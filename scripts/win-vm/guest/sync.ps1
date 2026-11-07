# Синхронизация Z:\mimir\src -> C:\mimir (SYSTEM через guest-agent). Код robocopy < 8 — успех.
param(
    [string]$Source = 'Z:\mimir\src',
    [string]$Dest = 'C:\mimir'
)
$ErrorActionPreference = 'Stop'
& robocopy.exe $Source $Dest /MIR /XD "$Dest\build" "$Dest\*\build" "$Dest\libs\*\build" "$Dest\apps\*\build" .gradle .kotlin node_modules /XF mise.local.toml /NFL /NDL /NJH /NP
if ($LASTEXITCODE -lt 8) { exit 0 }
exit $LASTEXITCODE
