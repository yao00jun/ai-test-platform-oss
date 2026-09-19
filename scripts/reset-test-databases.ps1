#requires -Version 7.4
# Recreates the disposable integration-test schemas on the project-local MySQL so every
# `verify` / release build starts from an empty database. The suite shares one persistent
# schema (see backend/src/test/java/com/aitest/support/MySqlIntegrationTest.java); without
# this reset it accumulates hundreds of MB of rows, enabled morning-brief schedules and
# stuck jobs from earlier runs, which slows every later run and makes the AI-pipeline tests
# flake with "模型服务返回 HTTP 503" (docs/acceptance/incident-concurrent-build-2026-09-18.md).
#
# Failsafe runs the suite in $Forks JVMs (backend/pom.xml aitest.it.forks); fork N>1 uses the _N suffixed schemas.
# Skipped when AI_TEST_INTEGRATION_DB_URL points the tests at an external database.
param([int]$Forks = 3, [switch]$Quiet)
$ErrorActionPreference = 'Stop'
if ($env:AI_TEST_INTEGRATION_DB_URL) {
    if (-not $Quiet) { Write-Output 'AI_TEST_INTEGRATION_DB_URL is set; leaving the external test database untouched.' }
    exit 0
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $projectRoot '.runtime/mysql'
$connectionFile = Join-Path $runtime 'connection.json'
$adminFile = Join-Path $runtime 'admin.cnf'
if (-not (Test-Path -LiteralPath $connectionFile) -or -not (Test-Path -LiteralPath $adminFile)) {
    throw 'The project MySQL is not bootstrapped. Run scripts/bootstrap-mysql.ps1 (or scripts/dev.ps1) first.'
}
$connection = Get-Content -Raw -LiteralPath $connectionFile | ConvertFrom-Json
if ($connection.username -notmatch '^[A-Za-z0-9_]+$') { throw 'Unexpected local test account name.' }
$mysql = Join-Path ([string]$connection.home) 'bin/mysql.exe'
if (-not (Test-Path -LiteralPath $mysql)) { throw "MySQL client is missing: $mysql" }
if (Get-Process java -ErrorAction SilentlyContinue) {
    throw 'A Java process is running. Finish or stop the other build/test/dev instance before resetting the test database.'
}
if ($Forks -lt 1 -or $Forks -gt 16) { throw 'Forks must be between 1 and 16.' }
$statements = [Text.StringBuilder]::new()
for ($fork = 1; $fork -le $Forks; $fork++) {
    $suffix = if ($fork -eq 1) { '' } else { "_$fork" }
    foreach ($schema in @("ai_test_platform_test$suffix", "ai_test_business_test$suffix")) {
        $null = $statements.AppendLine("DROP DATABASE IF EXISTS $schema;")
        $null = $statements.AppendLine("CREATE DATABASE $schema CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
        $null = $statements.AppendLine("GRANT ALL ON $schema.* TO '$($connection.username)'@'localhost';")
    }
}
# Schemas left behind by a previous run with more forks, and by crashed ProcessRecoveryIT child processes.
$null = $statements.AppendLine("SELECT CONCAT('DROP DATABASE `', schema_name, '`;') FROM information_schema.schemata WHERE schema_name LIKE 'ai_test_acceptance_%' OR (schema_name REGEXP '^ai_test_(platform|business)_test_[0-9]+$' AND CAST(SUBSTRING_INDEX(schema_name, '_', -1) AS UNSIGNED) > $Forks);")
$sql = $statements.ToString()
# admin.cnf is passed as a relative file name: the Windows MySQL client cannot read defaults files from paths containing non-ASCII characters.
Push-Location $runtime
try {
    $output = $sql | & $mysql --defaults-file=admin.cnf --default-character-set=utf8mb4 --connect-timeout=10 --batch --skip-column-names 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Resetting the integration-test databases failed: $output" }
    $stale = @($output | Where-Object { $_ -like 'DROP DATABASE*' })
    if ($stale.Count -gt 0) {
        $null = ($stale -join "`n") | & $mysql --defaults-file=admin.cnf --connect-timeout=10 --batch 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Dropping stale test schemas failed.' }
    }
} finally { Pop-Location }
$summary = "Integration-test databases recreated on 127.0.0.1:$($connection.port) for $Forks fork(s)."
if ($stale.Count -gt 0) { $summary += " Dropped $($stale.Count) stale schema(s)." }
if (-not $Quiet) { Write-Output $summary }
