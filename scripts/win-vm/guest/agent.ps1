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
    $p = Start-Process -FilePath 'mise' -ArgumentList 'exec', '--', 'gradlew.bat', ':apps:desktop:run' `
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

try {
    $req = Get-Content -Raw -Encoding UTF8 (Join-Path $Out 'request.json') | ConvertFrom-Json
    $script:ReqId = $req.id
    switch ($req.action) {
        'run' { Invoke-Run }
        default { Set-Status 'failed' 'agent' 64 }
    }
} catch {
    Add-Content -Path (Join-Path $Out 'agent.log') -Value ("{0} {1}" -f (Get-Date -Format o), $_ | Out-String)
    Set-Status 'failed' 'agent' 1
    exit 1
}
