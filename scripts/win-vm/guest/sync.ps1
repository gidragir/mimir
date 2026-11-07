# Синхронизация Z:\mimir\src -> C:\mimir (SYSTEM через guest-agent). Код robocopy < 8 — успех.
param(
    [string]$Source = 'Z:\mimir\src',
    [string]$Dest = 'C:\mimir'
)
$ErrorActionPreference = 'Stop'
# Выходные каталоги build — только как в .gitignore (/build, /*/build, /libs/*/build, /apps/*/build):
# голое имя build в /XD вырезало бы и исходники (build-logic/.../dev/mimir/build). Подстановки в середине пути robocopy не принимает.
$buildDirs = @("$Dest\build")
foreach ($top in Get-ChildItem -LiteralPath $Source -Directory -Force) {
    $buildDirs += "$Dest\$($top.Name)\build"
    if ($top.Name -in 'libs', 'apps') {
        foreach ($sub in Get-ChildItem -LiteralPath $top.FullName -Directory -Force) { $buildDirs += "$Dest\$($top.Name)\$($sub.Name)\build" }
    }
}
& robocopy.exe $Source $Dest /MIR /XD @buildDirs .gradle .kotlin node_modules /XF mise.local.toml /NFL /NDL /NJH /NP
if ($LASTEXITCODE -lt 8) { exit 0 }
exit $LASTEXITCODE
