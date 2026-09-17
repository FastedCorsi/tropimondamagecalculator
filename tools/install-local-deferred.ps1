param(
    [string]$LauncherRoot = $(if ($env:TROPIMON_HOME) {
        $env:TROPIMON_HOME
    } else {
        Join-Path $env:APPDATA '.tropimon'
    }),
    [int]$PollSeconds = 5
)

# By FastedCorsi. Outil local externe ; il n'est jamais embarqué dans le JAR public.
$ErrorActionPreference = 'Stop'
$modId = 'tropimon_damage_calc'
$filePrefix = 'TropimonDamageCalc'
$deliveryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$statusFile = Join-Path $deliveryRoot 'install-status.json'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Write-Status([string]$State, [string]$Detail) {
    [pscustomobject]@{
        state = $State
        detail = $Detail
        updatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $statusFile -Encoding UTF8
}

function Read-Manifest([string]$Path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry('fabric.mod.json')
        if ($null -eq $entry -or $entry.Length -gt 1048576) { throw 'Métadonnées du JAR absentes ou invalides.' }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { return ($reader.ReadToEnd() | ConvertFrom-Json) } finally { $reader.Dispose() }
    } finally {
        $archive.Dispose()
    }
}

function Get-Hash([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Game-Running([string]$Instance) {
    foreach ($process in (Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'")) {
        $line = $process.CommandLine
        if ([string]::IsNullOrWhiteSpace($line)) { throw 'Impossible de vérifier un processus Java.' }
        if ($line -notmatch 'KnotClient|net\.minecraft\.client|--gameDir|--launchTarget') { continue }
        $match = [regex]::Match($line, '--gameDir(?:\s+|=)(?:"([^"]+)"|([^\s"]+))')
        if ($match.Success) {
            $gameDir = if ($match.Groups[1].Value) { $match.Groups[1].Value } else { $match.Groups[2].Value }
            if ([IO.Path]::GetFullPath($gameDir).TrimEnd('\', '/') -ieq $Instance.TrimEnd('\', '/')) {
                return $true
            }
        } elseif ($line.IndexOf($Instance, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            return $true
        } else {
            throw 'Instance Minecraft active impossible à identifier.'
        }
    }
    return $false
}

try {
    $sources = @(Get-ChildItem -LiteralPath $deliveryRoot -Filter ($filePrefix + '-*-LOCAL.jar') -File)
    if ($sources.Count -ne 1) { throw 'Un seul JAR local est attendu.' }
    $source = $sources[0].FullName
    $hashFile = $source + '.sha256'
    if (-not (Test-Path -LiteralPath $hashFile -PathType Leaf)) { throw 'Empreinte SHA-256 absente.' }
    $expectedHash = ((Get-Content -LiteralPath $hashFile -Raw).Trim() -split '\s+')[0]
    if ($expectedHash -notmatch '^[0-9A-Fa-f]{64}$' -or (Get-Hash $source) -ine $expectedHash) {
        throw 'Intégrité du JAR local invalide.'
    }
    $incoming = Read-Manifest $source
    if ($incoming.id -cne $modId -or @($incoming.authors) -cnotcontains 'By FastedCorsi') {
        throw 'Identité ou attribution du JAR invalide.'
    }

    $root = (Resolve-Path -LiteralPath $LauncherRoot).Path
    $mods = (Resolve-Path -LiteralPath (Join-Path $root 'mods')).Path
    if ([IO.Path]::GetDirectoryName($mods) -ine $root) { throw 'Dossier mods hors de cette instance.' }
    foreach ($directory in @($root, $mods)) {
        if ((Get-Item -LiteralPath $directory).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Instance redirigée non vérifiable.'
        }
    }

    Write-Status 'prepared' 'JAR, empreinte et instance vérifiés.'
    while (Game-Running $root) {
        Write-Status 'waiting' 'Minecraft utilise encore cette instance.'
        Start-Sleep -Seconds ([Math]::Max(2, $PollSeconds))
    }

    $installed = @(Get-ChildItem -LiteralPath $mods -Filter '*.jar' -File | Where-Object {
        try { (Read-Manifest $_.FullName).id -ceq $modId } catch { $false }
    })
    foreach ($old in $installed) {
        $oldMetadata = Read-Manifest $old.FullName
        if ([version]$oldMetadata.version -gt [version]$incoming.version) {
            throw 'Une version plus récente est déjà installée.'
        }
        $probe = [IO.File]::Open($old.FullName, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $probe.Dispose()
    }

    $backupDir = Join-Path $root ('mod-archive/' + $modId + '-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
    if ($backupDir.StartsWith($mods + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Dossier de sauvegarde invalide.'
    }
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    $staged = Join-Path $backupDir 'incoming.jar'
    Copy-Item -LiteralPath $source -Destination $staged
    if ((Get-Hash $staged) -ine $expectedHash) { throw 'Copie préparée invalide.' }
    if (Game-Running $root) { throw 'Minecraft a redémarré ; mise à jour conservée en attente.' }

    $destination = Join-Path $mods $sources[0].Name
    if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($destination)) -ine $mods) {
        throw 'Destination invalide.'
    }
    if ((Test-Path -LiteralPath $destination) -and $installed.FullName -notcontains $destination) {
        throw 'Un fichier inconnu occupe la destination.'
    }

    $moved = @()
    try {
        foreach ($old in $installed) {
            $backup = Join-Path $backupDir $old.Name
            Move-Item -LiteralPath $old.FullName -Destination $backup
            $moved += [pscustomobject]@{ Original = $old.FullName; Backup = $backup }
        }
        Move-Item -LiteralPath $staged -Destination $destination
        if ((Get-Hash $destination) -ine $expectedHash) { throw 'Contrôle final invalide.' }
    } catch {
        if (Test-Path -LiteralPath $destination) {
            Move-Item -LiteralPath $destination -Destination (Join-Path $backupDir 'failed-install.jar')
        }
        foreach ($entry in $moved) {
            if ((Test-Path -LiteralPath $entry.Backup) -and -not (Test-Path -LiteralPath $entry.Original)) {
                Move-Item -LiteralPath $entry.Backup -Destination $entry.Original
            }
        }
        throw
    }
    Write-Status 'installed' 'JAR installé après arrêt de Minecraft ; sauvegarde vérifiée hors du dossier mods.'
} catch {
    Write-Status 'blocked' ('Installation conservée sans remplacement : ' + $_.Exception.GetType().Name)
    exit 2
}

