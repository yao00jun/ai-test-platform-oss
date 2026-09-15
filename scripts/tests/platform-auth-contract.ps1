#requires -Version 7.4
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../operations-common.ps1')
$testRoot = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) ('.runtime/auth-config-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testRoot
$configPath = Join-Path $testRoot 'config.json'
function Assert-Auth([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Read-Fixture($Fixture) {
    [IO.File]::WriteAllText($configPath, ($Fixture | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
    return Read-AiTestConfiguration -InstanceDirectory $testRoot
}
function Assert-Rejected($Fixture) {
    $rejected = $false
    try { Read-Fixture $Fixture | Out-Null } catch { $rejected = $true }
    Assert-Auth $rejected 'Invalid authentication configuration was accepted.'
}
$fixture = @{
    bind='127.0.0.1'; port=8188
    database=@{url='jdbc:mysql://127.0.0.1:3307/auth_fixture'; username='aitest'; password='db-fixture'}
}
$settings = Read-Fixture $fixture
$environment = Get-AiTestEnvironment $settings
Assert-Auth ($environment.AI_TEST_AUTH_ENABLED -eq 'false') 'Legacy local mode must explicitly disable inherited authentication settings.'
foreach ($untrusted in @('http://192.0.2.10:8188','http://localhost:8188','http://127.0.0.1:8189','http://127.0.0.1:8188/other','http://127.0.0.1:8188/?secret=x')) {
    $rejected = $false
    try { New-AiTestApiHeaders -Settings $settings -BaseUrl $untrusted | Out-Null } catch { $rejected = $true }
    Assert-Auth $rejected 'Operations authentication accepted an unverified API destination.'
}
$fixture.bind = '0.0.0.0'
Assert-Rejected $fixture
$fixture.security = @{enabled=$true; username='workspace-owner'; password='config-password-2026!'; sessionMinutes=25; secureCookie=$true}
$environment = Get-AiTestEnvironment (Read-Fixture $fixture)
Assert-Auth ($environment.AI_TEST_AUTH_ENABLED -eq 'true') 'Authentication activation was not propagated to the child.'
Assert-Auth ($environment.AI_TEST_AUTH_USERNAME -eq 'workspace-owner') 'Configured account was not propagated.'
Assert-Auth ($environment.AI_TEST_AUTH_PASSWORD -eq 'config-password-2026!') 'Configured password was not propagated privately through the environment.'
Assert-Auth ($environment.AI_TEST_SESSION_TIMEOUT -eq '25m') 'Configured expiry was not propagated.'
Assert-Auth ($environment.AI_TEST_SECURE_COOKIE -eq 'true') 'HTTPS-only cookie mode was not propagated.'
$fixture.security.enabled = 'false'
Assert-Rejected $fixture
$fixture.security.enabled = $true
$fixture.security.password = 'short'
Assert-Rejected $fixture
$fixture.security.password = '密' * 25
Assert-Rejected $fixture
$fixture.security.password = 'config-password-2026!'
$fixture.security.sessionMinutes = 0
Assert-Rejected $fixture
$fixture.security.sessionMinutes = 25
$fixture.security.secureCookie = 'true'
Assert-Rejected $fixture
Write-Output 'Authentication configuration, legacy loopback compatibility, expiry and secret transport contracts passed.'
