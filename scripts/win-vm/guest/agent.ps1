# Агент гостя: запускается задачей MimirDev в интерактивной сессии пользователя.
# Читает request.json, выполняет действие, публикует status.json и логи в $Out (virtiofs).
param(
    [string]$Out = 'Z:\mimir\out',
    [string]$Root = 'C:\mimir'
)
$ErrorActionPreference = 'Stop'
$script:ReqId = ''
$utf8 = New-Object System.Text.UTF8Encoding($false)

# Свежий Path: машинный Path мог измениться (provision) после входа пользователя.
$env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')

function Get-CurrentRequestId {
    try { (Get-Content -Raw -Encoding UTF8 (Join-Path $Out 'request.json') | ConvertFrom-Json).id } catch { '' }
}

# Атомарная запись: временный файл + Move-Item. Если запрос уже вытеснен новым — статус не пишем.
function Set-Status([string]$State, [string]$Step, [int]$Code = 0) {
    if ((Get-CurrentRequestId) -ne $script:ReqId) { return }
    $finished = $null
    if ($State -eq 'ok' -or $State -eq 'failed') { $finished = (Get-Date).ToUniversalTime().ToString('o') }
    $json = [ordered]@{ id = $script:ReqId; state = $State; step = $Step; exitCode = $Code; finishedAt = $finished } | ConvertTo-Json -Compress
    $tmp = Join-Path $Out 'status.json.tmp'
    [IO.File]::WriteAllText($tmp, $json, $utf8)
    Move-Item -Force -Path $tmp -Destination (Join-Path $Out 'status.json')
}

function Stop-MimirApp {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*dev.mimir.desktop*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Invoke-Run {
    Set-Status 'running' 'run'
    Stop-MimirApp
    $log = Join-Path $Out 'run.log'
    $err = Join-Path $Out 'run.err.log'
    # PowerShell 5.1 не пишет оба потока Start-Process в один файл: два лога, win:logs run хвостит оба.
    $p = Start-Process -FilePath 'mise' -ArgumentList 'exec', '--', 'cmd', '/c', 'gradlew.bat', ':apps:desktop:run' `
        -WorkingDirectory $Root -RedirectStandardOutput $log -RedirectStandardError $err `
        -WindowStyle Hidden -PassThru
    $null = $p.Handle   # кэшируем дескриптор, иначе ExitCode может оказаться пустым
    $launched = $false
    while (-not $p.HasExited) {
        if (-not $launched) {
            try {
                if (Select-String -Path $log -SimpleMatch -Quiet -Pattern '> Task :apps:desktop:run') {
                    $launched = $true
                    Set-Status 'launched' 'run'
                }
            } catch { }   # файл может быть занят процессом
        }
        Start-Sleep -Seconds 1
    }
    $p.WaitForExit()
    if ($p.ExitCode -eq 0) { Set-Status 'ok' 'run' 0 } else { Set-Status 'failed' 'run' $p.ExitCode }
}

# Запуск команды с выводом в лог через cmd.exe: в PowerShell 5.1 stderr нативных команд при
# $ErrorActionPreference='Stop' превращается в исключение. Возвращает код процесса.
function Invoke-Logged([string]$CommandLine, [string]$Log) {
    & cmd.exe /c "$CommandLine >> `"$Log`" 2>&1"
    return $LASTEXITCODE
}

function Invoke-Prepare {
    Set-Status 'running' 'prepare'
    $log = Join-Path $Out 'build.log'
    Set-Content -Path $log -Value '' -Encoding ASCII
    Set-Location $Root
    foreach ($cmd in @("mise trust `"$Root\mise.toml`"", 'mise install java', 'mise exec -- cmd /c gradlew.bat --version')) {
        Add-Content -Path $log -Value "> $cmd"
        $code = Invoke-Logged $cmd $log
        if ($code -ne 0) { Set-Status 'failed' 'prepare' $code; return }
    }
    Set-Status 'ok' 'prepare' 0
}

