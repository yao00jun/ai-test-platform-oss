#requires -Version 7.4
param([string]$MavenSettings = '', [switch]$IncludeBrowser, [switch]$Full, [int]$Forks = 3)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'operations-common.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $projectRoot 'frontend'
foreach ($command in @('lint','test:unit','build')) {
    & pnpm --dir $frontend $command
    if ($LASTEXITCODE -ne 0) { throw "Frontend $command failed." }
}
# Day-to-day verify skips @Tag("slow") integration tests; -Full adds the nightly profile
# and runs the same complete suite the release build (scripts/build.ps1) uses.
# -Forks sets how many JVMs failsafe runs in parallel; use 1 when the MySQL data directory is on a mechanical disk.
& (Join-Path $PSScriptRoot 'reset-test-databases.ps1') -Forks $Forks
if ($LASTEXITCODE -ne 0) { throw 'Could not reset the integration-test databases.' }
$arguments = @('-B','-ntp','verify',"-Daitest.it.forks=$Forks")
if ($Full) { $arguments += '-Pnightly' }
if ($MavenSettings) { $arguments += @('-s', [IO.Path]::GetFullPath($MavenSettings)) }
& (Join-Path $PSScriptRoot 'maven.ps1') @arguments
if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed.' }
if ($IncludeBrowser) {
    $classpathFile = Join-Path $projectRoot '.runtime/runtime-classpath.txt'
    $arguments = @('-B','-ntp','dependency:build-classpath',"-Dmdep.outputFile=$classpathFile",'-DincludeScope=runtime')
    if ($MavenSettings) { $arguments += @('-s', [IO.Path]::GetFullPath($MavenSettings)) }
    & (Join-Path $PSScriptRoot 'maven.ps1') @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Browser test classpath generation failed.' }
    # The existing fixture discovers the newest immutable snapshot. Always provide
    # one from the classes just verified, never from a potentially older dev server.
    $snapshot = Join-Path $projectRoot ('.runtime/backend-classes-' + [guid]::NewGuid().ToString('N'))
    Copy-Item -LiteralPath (Join-Path $projectRoot 'backend/target/classes') -Destination $snapshot -Recurse
    $classpath = $snapshot + [IO.Path]::PathSeparator + (Get-Content -Raw -LiteralPath $classpathFile).Trim()
    $javaArguments = "-cp`n`"" + $classpath.Replace('\','/').Replace('"','\"') + "`"`ncom.aitest.AiTestApplication`n"
    [IO.File]::WriteAllText((Join-Path $snapshot 'java.args'), $javaArguments, [Text.UTF8Encoding]::new($false))
    & pnpm --dir $frontend test:e2e:ai
    if ($LASTEXITCODE -ne 0) { throw 'Real-backend browser verification failed.' }
    & pnpm --dir $frontend test:e2e:auth
    if ($LASTEXITCODE -ne 0) { throw 'Authenticated workspace browser verification failed.' }
}
Write-Output 'Requested verification gates passed. Company model quality requires its separate environment acceptance.'
