param(
    [Parameter(Mandatory = $true)][string]$SourceJar,
    [Parameter(Mandatory = $true)][string]$ExpectedModId,
    [string]$LauncherRoot = $(if ($env:TROPIMON_HOME) { $env:TROPIMON_HOME } else { Join-Path $env:APPDATA '.tropimon' }),
    [int]$PollSeconds = 5,
    [switch]$CheckOnly
)

# By FastedCorsi. Local installation tool, never included in a public JAR.
# mods-user is the launcher's persistent import directory; mods is its runtime copy.
$ErrorActionPreference = 'Stop'
# Build processes may inherit a PowerShell 7 module path while invoking Windows PowerShell.
foreach ($module in @('Microsoft.PowerShell.Management', 'Microsoft.PowerShell.Utility', 'CimCmdlets')) {
    Import-Module (Join-Path $PSHOME ('Modules/' + $module + '/' + $module + '.psd1')) -ErrorAction Stop
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$locks = @()
$moves = @()
$created = @()
$mutex = $null
$ownsMutex = $false
$trackerReplaced = $false
$backupRoot = $null

function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
function Metadata([string]$Path) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $zip.GetEntry('fabric.mod.json')
        if (!$entry -or $entry.Length -gt 1048576) { throw 'Invalid Fabric metadata.' }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
}
function Safe-Child([string]$Parent, [string]$Leaf) {
    if ([IO.Path]::GetFileName($Leaf) -cne $Leaf -or $Leaf -in @('.', '..')) { throw 'Unsafe file name.' }
    $path = [IO.Path]::GetFullPath((Join-Path $Parent $Leaf))
    if ([IO.Path]::GetDirectoryName($path) -ine $Parent.TrimEnd('\', '/')) { throw 'Path outside expected directory.' }
    return $path
}
function No-Redirect([string]$Path) {
    if ((Get-Item -LiteralPath $Path).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Redirected installation path.' }
}
function Game-Running([string]$Instance) {
    foreach ($process in (Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'")) {
        $line = $process.CommandLine
        if ([string]::IsNullOrWhiteSpace($line)) { throw 'Cannot identify a Java process safely.' }
        if ($line -notmatch 'KnotClient|net\.minecraft\.client|--gameDir|--launchTarget') { continue }
        $match = [regex]::Match($line, '--gameDir(?:\s+|=)(?:"([^"]+)"|([^\s"]+))')
        if (!$match.Success) { throw 'Cannot identify a Minecraft instance safely.' }
        $gameDir = if ($match.Groups[1].Value) { $match.Groups[1].Value } else { $match.Groups[2].Value }
        if ([IO.Path]::GetFullPath($gameDir).TrimEnd('\', '/') -ieq $Instance.TrimEnd('\', '/')) { return $true }
    }
    return $false
}
function Mod-Files([string]$Directory) {
    foreach ($file in (Get-ChildItem -LiteralPath $Directory -Filter '*.jar' -File)) {
        No-Redirect $file.FullName
        $meta = Metadata $file.FullName
        if ($meta.id -ceq $ExpectedModId) {
            [pscustomobject]@{ Path = $file.FullName; Name = $file.Name; Hash = Hash $file.FullName; Version = [version]$meta.version }
        }
    }
}

try {
    $source = (Resolve-Path -LiteralPath $SourceJar).Path
    No-Redirect $source
    $name = [IO.Path]::GetFileName($source)
    if ($name -notmatch '^[A-Za-z0-9][A-Za-z0-9._+ -]*\.jar$') { throw 'Invalid JAR filename.' }
    $checksum = ((Get-Content -LiteralPath ($source + '.sha256') -Raw).Trim() -split '\s+')[0]
    if ($checksum -notmatch '^[0-9a-fA-F]{64}$' -or (Hash $source) -ine $checksum) { throw 'Source checksum mismatch.' }
    $incoming = Metadata $source
    if ($incoming.id -cne $ExpectedModId -or @($incoming.authors) -cnotcontains 'By FastedCorsi') { throw 'Unexpected mod identity or attribution.' }
    $incomingVersion = [version]$incoming.version
    $base = (Resolve-Path -LiteralPath $LauncherRoot).Path
    No-Redirect $base
    if (Test-Path -LiteralPath (Join-Path $base 'profiles') -PathType Container) {
        No-Redirect (Join-Path $base 'profiles')
        $profiles = @(Get-ChildItem -LiteralPath (Join-Path $base 'profiles') -Directory | Where-Object {
            Test-Path -LiteralPath (Join-Path $_.FullName 'instance/mods') -PathType Container
        })
        $running = @($profiles | Where-Object { Game-Running (Join-Path $_.FullName 'instance') })
        if ($running.Count -eq 1) { $profileRoot = $running[0].FullName }
        elseif ($profiles.Count -eq 1) { $profileRoot = $profiles[0].FullName }
        else { throw 'Ambiguous profile; provide its instance directory explicitly.' }
        $instance = Join-Path $profileRoot 'instance'
    } else {
        $instance = $base
        $profileRoot = Split-Path -Parent $instance
        if ((Split-Path -Leaf $instance) -cne 'instance') { throw 'Not a managed launcher instance.' }
    }
    $mods = Safe-Child $instance 'mods'
    $managed = Safe-Child $instance 'mods-user'
    $tracker = Safe-Child $profileRoot 'user-mods-tracked.json'
    foreach ($path in @($profileRoot, $instance, $mods, $managed, $tracker)) {
        if (!(Test-Path -LiteralPath $path)) { throw 'Managed launcher layout not recognized; no files changed.' }
        No-Redirect $path
    }
    if ((Split-Path -Parent $source) -in @($mods, $managed)) { throw 'Use a delivery JAR outside the launcher directories.' }

    $key = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($instance.ToLowerInvariant())).Replace('/', '_')
    $mutex = [Threading.Mutex]::new($false, ('Local\TropimonLocalInstall-' + $key))
    $ownsMutex = $mutex.WaitOne(0)
    if (!$ownsMutex) { throw 'Another local installation is already in progress for this profile.' }
    $trackerHash = Hash $tracker
    $trackerText = Get-Content -LiteralPath $tracker -Raw
    if (!$trackerText.TrimStart().StartsWith('[')) { throw 'Unrecognized launcher tracking format.' }
    $parsedTracker = ConvertFrom-Json -InputObject $trackerText
    $tracked = @($parsedTracker)
    foreach ($item in $tracked) {
        if ($item -isnot [string] -or [IO.Path]::GetFileName($item) -cne $item -or $item -notmatch '\.jar$') { throw 'Invalid launcher tracking entry.' }
    }
    $oldRuntime = @(Mod-Files $mods)
    $oldManaged = @(Mod-Files $managed)
    if ($oldRuntime.Count -gt 1 -or $oldManaged.Count -gt 1) { throw 'Multiple copies of the mod; refusing an ambiguous replacement.' }
    $oldFiles = @($oldRuntime) + @($oldManaged)
    $oldPaths = @($oldFiles | ForEach-Object { $_.Path })
    if (@($oldFiles | Where-Object { $_.Version -gt $incomingVersion }).Count) { throw 'A newer version is already installed.' }
    foreach ($directory in @($mods, $managed)) {
        $target = Safe-Child $directory $name
        if ((Test-Path -LiteralPath $target) -and $target -notin $oldPaths) { throw 'Destination belongs to another mod.' }
    }
    $oldNames = @($oldFiles.Name)
    $newTracked = @($tracked | Where-Object { $_ -notin $oldNames -and $_ -cne $name }) + @($name)
    $alreadyInstalled = $oldFiles.Count -eq 2 -and @($oldFiles | Where-Object { $_.Name -cne $name -or $_.Hash -ine $checksum }).Count -eq 0 -and @($tracked | Where-Object { $_ -ceq $name }).Count -eq 1 -and @($tracked | Where-Object { $_ -in $oldNames -and $_ -cne $name }).Count -eq 0
    if ($CheckOnly -or $alreadyInstalled) {
        [pscustomobject]@{ state = $(if ($alreadyInstalled) { 'installed' } else { 'ready' }); modId = $ExpectedModId; version = $incoming.version; jar = $name; checkOnly = [bool]$CheckOnly } | ConvertTo-Json -Compress
        exit 0
    }
    while (Game-Running $instance) {
        Write-Output 'Waiting for this Minecraft instance to exit; launcher may remain open.'
        Start-Sleep -Seconds ([Math]::Max(2, $PollSeconds))
    }

    $archive = Safe-Child $instance 'mod-archive'
    if (!(Test-Path -LiteralPath $archive)) { New-Item -ItemType Directory -Path $archive | Out-Null }
    No-Redirect $archive
    $backupRoot = Safe-Child $archive ('managed-install-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $backupRoot | Out-Null
    foreach ($leaf in @('old-mods', 'old-mods-user', 'prepared', 'failed')) {
        New-Item -ItemType Directory -Path (Safe-Child $backupRoot $leaf) | Out-Null
    }
    $prepared = Safe-Child $backupRoot 'prepared'
    $stagedRuntime = Safe-Child $prepared 'runtime.jar'
    $stagedManaged = Safe-Child $prepared 'managed.jar'
    foreach ($stage in @($stagedRuntime, $stagedManaged)) {
        Copy-Item -LiteralPath $source -Destination $stage
        if ((Hash $stage) -ine $checksum) { throw 'Staged JAR checksum mismatch.' }
    }
    $newTracker = Safe-Child $prepared 'user-mods-tracked.json'
    [IO.File]::WriteAllText($newTracker, (ConvertTo-Json -InputObject @($newTracked)), [Text.UTF8Encoding]::new($false))
    $newTrackerHash = Hash $newTracker
    $trackerBackup = Safe-Child $backupRoot 'user-mods-tracked.before.json'

    # Hold read handles that deny writes while allowing our backup renames.
    foreach ($path in $oldPaths + @($tracker)) {
        $locks += [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, ([IO.FileShare]::Read -bor [IO.FileShare]::Delete))
    }
    if ((Hash $tracker) -ine $trackerHash) { throw 'Launcher tracking changed after preparation.' }
    foreach ($old in $oldFiles) { if ((Hash $old.Path) -ine $old.Hash) { throw 'An installed JAR changed after preparation.' } }
    $current = @(Mod-Files $mods) + @(Mod-Files $managed)
    if ($current.Count -ne $oldFiles.Count -or @($current | Where-Object { $_.Path -notin $oldPaths }).Count) { throw 'The installed mod set changed after preparation.' }
    if (Game-Running $instance) { throw 'Minecraft restarted; no replacement performed.' }

    foreach ($old in $oldFiles) {
        $bucket = if ((Split-Path -Parent $old.Path) -ieq $mods) { 'old-mods' } else { 'old-mods-user' }
        $backup = Safe-Child (Safe-Child $backupRoot $bucket) $old.Name
        Move-Item -LiteralPath $old.Path -Destination $backup
        $moves += [pscustomobject]@{ Original = $old.Path; Backup = $backup; Hash = $old.Hash }
    }
    $runtimeTarget = Safe-Child $mods $name
    $managedTarget = Safe-Child $managed $name
    Move-Item -LiteralPath $stagedRuntime -Destination $runtimeTarget
    $created += $runtimeTarget
    Move-Item -LiteralPath $stagedManaged -Destination $managedTarget
    $created += $managedTarget
    if ((Hash $runtimeTarget) -ine $checksum -or (Hash $managedTarget) -ine $checksum) { throw 'Installed JAR checksum mismatch.' }
    if ((Hash $tracker) -ine $trackerHash) { throw 'Launcher tracking changed during installation.' }
    [IO.File]::Replace($newTracker, $tracker, $trackerBackup)
    $trackerReplaced = $true
    foreach ($entry in $moves) { if ((Hash $entry.Backup) -ine $entry.Hash) { throw 'Backup integrity mismatch.' } }
    if ((Hash $trackerBackup) -ine $trackerHash -or (Hash $tracker) -ine $newTrackerHash) { throw 'Launcher tracking verification failed.' }
    foreach ($directory in @($mods, $managed)) {
        $installed = @(Mod-Files $directory)
        if ($installed.Count -ne 1 -or $installed[0].Name -cne $name -or $installed[0].Hash -ine $checksum) { throw 'Final installed mod verification failed.' }
    }
    $result = [pscustomobject]@{ state = 'installed'; modId = $ExpectedModId; version = $incoming.version; jar = $name; managed = $true; backup = (Split-Path -Leaf $backupRoot) }
    $result | ConvertTo-Json | Set-Content -LiteralPath (Safe-Child $backupRoot 'result.json') -Encoding UTF8
    $result | ConvertTo-Json -Compress
} catch {
    foreach ($lock in $locks) { $lock.Dispose() }
    $locks = @()
    $rollbackOk = $true
    try {
        if ($trackerReplaced) {
            if ((Hash $tracker) -ine $newTrackerHash) { throw 'Tracking changed externally; preserved for manual recovery.' }
            $restore = Safe-Child $backupRoot 'restore-tracker.json'
            Copy-Item -LiteralPath $trackerBackup -Destination $restore
            [IO.File]::Replace($restore, $tracker, (Safe-Child $backupRoot 'failed-tracker.json'))
        }
        foreach ($path in $created) {
            if (Test-Path -LiteralPath $path) {
                if ((Hash $path) -ine $checksum) { throw 'Installed target changed externally; preserved for manual recovery.' }
                $bucket = if ((Split-Path -Parent $path) -ieq $mods) { 'runtime.jar' } else { 'managed.jar' }
                Move-Item -LiteralPath $path -Destination (Safe-Child (Safe-Child $backupRoot 'failed') $bucket)
            }
        }
        foreach ($entry in $moves) {
            if (Test-Path -LiteralPath $entry.Original) { throw 'Rollback destination occupied; preserved for manual recovery.' }
            if ((Hash $entry.Backup) -ine $entry.Hash) { throw 'Rollback backup changed; preserved for manual recovery.' }
            Move-Item -LiteralPath $entry.Backup -Destination $entry.Original
        }
    } catch { $rollbackOk = $false }
    [pscustomobject]@{ state = 'blocked'; errorType = $_.Exception.GetType().Name; reason = $(if ($_.Exception -is [Management.Automation.RuntimeException]) { $_.Exception.Message } else { 'Installation could not be completed safely.' }); rollbackComplete = $rollbackOk } | ConvertTo-Json -Compress
    exit 2
} finally {
    foreach ($lock in $locks) { $lock.Dispose() }
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    if ($mutex) { $mutex.Dispose() }
}