function Stop-InstalledMimir([string]$AppName) {
    Get-Process -Name $AppName -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

# Ключи удаления установленного продукта (per-user MSI регистрируется в HKCU).
function Get-MimirUninstallKeys([string]$AppName) {
    Get-ChildItem 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall' -ErrorAction SilentlyContinue |
        Where-Object { (Get-ItemProperty -LiteralPath $_.PSPath).DisplayName -eq $AppName }
}

# Отчёт об остатках после удаления: ярлыки, ключ mimir://, каталог установки. report.log (msi.log затирается msiexec /i).
function Write-UninstallReport([string]$AppName, $InstallLocations) {
    $startMenu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'
    $desktop = [Environment]::GetFolderPath('Desktop')
    $lnkMenu = @(Get-ChildItem -Path $startMenu -Recurse -Filter "$AppName*.lnk" -ErrorAction SilentlyContinue | ForEach-Object FullName)
    $lnkDesk = @(Get-ChildItem -Path $desktop -Filter "$AppName*.lnk" -ErrorAction SilentlyContinue | ForEach-Object FullName)
    $uri = Test-Path 'HKCU:\Software\Classes\mimir'
    $lines = @(
        "Start menu shortcuts: " + $(if ($lnkMenu) { $lnkMenu -join '; ' } else { 'none' }),
        "Desktop shortcuts: " + $(if ($lnkDesk) { $lnkDesk -join '; ' } else { 'none' }),
        "HKCU\Software\Classes\mimir: " + $(if ($uri) { 'present' } else { 'absent' }),
        "Install location: " + $(if ($InstallLocations) { ($InstallLocations | ForEach-Object { $_ + $(if (Test-Path $_) { ' (exists)' } else { ' (gone)' }) }) -join '; ' } else { 'not installed' })
    )
    [IO.File]::WriteAllText((Join-Path $Out 'report.log'), (($lines -join "`r`n") + "`r`n"), $utf8)
}

# Удаляет установленный MSI по ключам реестра (нет ключа — не ошибка). Возвращает код msiexec (0 — успех).
function Uninstall-Mimir([string]$AppName) {
    Set-Status 'running' 'uninstall'
    Stop-InstalledMimir $AppName
    $keys = @(Get-MimirUninstallKeys $AppName)
    $locations = @($keys | ForEach-Object { (Get-ItemProperty -LiteralPath $_.PSPath).InstallLocation } | Where-Object { $_ })
    $code = 0
    foreach ($k in $keys) {
        $p = Start-Process -FilePath 'msiexec.exe' -ArgumentList '/x', $k.PSChildName, '/qn', '/l*v', "`"$Out\msi-uninstall.log`"" -Wait -PassThru
        if ($p.ExitCode -ne 0 -and $p.ExitCode -ne 3010) { $code = $p.ExitCode }
    }
    Write-UninstallReport $AppName $locations
    return $code
}

function Invoke-Msi($Request) {
    $log = Join-Path $Out 'build.log'
    Set-Content -Path $log -Value '' -Encoding ASCII
    Set-Location $Root
    Set-Status 'running' 'build'
    $code = Invoke-Logged 'mise exec -- cmd /c gradlew.bat :apps:desktop:packageMsi' $log
    if ($code -ne 0) { Set-Status 'failed' 'build' $code; return }
    if (-not $Request.upgrade) {
        # без --upgrade старую версию удаляем: одинаковая версия MSI даёт ошибку 1638
        $code = [int](@(Uninstall-Mimir $Request.appName)[-1])
        if ($code -ne 0) { Set-Status 'failed' 'uninstall' $code; return }
    }
    Set-Status 'running' 'install'
    $msiLog = Join-Path $Out 'msi.log'
    $msi = Get-ChildItem (Join-Path $Root 'apps\desktop\build\compose\binaries\main\msi') -Filter '*.msi' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $msi) {
        Set-Content -Path $msiLog -Value 'No .msi found in apps\desktop\build\compose\binaries\main\msi' -Encoding ASCII
        Set-Status 'failed' 'install' 2
        return
    }
    $p = Start-Process -FilePath 'msiexec.exe' -ArgumentList '/i', "`"$($msi.FullName)`"", '/qn', '/l*v', "`"$msiLog`"" -Wait -PassThru
    if ($p.ExitCode -ne 0 -and $p.ExitCode -ne 3010) { Set-Status 'failed' 'install' $p.ExitCode; return }
    # запуск через ярлык меню «Пуск» проверяет сам лаунчер
    $lnk = Get-ChildItem -Path (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs') -Recurse -Filter "$($Request.appName)*.lnk" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($lnk) { Start-Process -FilePath $lnk.FullName }
    Set-Status 'ok' 'install' 0
}

function Invoke-Uninstall($Request) {
    $code = [int](@(Uninstall-Mimir $Request.appName)[-1])
    if ($code -ne 0) { Set-Status 'failed' 'uninstall' $code } else { Set-Status 'ok' 'uninstall' 0 }
}

try {
    $req = Get-Content -Raw -Encoding UTF8 (Join-Path $Out 'request.json') | ConvertFrom-Json
    $script:ReqId = $req.id
    switch ($req.action) {
        'run' { Invoke-Run }
        'prepare' { Invoke-Prepare }
        'msi' { Invoke-Msi $req }
        'uninstall' { Invoke-Uninstall $req }
        default { Set-Status 'failed' 'agent' 64 }
    }
} catch {
    Add-Content -Path (Join-Path $Out 'agent.log') -Value ("{0} {1}" -f (Get-Date -Format o), $_ | Out-String)
    Set-Status 'failed' 'agent' 1
    exit 1
}
