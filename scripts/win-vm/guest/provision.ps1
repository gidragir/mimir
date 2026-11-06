# Разовая подготовка гостя (SYSTEM через guest-agent). Идемпотентен.
# Ставит mise, добавляет его в машинный Path, регистрирует задачу MimirDev на интерактивного пользователя.
param(
    [string]$ToolsDir = 'C:\mimir-tools',
    [string]$MiseVersion = '2026.11.2'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$user = (Get-CimInstance Win32_ComputerSystem).UserName
if (-not $user) {
    [Console]::Error.WriteLine('No interactive user is logged in')
    exit 4
}

$miseBin = Join-Path $ToolsDir 'mise\bin'
$miseExe = Join-Path $miseBin 'mise.exe'
$installed = $false
if (Test-Path $miseExe) {
    $installed = ((& $miseExe --version) -join ' ') -like "*$MiseVersion*"
}
if (-not $installed) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
    $zip = Join-Path $ToolsDir "mise-v$MiseVersion.zip"
    $url = "https://github.com/jdx/mise/releases/download/v$MiseVersion/mise-v$MiseVersion-windows-x64.zip"
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    $tmp = Join-Path $ToolsDir 'unpack'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    # раскладка архива может отличаться (mise\bin\mise.exe или mise.exe в корне) — ищем сами
    $found = Get-ChildItem -Path $tmp -Recurse -Filter 'mise.exe' | Select-Object -First 1
    if (-not $found) { throw "mise.exe not found in $url" }
    New-Item -ItemType Directory -Force -Path $miseBin | Out-Null
    Copy-Item -Force -Path $found.FullName -Destination $miseExe
    Remove-Item -Recurse -Force $tmp
    Remove-Item -Force $zip
}

$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
if (($machinePath -split ';') -notcontains $miseBin) {
    [Environment]::SetEnvironmentVariable('Path', ($machinePath.TrimEnd(';') + ';' + $miseBin), 'Machine')
}

$agentArgs = '-WindowStyle Hidden -NoProfile -ExecutionPolicy Bypass -File C:\mimir\scripts\win-vm\guest\agent.ps1 -Out Z:\mimir\out -Root C:\mimir'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $agentArgs
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
# Parallel: повторный win:run не должен игнорироваться, пока старый агент ждёт приложение; без лимита времени
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -MultipleInstances Parallel -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName 'MimirDev' -Action $action -Principal $principal -Settings $settings -Force | Out-Null
Write-Output "mise $MiseVersion in $miseBin; task MimirDev registered for $user"
