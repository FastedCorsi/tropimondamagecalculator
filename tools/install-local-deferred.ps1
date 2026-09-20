param(
    [string]$LauncherRoot = $(if ($env:TROPIMON_HOME) { $env:TROPIMON_HOME } else { Join-Path $env:APPDATA '.tropimon' }),
    [int]$PollSeconds = 5
)

# By FastedCorsi. Entrée locale pour le stockage géré du launcher.
$ErrorActionPreference = 'Stop'
$jars = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'TropimonDamageCalc-*-LOCAL.jar' -File)
if ($jars.Count -ne 1) { throw 'Un unique JAR LOCAL est requis.' }
& (Join-Path $PSScriptRoot 'InstallManagedLocalMod.ps1') -SourceJar $jars[0].FullName `
    -ExpectedModId 'tropimon_damage_calc' -LauncherRoot $LauncherRoot -PollSeconds $PollSeconds
exit $LASTEXITCODE

