#requires -Version 7.4
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../operations-common.ps1')
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$connection = Get-Content -Raw -LiteralPath (Join-Path $projectRoot '.runtime/mysql/connection.json') | ConvertFrom-Json
$testRoot = Join-Path $projectRoot ('.runtime/mysql-client-contract-' + [guid]::NewGuid().ToString('N') + '/中文 实例')
$null = New-Item -ItemType Directory -Path $testRoot -Force
$config = @{
    mysqlHome=$connection.home; bind='127.0.0.1'; port=8188
    database=@{url="jdbc:mysql://127.0.0.1:$($connection.port)/ai_test_platform_test"; username=$connection.username; password=$connection.password}
}
[IO.File]::WriteAllText((Join-Path $testRoot 'config.json'), ($config | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
$settings = Read-AiTestConfiguration -InstanceDirectory $testRoot
$version = Invoke-AiTestSql -Settings $settings -Sql 'SELECT VERSION();'
if ($version -notmatch '^8\.4\.') { throw 'The native client did not read its defaults file from the Chinese instance path.' }
$text = Invoke-AiTestSql -Settings $settings -Sql "SELECT '中文行与空格 text';"
if ($text -cne '中文行与空格 text') { throw 'SQL input or output changed UTF-8 bytes.' }
$dumpFile = Join-Path $testRoot '导出 结构.sql'
$null = Invoke-AiTestMySqlTool -Settings $settings -Tool mysqldump -Arguments @('--no-data','--skip-lock-tables','--set-gtid-purged=OFF','--no-tablespaces',$settings.DatabaseName,'asset') -OutputFile $dumpFile
$dump = [IO.File]::ReadAllText($dumpFile, [Text.Encoding]::UTF8)
if ($dump -notmatch 'CREATE TABLE `asset`') { throw 'The dump was not written to the Chinese output path.' }
if (@(Get-ChildItem -LiteralPath $settings.RunDirectory -File | Where-Object Name -Like 'mysql-*').Count) { throw 'Temporary client credentials or output files were left behind.' }
Write-Output 'MySQL 8.4 defaults, SQL UTF-8 transport and dump output passed from a Chinese/space instance path.'
