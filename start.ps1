# Starts JobPilot on this PC: backend (http://localhost:8080) and frontend (http://localhost:5173), each in its own window.
# Settings and secrets come from .env.local next to this file (not committed). Usage: right-click > Run with PowerShell,
# or in a terminal:  powershell -ExecutionPolicy Bypass -File start.ps1
param([switch]$BackendOnly)   # -BackendOnly: restart just the backend (frontend keeps running)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $root '.env.local'
if (-not (Test-Path $envFile)) { throw ".env.local not found next to start.ps1" }

# KEY=VALUE lines become environment variables for both windows
foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim()) }
}

Start-Process powershell -ArgumentList '-NoExit', '-Command', "Set-Location '$root\backend'; `$host.UI.RawUI.WindowTitle = 'JobPilot backend'; .\mvnw.cmd -o -q spring-boot:run"
if ($BackendOnly) { Write-Host 'Backend restarting.'; exit }
Start-Process powershell -ArgumentList '-NoExit', '-Command', "Set-Location '$root\frontend';`$host.UI.RawUI.WindowTitle = 'JobPilot frontend'; npm run dev"

Write-Host 'Waiting for the backend to start...'
for ($i = 0; $i -lt 90; $i++) {
    try { Invoke-RestMethod http://localhost:8080/actuator/health -TimeoutSec 2 | Out-Null; break } catch { Start-Sleep -Seconds 2 }
}
Start-Process 'http://localhost:5173'
Write-Host 'JobPilot is running. Close the two JobPilot windows to stop it.'
