#requires -Version 7.4
param([string]$InstanceDirectory = '', [string]$ConfigPath = '', [switch]$TestModel)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'operations-common.ps1')
if (-not $InstanceDirectory) { $InstanceDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'instance' }
$settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory -ConfigPath $ConfigPath
$java = Find-AiTestJava ([string]$settings.Config.javaHome)
Write-Output "Java 21: $java"
$version = Invoke-AiTestSql -Settings $settings -Sql 'SELECT VERSION();'
if ($version -notmatch '^8\.4\.') { throw 'MySQL 8.4 LTS is required.' }
Write-Output "MySQL: $version"
$null = New-Item -ItemType Directory -Path $settings.Storage -Force
$probe = Join-Path $settings.Storage ('.write-check-' + [guid]::NewGuid().ToString('N'))
try { [IO.File]::WriteAllText($probe, 'storage-check') } finally { if (Test-Path -LiteralPath $probe) { Remove-Item -LiteralPath $probe } }
Write-Output "Storage is writable: $($settings.Storage)"
$browserExecutables = if (Test-Path -LiteralPath $settings.Browsers) { @(Get-ChildItem -LiteralPath $settings.Browsers -Recurse -File -Filter 'chrome*.exe') } else { @() }
if ($browserExecutables.Count -eq 0) { Write-Warning 'Chromium is not installed at the configured path. Run scripts/install-browsers.ps1 before UI/PDF execution.' }
else { Write-Output "Chromium installation found: $($settings.Browsers)" }
$modelConfigured = $settings.Config.model.baseUrl -and $settings.Config.model.apiKey -and $settings.Config.model.modelName
if (-not $modelConfigured) { Write-Output 'Model configuration is incomplete in config.json; manual workflows remain available. A model saved in the application UI can still be used.' }
if ($TestModel) {
    $state = Get-AiTestState $settings
    if (-not (Get-AiTestManagedProcess $state)) { throw 'Start this instance before testing its effective model settings.' }
    $headers = New-AiTestApiHeaders -Settings $settings -BaseUrl $state.baseUrl
    try {
        $result = Invoke-RestMethod -Method Post -Uri "$($state.baseUrl)/api/settings/model/test" -Headers $headers -TimeoutSec 120 -MaximumRedirection 0 -NoProxy
        Write-Output ($result | ConvertTo-Json -Depth 5)
    } finally {
        if ($settings.Config.security.enabled) {
            try { $null = Invoke-RestMethod -Method Post -Uri "$($state.baseUrl)/api/auth/logout" -Headers $headers -TimeoutSec 15 -MaximumRedirection 0 -NoProxy }
            catch { Write-Warning 'Could not close the temporary operations session; it will expire normally.' }
        }
    }
}
