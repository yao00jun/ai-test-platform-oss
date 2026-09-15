#requires -Version 7.4
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../operations-common.ps1')
$testRoot = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) ('.runtime/ops-contract-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testRoot
function Assert-Contract([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Rejected([scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-Contract $rejected 'Unsafe or invalid configuration was accepted.'
}
$fixture = @{
    javaHome=''; bind='127.0.0.1'; port=8188
    database=@{url='jdbc:mysql://127.0.0.1:3307/acceptance_empty'; username='aitest'; password='fixture-db-secret'}
    model=@{baseUrl=''; apiKey='fixture-model-secret'; modelName=''}
    paths=@{storage='data'; browsers='browsers'; localFileRoots=@()}
    runtime=@{heapMiB=1024; concurrency=8; browserWorkers=2; businessConnections=32}
}
$configPath = Join-Path $testRoot 'config.json'
[IO.File]::WriteAllText($configPath, ($fixture | ConvertTo-Json -Depth 10))
$settings = Read-AiTestConfiguration -InstanceDirectory $testRoot
Assert-Contract ($settings.Storage -eq (Join-Path $testRoot 'data')) 'Relative storage path did not resolve inside the instance.'
Assert-Contract ($settings.DatabaseName -eq 'acceptance_empty') 'The target schema was not parsed correctly.'
$environment = Get-AiTestEnvironment -Settings $settings -ShutdownToken 'fixture-shutdown-token'
Assert-Contract ($environment.AI_TEST_DB_PASSWORD -eq 'fixture-db-secret') 'Database credentials were not passed in the environment.'
Assert-Contract ($environment.AI_TEST_MODEL_API_KEY -eq 'fixture-model-secret') 'Model credentials were not passed in the environment.'
$previousSpringJson = $env:SPRING_APPLICATION_JSON
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
$previousServerPort = $env:SERVER_PORT
try {
    $env:SPRING_APPLICATION_JSON = '{"server":{"port":12345}}'
    $env:JAVA_TOOL_OPTIONS = '-Dfixture.option=inherited'
    $env:SERVER_PORT = '12345'
    $environment = Get-AiTestEnvironment -Settings $settings
    Assert-Contract ($environment.ContainsKey('SPRING_APPLICATION_JSON') -and $null -eq $environment.SPRING_APPLICATION_JSON) 'Inherited Spring settings can override the instance configuration.'
    Assert-Contract ($environment.ContainsKey('JAVA_TOOL_OPTIONS') -and $null -eq $environment.JAVA_TOOL_OPTIONS) 'Inherited JVM options can override the instance configuration.'
    Assert-Contract ($environment.ContainsKey('SERVER_PORT') -and $null -eq $environment.SERVER_PORT) 'Inherited server settings can override the instance port.'
} finally {
    $env:SPRING_APPLICATION_JSON = $previousSpringJson
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    $env:SERVER_PORT = $previousServerPort
}
Assert-Rejected { Resolve-AiTestArchivePath -Root $testRoot -RelativePath '../outside' }
Assert-Rejected { Resolve-AiTestArchivePath -Root $testRoot -RelativePath 'C:/outside' }
Assert-Rejected { Resolve-AiTestArchivePath -Root $testRoot -RelativePath 'files/../../outside' }
$fixture.database.url = 'jdbc:mysql://127.0.0.1:3307/acceptance_empty?password=not-allowed'
[IO.File]::WriteAllText($configPath, ($fixture | ConvertTo-Json -Depth 10))
Assert-Rejected { Read-AiTestConfiguration -InstanceDirectory $testRoot }
$fixture.database.url = 'jdbc:mysql://127.0.0.1:3307/acceptance_empty'
$fixture.bind = '192.0.2.10'
[IO.File]::WriteAllText($configPath, ($fixture | ConvertTo-Json -Depth 10))
Assert-Rejected { Read-AiTestConfiguration -InstanceDirectory $testRoot }
$fixture.bind = '127.0.0.1'
$fixture.port = 99999
[IO.File]::WriteAllText($configPath, ($fixture | ConvertTo-Json -Depth 10))
Assert-Rejected { Read-AiTestConfiguration -InstanceDirectory $testRoot }
$state = @{pid=$PID; startedAt='2000-01-01T00:00:00.0000000Z'}
Assert-Contract ($null -eq (Get-AiTestManagedProcess -State $state)) 'Stale process identity must never match a current process.'
$probeFile = Join-Path $testRoot 'ProcessIdentityProbe.java'
[IO.File]::WriteAllText($probeFile, 'class ProcessIdentityProbe { public static void main(String[] args) throws Exception { System.out.println("READY"); Thread.sleep(60000); } }', [Text.UTF8Encoding]::new($false))
$probeInfo = [Diagnostics.ProcessStartInfo]::new((Find-AiTestJava))
$probeInfo.UseShellExecute=$false; $probeInfo.CreateNoWindow=$true; $probeInfo.RedirectStandardOutput=$true
$probeInfo.ArgumentList.Add($probeFile)
$probeProcess = [Diagnostics.Process]::Start($probeInfo)
try {
    $ready = $probeProcess.StandardOutput.ReadLineAsync()
    Assert-Contract ($ready.Wait(10000) -and $ready.Result -eq 'READY') 'The real Java identity probe did not start.'
    $state = @{pid=$probeProcess.Id; startedAt=$probeProcess.StartTime.ToUniversalTime().ToString('O')}
    Assert-Contract ($null -ne (Get-AiTestManagedProcess $state)) 'A live Java process was not recognized before serialization.'
    $roundTrip = $state | ConvertTo-Json | ConvertFrom-Json -AsHashtable
    Assert-Contract ($null -ne (Get-AiTestManagedProcess $roundTrip)) 'A live Java process was not recognized after reading persisted JSON state.'
    $roundTrip.startedAt = ([datetime]$state.startedAt).AddTicks(1)
    Assert-Contract ($null -eq (Get-AiTestManagedProcess $roundTrip)) 'A different start instant must not match the same PID.'
} finally {
    if (-not $probeProcess.HasExited) { $probeProcess.Kill($true); $probeProcess.WaitForExit() }
    $probeProcess.Dispose()
}
Write-Output 'Operations configuration, archive containment, secret transport and process identity contracts passed.'
