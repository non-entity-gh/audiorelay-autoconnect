# AudioRelay Auto-Connect installer for Windows. Easiest: double-click Install.bat
# What it does: copies audiorelay-autoconnect.jar into AudioRelay's app folder and adds ONE line to
# AudioRelay.cfg (a backup is saved first). Nothing is downloaded and nothing else is changed.
#   -Server 192.168.1.50   skip the prompt
#   -NoPersistent          only connect at launch (retrying until the first success), never reconnect after
#   -AppDir "D:\Apps\AudioRelay"   if AudioRelay isn't installed in the default place
param([string]$Server = "", [switch]$NoPersistent, [string]$AppDir = "")

$logFile = Join-Path $env:TEMP "audiorelay-autoconnect-install.log"
function Pause-Exit($code) { Write-Host ""; Read-Host "Press Enter to close"; exit $code }

function Find-AudioRelay([string]$hint) {
    $candidates = @()
    if ($hint) { $candidates += $hint; $candidates += (Join-Path $hint "app") }
    foreach ($base in @(${env:ProgramFiles(x86)}, $env:ProgramFiles, $env:LOCALAPPDATA)) {
        if ($base) { $candidates += (Join-Path $base "AudioRelay\app") }
    }
    # The installer's registry entry knows custom install locations
    foreach ($key in @("HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
                       "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*",
                       "HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*")) {
        Get-ItemProperty -Path $key -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -like "AudioRelay*" -and $_.InstallLocation } |
            ForEach-Object { $candidates += (Join-Path $_.InstallLocation "app") }
    }
    foreach ($c in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $c "audiorelay.jar")) { return $c }
    }
    return $null
}

try {
    Start-Transcript -Path $logFile -Append | Out-Null
    $ErrorActionPreference = "Stop"

    $jar = Join-Path $PSScriptRoot "audiorelay-autoconnect.jar"
    if (-not (Test-Path -LiteralPath $jar)) {
        Write-Host "Can't find audiorelay-autoconnect.jar next to this script." -ForegroundColor Red
        Write-Host "Extract the whole zip first (right-click > Extract All), then run Install.bat from the extracted folder."
        Pause-Exit 1
    }

    $appDir = Find-AudioRelay $AppDir
    if (-not $appDir) {
        Write-Host "AudioRelay not found. If it's installed somewhere unusual, run:" -ForegroundColor Red
        Write-Host '  Install.bat -AppDir "D:\path\to\AudioRelay"'
        Pause-Exit 1
    }
    $cfg = Join-Path $appDir "AudioRelay.cfg"
    Write-Host "AudioRelay found: $appDir"

    if (-not $Server) { $Server = Read-Host "IP address (or hostname) of the computer running the AudioRelay SERVER" }
    $Server = $Server.Trim()
    if ($Server -notmatch '^[A-Za-z0-9\[][A-Za-z0-9._:%\[\]-]{0,252}$') {
        Write-Host "That doesn't look like an IP address or hostname: '$Server'" -ForegroundColor Red
        Pause-Exit 1
    }

    # Admin rights are needed only if the app folder isn't writable (e.g. Program Files)
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    $writable = $true
    try { $probe = Join-Path $appDir ".autoconnect-write-test"; [IO.File]::WriteAllText($probe, ""); Remove-Item -LiteralPath $probe } catch { $writable = $false }
    if (-not $writable -and -not $isAdmin) {
        Write-Host "Asking for administrator permission (AudioRelay's folder needs it)..."
        $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"", "-Server", "`"$Server`"", "-AppDir", "`"$appDir`"")
        if ($NoPersistent) { $argList += "-NoPersistent" }
        Start-Process powershell.exe -Verb RunAs -ArgumentList $argList
        Stop-Transcript | Out-Null
        exit 0
    }

    # AudioRelay must be closed: its files are in use while it runs
    $running = @(Get-Process -Name "AudioRelay" -ErrorAction SilentlyContinue)
    if ($running.Count -gt 0) {
        $ans = Read-Host "AudioRelay is running and must be closed to install. Close it now? (y/n)"
        if ($ans -notmatch '^[yY]') { Write-Host "Cancelled. Nothing was changed."; Pause-Exit 1 }
        $running | Stop-Process -Force
        $running | Wait-Process -Timeout 15 -ErrorAction SilentlyContinue
    }

    Copy-Item -LiteralPath $jar -Destination (Join-Path $appDir "audiorelay-autoconnect.jar") -Force
    if (-not (Test-Path -LiteralPath "$cfg.bak-autoconnect")) { Copy-Item -LiteralPath $cfg -Destination "$cfg.bak-autoconnect" }

    $opts = "host=$Server"
    if ($NoPersistent) { $opts += ",persistent=false" }
    $agentLine = 'java-options=-javaagent:$APPDIR\audiorelay-autoconnect.jar=' + $opts

    $lines = @(Get-Content -LiteralPath $cfg | Where-Object { $_ -notmatch 'audiorelay-autoconnect\.jar' })
    $out = New-Object System.Collections.Generic.List[string]
    $added = $false
    foreach ($l in $lines) {
        $out.Add($l)
        if ($l.Trim() -eq "[JavaOptions]") { $out.Add($agentLine); $added = $true }
    }
    if (-not $added) { $out.Add("[JavaOptions]"); $out.Add($agentLine) }
    [IO.File]::WriteAllLines($cfg, $out.ToArray(), (New-Object System.Text.ASCIIEncoding))

    Write-Host ""
    Write-Host "Installed. AudioRelay will auto-connect to $Server when it starts." -ForegroundColor Green
    Write-Host "Mod log (after AudioRelay starts): %TEMP%\audiorelay-autoconnect.log  (for the user running AudioRelay)"
    Write-Host "After an AudioRelay update, run Install.bat again."
    Stop-Transcript | Out-Null
    Pause-Exit 0
}
catch {
    Write-Host ""
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host $_.InvocationInfo.PositionMessage
    Write-Host "Details saved to $logFile"
    try { Stop-Transcript | Out-Null } catch {}
    Pause-Exit 1
}
