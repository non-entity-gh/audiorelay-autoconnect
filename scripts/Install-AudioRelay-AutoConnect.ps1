# Installs AudioRelay 0.27.5 (stable) from audiorelay.net, then the latest AudioRelay Auto-Connect mod from GitHub.
#
# Download it from https://github.com/non-entity-gh/audiorelay-autoconnect/blob/main/scripts/Install-AudioRelay-AutoConnect.ps1
# (or the raw link in the README), then run it from PowerShell in the folder you saved it to:
#   powershell -ExecutionPolicy Bypass -File .\Install-AudioRelay-AutoConnect.ps1 -Server 192.168.1.201
#
# Options:
#   -Server ADDRESS        IP or hostname of the PC running the AudioRelay server (asked if omitted)
#   -SkipAudioRelay        only install/update the mod (AudioRelay is already installed)
#   -AudioRelayVersion X   AudioRelay version to download (default 0.27.5)
#
# Safety checks: the mod zip is verified against the release's SHA256SUMS.txt, and the AudioRelay
# installer's digital signature is shown (you're asked before running it if it isn't valid).
param(
    [string]$Server = "",
    [switch]$SkipAudioRelay,
    [string]$AudioRelayVersion = "0.27.5"
)

$ErrorActionPreference = "Stop"
$Repo = "non-entity-gh/audiorelay-autoconnect"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = "SilentlyContinue"   # makes Invoke-WebRequest much faster on Windows PowerShell 5

function Pause-Exit($code) { Write-Host ""; Read-Host "Press Enter to close"; exit $code }

try {
    if ($Server -and $Server -notmatch '^[A-Za-z0-9\[][A-Za-z0-9._:%\[\]-]{0,252}$') {
        throw "'$Server' doesn't look like an IP address or hostname."
    }
    if (-not $Server) {
        $Server = (Read-Host "IP address (or hostname) of the PC running the AudioRelay SERVER").Trim()
        if ($Server -notmatch '^[A-Za-z0-9\[][A-Za-z0-9._:%\[\]-]{0,252}$') { throw "'$Server' doesn't look like an IP address or hostname." }
    }

    $work = Join-Path $env:TEMP ("audiorelay-setup-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
    New-Item -ItemType Directory -Path $work | Out-Null

    # ---------- 1. AudioRelay ----------
    if (-not $SkipAudioRelay) {
        $running = @(Get-Process -Name "AudioRelay" -ErrorAction SilentlyContinue)
        if ($running.Count -gt 0) {
            $ans = Read-Host "AudioRelay is running and must be closed to install. Close it now? (y/n)"
            if ($ans -notmatch '^[yY]') { throw "Cancelled. Nothing was changed." }
            $running | Stop-Process -Force
            $running | Wait-Process -Timeout 15 -ErrorAction SilentlyContinue
        }

        $url = "https://dl.audiorelay.net/setups/windows/audiorelay-$AudioRelayVersion.exe"
        $setup = Join-Path $work "audiorelay-$AudioRelayVersion.exe"
        Write-Host "Downloading AudioRelay $AudioRelayVersion from audiorelay.net..."
        Invoke-WebRequest -Uri $url -OutFile $setup -UseBasicParsing

        $sig = Get-AuthenticodeSignature -LiteralPath $setup
        Write-Host "Installer signature: $($sig.Status) $(if ($sig.SignerCertificate) { '- ' + $sig.SignerCertificate.Subject })"
        if ($sig.Status -ne "Valid") {
            $ans = Read-Host "The installer isn't validly signed (AudioRelay may simply not sign it). It was downloaded from the official site over HTTPS. Install anyway? (y/n)"
            if ($ans -notmatch '^[yY]') { throw "Cancelled. Nothing was installed." }
        }

        Write-Host "Installing AudioRelay $AudioRelayVersion (approve the admin prompt)..."
        $p = Start-Process -FilePath $setup -ArgumentList "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-" -Verb RunAs -Wait -PassThru
        if ($p.ExitCode -ne 0) { throw "The AudioRelay installer exited with code $($p.ExitCode)." }
        Start-Sleep -Seconds 2
        # The installer may start AudioRelay when it finishes; the mod installer needs it closed.
        Get-Process -Name "AudioRelay" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        Write-Host "AudioRelay installed." -ForegroundColor Green
    }

    # ---------- 2. The mod, latest release from GitHub ----------
    Write-Host "Looking up the latest AudioRelay Auto-Connect release..."
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers @{ "User-Agent" = "audiorelay-autoconnect-installer" }
    $zipAsset = $rel.assets | Where-Object { $_.name -eq "audiorelay-autoconnect-windows.zip" }
    $sumAsset = $rel.assets | Where-Object { $_.name -eq "SHA256SUMS.txt" }
    if (-not $zipAsset -or -not $sumAsset) { throw "Release $($rel.tag_name) is missing its files; try again in a few minutes." }

    $zip = Join-Path $work $zipAsset.name
    $sums = Join-Path $work "SHA256SUMS.txt"
    Write-Host "Downloading mod $($rel.tag_name)..."
    Invoke-WebRequest -Uri $zipAsset.browser_download_url -OutFile $zip -UseBasicParsing
    Invoke-WebRequest -Uri $sumAsset.browser_download_url -OutFile $sums -UseBasicParsing

    $expected = (Get-Content -LiteralPath $sums | Where-Object { $_ -match '\saudiorelay-autoconnect-windows\.zip$' }) -split '\s+' | Select-Object -First 1
    $actual = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
    if (-not $expected -or $actual -ne $expected.ToUpper()) { throw "Checksum mismatch for the mod zip; not installing it." }
    Write-Host "Checksum OK."

    Expand-Archive -LiteralPath $zip -DestinationPath $work -Force
    $installer = Get-ChildItem -LiteralPath $work -Recurse -Filter "Install.ps1" | Select-Object -First 1
    if (-not $installer) { throw "Install.ps1 not found in the mod zip." }

    Write-Host "Running the mod installer..."
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installer.FullName -Server $Server
    exit $LASTEXITCODE
}
catch {
    Write-Host ""
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    Pause-Exit 1
}
