#requires -Version 7.4
param([Parameter(Mandatory)][string]$BackupDirectory, [Parameter(Mandatory)][string]$InstanceDirectory, [string]$ConfigPath = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'operations-common.ps1')
$settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory -ConfigPath $ConfigPath
if (Get-AiTestManagedProcess (Get-AiTestState $settings)) { throw 'Stop the destination instance before restoring.' }
$incompleteFile = Join-Path $settings.RunDirectory 'restore-incomplete.json'
if (Test-Path -LiteralPath $incompleteFile) { throw 'This destination has an incomplete restore. Use a new empty database and instance; the partial attempt has been preserved for inspection.' }
$backupRoot = [IO.Path]::GetFullPath($BackupDirectory)
$manifest = Get-Content -Raw -LiteralPath (Join-Path $backupRoot 'manifest.json') | ConvertFrom-Json -AsHashtable
if ($manifest.formatVersion -ne 'aitest.backup/v1' -or -not $manifest.files) { throw 'The backup is incomplete or uses an unsupported format.' }
if ((Test-Path -LiteralPath $settings.Storage) -and @(Get-ChildItem -LiteralPath $settings.Storage -Force).Count -ne 0) { throw 'Restore only accepts an empty destination storage directory.' }
$paths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$resolvedPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($file in $manifest.files) {
    if (-not $paths.Add([string]$file.path)) { throw 'The backup contains duplicate paths.' }
    if ($file.path -notin @('database.sql','configuration.json') -and -not $file.path.StartsWith('storage/')) { throw 'Unexpected file category in backup manifest.' }
    $source = Resolve-AiTestArchivePath -Root $backupRoot -RelativePath $file.path
    if (-not $resolvedPaths.Add($source)) { throw 'The backup contains multiple names for the same file.' }
    $entry = Get-Item -LiteralPath $source -Force
    if ($entry.PSIsContainer -or $entry.Length -ne $file.bytes -or (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $file.sha256) { throw 'Backup file checksum or size verification failed.' }
    $current = $entry
    while ($current -and $current.FullName -ne $backupRoot) {
        if ($current.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Backup links are not accepted.' }
        $current = if ($current -is [IO.FileInfo]) { $current.Directory } else { $current.Parent }
    }
}
foreach ($required in @('database.sql','configuration.json','storage/.master-key')) { if (-not $paths.Contains($required)) { throw "Required backup file is missing: $required" } }
$key = [IO.File]::ReadAllBytes((Join-Path $backupRoot 'storage/.master-key'))
try {
    if ($key.Length -ne 32 -or (Get-AiTestKeyHash $key) -ne $manifest.masterKeyHash) { throw 'Backup master key does not match its manifest.' }
    if ($env:AI_TEST_MASTER_KEY -and (Get-AiTestKeyHash ([Convert]::FromBase64String($env:AI_TEST_MASTER_KEY))) -ne $manifest.masterKeyHash) { throw 'AI_TEST_MASTER_KEY must be cleared or match the backup key before restoring.' }
} finally { [Array]::Clear($key) }
$count = Invoke-AiTestSql $settings 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();'
if ($count -ne '0') { throw 'Restore only accepts an existing empty database; no tables were changed.' }
$version = Invoke-AiTestSql $settings 'SELECT VERSION();'
if ($version -notmatch '^8\.4\.') { throw 'Restore requires MySQL 8.4.' }
$dump = Join-Path $backupRoot 'database.sql'
$reader = [IO.File]::OpenText($dump)
try {
    while ($null -ne ($line = $reader.ReadLine())) {
        if ($line -match '^\s*(USE\s|(?:CREATE|DROP|ALTER)\s+DATABASE\s)') { throw 'This dump changes database scope and is not a platform portable instance backup.' }
    }
} finally { $reader.Dispose() }
$marker = @{startedAt=[DateTime]::UtcNow.ToString('O'); backup=$backupRoot; targetDatabase=$settings.DatabaseName; storage=$settings.Storage; phase='COPY_FILES'}
[IO.File]::WriteAllText($incompleteFile, ($marker | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
try {
    $null = New-Item -ItemType Directory -Path $settings.Storage -Force
    foreach ($file in $manifest.files) {
        if (-not $file.path.StartsWith('storage/')) { continue }
        $source = Resolve-AiTestArchivePath -Root $backupRoot -RelativePath $file.path
        $target = Resolve-AiTestArchivePath -Root $settings.Storage -RelativePath $file.path.Substring(8)
        $null = New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force
        [IO.File]::Copy($source, $target, $false)
    }
    $marker.phase='IMPORT_DATABASE'
    [IO.File]::WriteAllText($incompleteFile, ($marker | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    $null = Invoke-AiTestMySqlTool -Settings $settings -Tool mysql -Arguments @('--binary-mode',$settings.DatabaseName) -InputFile $dump
    $migration = Invoke-AiTestSql $settings 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;'
    if ($migration -ne [string]$manifest.schemaVersion) { throw 'The restored database migration version does not match the backup.' }
    $referencePath = Join-Path $settings.Instance ('restored-configuration-' + [guid]::NewGuid().ToString('N') + '.json')
    [IO.File]::Copy((Join-Path $backupRoot 'configuration.json'), $referencePath, $false)
    Remove-Item -LiteralPath $incompleteFile -Force
} catch {
    throw "Restore did not complete. The partial destination is preserved and startup is blocked by $incompleteFile. Inspect the MySQL logs and use a new empty destination for the next attempt. $($_.Exception.Message)"
}
Write-Output "Restored schema and managed files into the empty destination: $($settings.Instance)"
Write-Output "Destination connection/port/path settings were retained. Original configuration reference: $referencePath"
