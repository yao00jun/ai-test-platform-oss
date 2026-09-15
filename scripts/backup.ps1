#requires -Version 7.4
param([string]$InstanceDirectory = '', [string]$ConfigPath = '', [string]$DestinationDirectory = '', [switch]$LeaveStopped)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'operations-common.ps1')
if (-not $InstanceDirectory) { $InstanceDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'instance' }
$settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory -ConfigPath $ConfigPath
$state = Get-AiTestState $settings
$wasRunning = $null -ne (Get-AiTestManagedProcess $state)
$key = Get-AiTestMasterKey $settings
if ($state -and $state.masterKeyHash -and $state.masterKeyHash -ne (Get-AiTestKeyHash $key)) { throw 'The current master key does not match the last running instance; no backup was made.' }
if (-not $DestinationDirectory) { $DestinationDirectory = Join-Path $settings.Instance 'backups' }
$destination = [IO.Path]::GetFullPath($DestinationDirectory)
$storagePrefix = $settings.Storage.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
if ($destination -eq $settings.Storage -or $destination.StartsWith($storagePrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'The backup destination must be outside the live storage directory.' }
$backupRoot = Join-Path $destination ('backup-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
if ($wasRunning) { & (Join-Path $PSScriptRoot 'stop.ps1') -InstanceDirectory $settings.Instance -ConfigPath $settings.ConfigPath }
try {
    if (Get-AiTestManagedProcess (Get-AiTestState $settings)) { throw 'Application must be stopped for a consistent database/file backup.' }
    $version = Invoke-AiTestSql $settings 'SELECT VERSION();'
    if ($version -notmatch '^8\.4\.') { throw 'This backup workflow requires MySQL 8.4.' }
    $null = New-Item -ItemType Directory -Path (Join-Path $backupRoot 'storage') -Force
    $dumpFile = Join-Path $backupRoot 'database.sql'
    $null = Invoke-AiTestMySqlTool -Settings $settings -Tool mysqldump -Arguments @('--single-transaction','--skip-lock-tables','--skip-add-locks','--routines','--events','--triggers','--hex-blob','--set-gtid-purged=OFF','--no-tablespaces',$settings.DatabaseName) -OutputFile $dumpFile
    foreach ($entry in Get-ChildItem -LiteralPath $settings.Storage -Force -Recurse) {
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Managed storage contains a link; resolve it before creating a portable backup.' }
        $relative = [IO.Path]::GetRelativePath($settings.Storage, $entry.FullName)
        if ($relative -match '^\.(workers|render)([/\\]|$)') { continue }
        $target = Resolve-AiTestArchivePath -Root (Join-Path $backupRoot 'storage') -RelativePath $relative
        if ($entry.PSIsContainer) { $null = New-Item -ItemType Directory -Path $target -Force }
        else { $null = New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force; [IO.File]::Copy($entry.FullName, $target, $false) }
    }
    [IO.File]::WriteAllBytes((Join-Path $backupRoot 'storage/.master-key'), $key)
    [IO.File]::Copy($settings.ConfigPath, (Join-Path $backupRoot 'configuration.json'), $false)
    $files = @(Get-ChildItem -LiteralPath $backupRoot -Force -Recurse -File | ForEach-Object {
        @{path=[IO.Path]::GetRelativePath($backupRoot, $_.FullName).Replace('\','/'); bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    })
    $migration = Invoke-AiTestSql $settings 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;'
    $manifest = @{formatVersion='aitest.backup/v1'; createdAt=[DateTime]::UtcNow.ToString('O'); mysqlVersion=$version; schemaVersion=$migration;
        sourceDatabase=$settings.DatabaseName; masterKeyHash=(Get-AiTestKeyHash $key); files=$files}
    [IO.File]::WriteAllText((Join-Path $backupRoot 'manifest.json'), ($manifest | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
    Write-Output "Consistent backup created: $backupRoot"
} finally {
    [Array]::Clear($key)
    if ($wasRunning -and -not $LeaveStopped) {
        & (Join-Path $PSScriptRoot 'start.ps1') -InstanceDirectory $settings.Instance -ConfigPath $settings.ConfigPath -JarPath $state.jar
    }
}
