param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArguments)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jdkCandidates = @($env:AI_TEST_JAVA_HOME, 'C:\Program Files\Java\jdk-21.0.11')
if ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
$projectJavaHome = $null
foreach ($candidate in $jdkCandidates) {
    if (-not $candidate) { continue }
    $releaseFile = Join-Path $candidate 'release'
    if ((Test-Path -LiteralPath $releaseFile) -and ((Get-Content -Raw -LiteralPath $releaseFile) -match 'JAVA_VERSION="21\.')) {
        $projectJavaHome = $candidate
        break
    }
}
if (-not $projectJavaHome) { throw 'Java 21 is required. Set AI_TEST_JAVA_HOME to an installed JDK 21 directory.' }
$env:JAVA_HOME = $projectJavaHome
$env:PATH = (Join-Path $projectJavaHome 'bin') + [IO.Path]::PathSeparator + $env:PATH
$env:MAVEN_OPTS = '-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Duser.language=en -Duser.country=US'
if (-not $MavenArguments -or $MavenArguments.Count -eq 0) { $MavenArguments = @('verify') }
$wrapper = Join-Path $projectRoot 'backend\mvnw.cmd'
if (-not (Test-Path -LiteralPath $wrapper)) { throw 'The checked-in Maven Wrapper is missing. Restore backend/mvnw.cmd and backend/.mvn from the corresponding source archive.' }
& $wrapper -f (Join-Path $projectRoot 'backend\pom.xml') @MavenArguments
exit $LASTEXITCODE
