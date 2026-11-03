# Removes AudioRelay Auto-Connect. Easiest: double-click Uninstall.bat   (optional: -AppDir "D:\path\to\AudioRelay")
param([string]$AppDir = "")
function Pause-Exit($code) { Write-Host ""; Read-Host "Press Enter to close"; exit $code }
try {
    $ErrorActionPreference = "Stop"
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdmin) {
        $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"")
        if ($AppDir) { $argList += @("-AppDir", "`"$AppDir`"") }
        Start-Process powershell.exe -Verb RunAs -ArgumentList $argList
        exit 0
    }

    $running = @(Get-Process -Name "AudioRelay" -ErrorAction SilentlyContinue)
    if ($running.Count -gt 0) {
        $ans = Read-Host "AudioRelay is running and must be closed to uninstall. Close it now? (y/n)"
        if ($ans -notmatch '^[yY]') { Write-Host "Cancelled. Nothing was changed."; Pause-Exit 1 }
        $running | Stop-Process -Force
        $running | Wait-Process -Timeout 15 -ErrorAction SilentlyContinue
    }

    $dirs = @()
    if ($AppDir) { $dirs += $AppDir; $dirs += (Join-Path $AppDir "app") }
    foreach ($base in @(${env:ProgramFiles(x86)}, $env:ProgramFiles, $env:LOCALAPPDATA)) { if ($base) { $dirs += (Join-Path $base "AudioRelay\app") } }
    foreach ($key in @("HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
                       "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*",
                       "HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*")) {
        Get-ItemProperty -Path $key -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -like "AudioRelay*" -and $_.InstallLocation } |
            ForEach-Object { $dirs += (Join-Path $_.InstallLocation "app") }
    }

    $done = $false
    foreach ($appDir in ($dirs | Select-Object -Unique)) {
        $cfg = Join-Path $appDir "AudioRelay.cfg"
        if (-not (Test-Path -LiteralPath $cfg)) { continue }
        $content = @(Get-Content -LiteralPath $cfg)
        $jarPath = Join-Path $appDir "audiorelay-autoconnect.jar"
        if (-not ($content -match 'audiorelay-autoconnect\.jar') -and -not (Test-Path -LiteralPath $jarPath)) { continue }
        $lines = @($content | Where-Object { $_ -notmatch 'audiorelay-autoconnect\.jar' })
        [IO.File]::WriteAllLines($cfg, $lines, (New-Object System.Text.ASCIIEncoding))
        if (Test-Path -LiteralPath $jarPath) { Remove-Item -LiteralPath $jarPath -Force }
        if (Test-Path -LiteralPath "$cfg.bak-autoconnect") { Remove-Item -LiteralPath "$cfg.bak-autoconnect" -Force }
        Write-Host "Removed from $appDir" -ForegroundColor Green
        $done = $true
    }
    if (-not $done) { Write-Host "Auto-Connect isn't installed; nothing to remove." }
    Pause-Exit 0
}
catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    Pause-Exit 1
}
