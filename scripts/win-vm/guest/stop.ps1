# Останавливает прежний Mimir перед новым win:run (SYSTEM через guest-agent): dev-запуск (java dev.mimir.desktop)
# и установленный MSI (<AppName>.exe), затем ждёт выхода прежнего agent.ps1 — его Gradle дописывает в run.log
# «BUILD FAILED» уже после остановки приложения. Вывод: строки «stopped <pid> <name>», «wait-timeout».
param(
    [Parameter(Mandatory)][string]$AppName,
    [int]$TimeoutSec = 60
)
$ErrorActionPreference = 'Stop'
$apps = @(Get-CimInstance Win32_Process | Where-Object {
        ($_.Name -eq 'java.exe' -and $_.CommandLine -like '*dev.mimir.desktop*') -or $_.Name -eq "$AppName.exe"
    })
if ($apps.Count -eq 0) { exit 0 }
$agents = @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
        Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -like '*agent.ps1*' })
foreach ($a in $apps) {
    Stop-Process -Id $a.ProcessId -Force -ErrorAction SilentlyContinue
    "stopped $($a.ProcessId) $($a.Name)"
}
$deadline = (Get-Date).AddSeconds($TimeoutSec)
while (@($agents | Where-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }).Count -gt 0) {
    if ((Get-Date) -gt $deadline) { 'wait-timeout'; break }
    Start-Sleep -Milliseconds 500
}
exit 0
