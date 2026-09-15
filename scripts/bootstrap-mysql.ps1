param([int]$Port = 3307, [string]$MySqlHome, [string]$Version)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot '.runtime\mysql'
$toolsRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Path $runtimeRoot,$toolsRoot -Force | Out-Null
$connectionFile = Join-Path $runtimeRoot 'connection.json'
if (Test-Path -LiteralPath $connectionFile) {
    $existing = Get-Content -Raw -LiteralPath $connectionFile | ConvertFrom-Json
    if (Get-Process -Id $existing.pid -ErrorAction SilentlyContinue) {
        Write-Output "Project MySQL is already running on port $($existing.port)."
        exit 0
    }
    $MySqlHome = $existing.home
    $Port = $existing.port
}
if (-not $MySqlHome) {
    $versions = if ($Version) { @($Version) } else { @('8.4.10','8.4.9','8.4.8','8.4.7','8.4.6') }
    $downloadUrl = $null
    foreach ($candidate in $versions) {
        $candidateUrl = "https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-$candidate-winx64.zip"
        try {
            $null = Invoke-WebRequest -Uri $candidateUrl -Method Head -TimeoutSec 15
            $Version = $candidate
            $downloadUrl = $candidateUrl
            break
        } catch { if ($Version) { throw } }
    }
    if (-not $downloadUrl) { throw 'No downloadable MySQL 8.4 archive was found. Supply -MySqlHome for a native MySQL 8.4 installation.' }
    $archivePath = Join-Path $toolsRoot "mysql-$Version-winx64.zip"
    $MySqlHome = Join-Path $toolsRoot "mysql-$Version-winx64"
    if (-not (Test-Path -LiteralPath (Join-Path $MySqlHome 'bin\mysqld.exe'))) {
        Write-Output "Downloading official MySQL $Version Windows archive."
        if (-not (Test-Path -LiteralPath $archivePath)) { Invoke-WebRequest -Uri $downloadUrl -OutFile $archivePath -TimeoutSec 900 }
        Expand-Archive -LiteralPath $archivePath -DestinationPath $toolsRoot -Force
    }
}
$mysqld = Join-Path $MySqlHome 'bin\mysqld.exe'
$mysql = Join-Path $MySqlHome 'bin\mysql.exe'
$versionOutput = & $mysqld --version
if ($versionOutput -notmatch '8\.4\.') { throw 'This project requires MySQL 8.4 LTS.' }
$dataDirectory = Join-Path $runtimeRoot 'data'
$configPath = Join-Path $runtimeRoot 'my.ini'
$config = @"
[mysqld]
basedir=$($MySqlHome.Replace('\','/'))
datadir=$($dataDirectory.Replace('\','/'))
port=$Port
bind-address=127.0.0.1
mysqlx=OFF
skip-log-bin
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
default-time-zone=+00:00
max-connections=100
log-error=$($runtimeRoot.Replace('\','/'))/mysql.log
"@
[IO.File]::WriteAllText($configPath, $config, [Text.UTF8Encoding]::new($false))
$firstStart = -not (Test-Path -LiteralPath (Join-Path $dataDirectory 'mysql'))
if ($firstStart) {
    & $mysqld "--defaults-file=$configPath" --initialize-insecure
    if ($LASTEXITCODE -ne 0) { throw 'MySQL initialization failed. See .runtime/mysql/mysql.log.' }
}
$process = Start-Process -FilePath $mysqld -ArgumentList @("--defaults-file=`"$configPath`"") -WindowStyle Hidden -PassThru
$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    if ($process.HasExited) { throw 'MySQL exited during startup. See .runtime/mysql/mysql.log.' }
    try {
        $tcp = [Net.Sockets.TcpClient]::new()
        $tcp.Connect('127.0.0.1', $Port)
        $tcp.Dispose()
        $ready = $true
        break
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $ready) { throw 'MySQL did not become ready.' }
if ($firstStart) {
    $rootPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $appPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $sql = @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';
CREATE DATABASE ai_test_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE ai_test_platform_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE ai_test_business_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'aitest'@'localhost' IDENTIFIED BY '$appPassword';
GRANT ALL ON ai_test_platform.* TO 'aitest'@'localhost';
GRANT ALL ON ai_test_platform_test.* TO 'aitest'@'localhost';
GRANT ALL ON ai_test_business_test.* TO 'aitest'@'localhost';
"@
    $sql | & $mysql --host=127.0.0.1 "--port=$Port" --user=root --default-character-set=utf8mb4
    if ($LASTEXITCODE -ne 0) { throw 'MySQL project account setup failed.' }
    $adminConfig = "[client]`nuser=root`npassword=$rootPassword`nhost=127.0.0.1`nport=$Port`n"
    [IO.File]::WriteAllText((Join-Path $runtimeRoot 'admin.cnf'), $adminConfig, [Text.UTF8Encoding]::new($false))
    $existing = @{home=$MySqlHome;port=$Port;username='aitest';password=$appPassword;version=$versionOutput}
}
$settings = @{home=$MySqlHome;port=$Port;username=$existing.username;password=$existing.password;version=$versionOutput;pid=$process.Id}
[IO.File]::WriteAllText($connectionFile, ($settings | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Write-Output "Project MySQL 8.4 is ready on 127.0.0.1:$Port. Credentials are in ignored .runtime/mysql/connection.json."
