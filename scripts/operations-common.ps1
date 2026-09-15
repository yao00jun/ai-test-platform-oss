#requires -Version 7.4

function Resolve-AiTestPath([string]$Base, [string]$Path) {
    if ([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
    return [IO.Path]::GetFullPath((Join-Path $Base $Path))
}

function Resolve-AiTestArchivePath([string]$Root, [string]$RelativePath) {
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath) -or $RelativePath.Contains(':')) {
        throw 'Backup paths must be nonempty relative paths.'
    }
    $resolvedRoot = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $resolvedPath = [IO.Path]::GetFullPath((Join-Path $resolvedRoot $RelativePath))
    if (-not $resolvedPath.StartsWith($resolvedRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Backup path escapes its containing directory.'
    }
    return $resolvedPath
}

function Read-AiTestConfiguration([string]$InstanceDirectory, [string]$ConfigPath = '') {
    $instance = [IO.Path]::GetFullPath($InstanceDirectory)
    $configFile = if ($ConfigPath) { [IO.Path]::GetFullPath($ConfigPath) } else { Join-Path $instance 'config.json' }
    if (-not (Test-Path -LiteralPath $configFile -PathType Leaf)) { throw "Configuration is missing: $configFile. Copy deploy/config.example.json and configure the database." }
    $config = Get-Content -Raw -LiteralPath $configFile | ConvertFrom-Json -AsHashtable
    if ($config -isnot [Collections.IDictionary] -or $config.database -isnot [Collections.IDictionary]) { throw 'Configuration must contain a database object.' }
    $portNumber = 0
    if (-not [int]::TryParse([string]$config.port, [ref]$portNumber) -or $portNumber -lt 1 -or $portNumber -gt 65535) { throw 'Port must be an integer from 1 to 65535.' }
    $bindAddress = if ($config.bind) { [string]$config.bind } else { '127.0.0.1' }
    $parsedAddress = $null
    if (-not [Net.IPAddress]::TryParse($bindAddress, [ref]$parsedAddress)) { throw 'Bind must be an IP address, for example 127.0.0.1.' }
    if (-not [Net.IPAddress]::IsLoopback($parsedAddress) -and -not $parsedAddress.Equals([Net.IPAddress]::Any) -and -not $parsedAddress.Equals([Net.IPAddress]::IPv6Any)) {
        throw 'Use a loopback address or 0.0.0.0/:: so the local shutdown endpoint remains reachable.'
    }
    if ($config.Contains('security') -and $config.security -isnot [Collections.IDictionary]) { throw 'Security configuration must be an object.' }
    if (-not $config.Contains('security')) { $config.security = @{} }
    foreach ($flag in @('enabled','secureCookie')) {
        if (-not $config.security.Contains($flag)) { $config.security[$flag] = $false }
        if ($config.security[$flag] -isnot [bool]) { throw "Security setting $flag must be a JSON boolean." }
    }
    foreach ($credential in @('username','password')) {
        if (-not $config.security.Contains($credential)) { $config.security[$credential] = '' }
        if ($config.security[$credential] -isnot [string]) { throw "Security setting $credential must be a string." }
    }
    if (-not $config.security.Contains('sessionMinutes')) { $config.security.sessionMinutes = 30 }
    $sessionMinutes = 0
    if (-not [int]::TryParse([string]$config.security.sessionMinutes, [ref]$sessionMinutes) -or $sessionMinutes -lt 1 -or $sessionMinutes -gt 1440) {
        throw 'Security sessionMinutes must be an integer from 1 to 1440.'
    }
    $config.security.sessionMinutes = $sessionMinutes
    if ($config.security.enabled) {
        if ($config.security.username -notmatch '^[\p{L}\p{N}_.@-]{1,64}$') { throw 'Configure a username of 1 to 64 letters, digits or _.@- characters.' }
        if ([string]::IsNullOrWhiteSpace($config.security.password) -or $config.security.password.Length -lt 12 -or [Text.Encoding]::UTF8.GetByteCount($config.security.password) -gt 72) {
            throw 'Configure a password of at least 12 characters and at most 72 UTF-8 bytes.'
        }
    } elseif (-not [Net.IPAddress]::IsLoopback($parsedAddress)) {
        throw 'Enable platform authentication before binding outside loopback.'
    }
    $url = [string]$config.database.url
    if (-not $url.StartsWith('jdbc:mysql://') -or $url -match '(?i)[?&](password|user|username|socketFactory|autoDeserialize)=') { throw 'Use a MySQL JDBC URL with credentials in separate fields.' }
    try { $databaseUri = [uri]$url.Substring(5) } catch { throw 'Invalid MySQL JDBC URL.' }
    $databaseName = [uri]::UnescapeDataString($databaseUri.AbsolutePath.TrimStart('/'))
    if ($databaseUri.UserInfo -or $databaseName -notmatch '^[A-Za-z0-9_]{1,64}$' -or -not $databaseUri.Host) { throw 'The JDBC URL must name one database without embedded credentials.' }
    if (-not $config.database.username -or [string]$config.database.username -match '[\r\n\0]') { throw 'A valid database username is required.' }
    if ($null -eq $config.database.password) { throw 'Database password must be configured in the password field.' }
    if ($config.runtime -isnot [Collections.IDictionary]) { $config.runtime = @{} }
    foreach ($rule in @(@('heapMiB',1024,256,32768), @('concurrency',8,1,64), @('browserWorkers',2,1,8), @('businessConnections',32,1,256))) {
        if (-not $config.runtime.Contains($rule[0])) { $config.runtime[$rule[0]] = $rule[1] }
        $number = 0
        if (-not [int]::TryParse([string]$config.runtime[$rule[0]], [ref]$number) -or $number -lt $rule[2] -or $number -gt $rule[3]) { throw "Invalid runtime setting: $($rule[0])." }
        $config.runtime[$rule[0]] = $number
    }
    if ($config.paths -isnot [Collections.IDictionary]) { $config.paths = @{} }
    if ($config.model -isnot [Collections.IDictionary]) { $config.model = @{} }
    $storage = Resolve-AiTestPath $instance $(if ($config.paths.storage) { $config.paths.storage } else { 'data' })
    $browsers = Resolve-AiTestPath $instance $(if ($config.paths.browsers) { $config.paths.browsers } else { 'browsers' })
    return [pscustomobject]@{ Instance=$instance; ConfigPath=$configFile; Config=$config; Storage=$storage; Browsers=$browsers; Port=$portNumber; Bind=$bindAddress;
        DatabaseName=$databaseName; DatabaseHost=$databaseUri.Host; DatabasePort=$(if ($databaseUri.Port -gt 0) { $databaseUri.Port } else { 3306 });
        RunDirectory=(Join-Path $instance 'run'); LogDirectory=(Join-Path $instance 'logs') }
}

function Initialize-AiTestConfiguration([string]$PackageRoot, [string]$InstanceDirectory) {
    $configFile = Join-Path $InstanceDirectory 'config.json'
    if (Test-Path -LiteralPath $configFile) { return }
    $template = Join-Path $PackageRoot 'deploy/config.example.json'
    if (-not (Test-Path -LiteralPath $template)) { $template = Join-Path $PackageRoot 'config.example.json' }
    $config = Get-Content -Raw -LiteralPath $template | ConvertFrom-Json -AsHashtable
    $localDatabase = Join-Path $PackageRoot '.runtime/mysql/connection.json'
    if (Test-Path -LiteralPath $localDatabase) {
        $local = Get-Content -Raw -LiteralPath $localDatabase | ConvertFrom-Json -AsHashtable
        $config.database.url = "jdbc:mysql://127.0.0.1:$($local.port)/ai_test_platform?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
        $config.database.username = $local.username; $config.database.password = $local.password; $config.mysqlHome = $local.home
        $config.paths.storage = Join-Path $PackageRoot 'data'
        $config.paths.browsers = Join-Path $PackageRoot '.tools/playwright-1.62.0'
    }
    $null = New-Item -ItemType Directory -Path $InstanceDirectory -Force
    [IO.File]::WriteAllText($configFile, ($config | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
    Write-Output "Created instance configuration: $configFile"
}

function Find-AiTestJava([string]$ConfiguredJavaHome = '') {
    $candidates = @($ConfiguredJavaHome, $env:AI_TEST_JAVA_HOME, $env:JAVA_HOME)
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $candidates += Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
    if (Test-Path -LiteralPath 'C:/Program Files/Java') {
        $candidates += Get-ChildItem -LiteralPath 'C:/Program Files/Java' -Directory | Where-Object Name -Like 'jdk-21*' | Sort-Object Name -Descending | Select-Object -ExpandProperty FullName
    }
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $release = Join-Path $candidate 'release'
        $java = Join-Path $candidate 'bin/java.exe'
        if ((Test-Path -LiteralPath $java) -and (Test-Path -LiteralPath $release) -and (Get-Content -Raw -LiteralPath $release) -match 'JAVA_VERSION="21\.') { return $java }
    }
    throw 'Java 21 is required. Set javaHome in config.json or AI_TEST_JAVA_HOME.'
}

function Get-AiTestEnvironment($Settings, [string]$ShutdownToken = '') {
    $child = @{}
    Get-ChildItem Env: | Where-Object { $_.Name -match '^(AI_TEST|SPRING|SERVER|AITEST|MANAGEMENT|LOGGING)_' } | ForEach-Object { $child[$_.Name] = $null }
    foreach ($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')) { $child[$name] = $null }
    $config = $Settings.Config
    $child.AI_TEST_BIND=$Settings.Bind; $child.AI_TEST_PORT=[string]$Settings.Port
    $child.AI_TEST_DB_URL=[string]$config.database.url; $child.AI_TEST_DB_USER=[string]$config.database.username; $child.AI_TEST_DB_PASSWORD=[string]$config.database.password
    $child.AI_TEST_STORAGE=$Settings.Storage; $child.AI_TEST_BROWSER_PATH=$Settings.Browsers
    $child.PLAYWRIGHT_BROWSERS_PATH=$Settings.Browsers
    $child.AI_TEST_CONCURRENCY=[string]$config.runtime.concurrency; $child.AI_TEST_BROWSER_WORKERS=[string]$config.runtime.browserWorkers
    $child.AI_TEST_MODEL_BASE_URL=[string]$config.model.baseUrl; $child.AI_TEST_MODEL_API_KEY=[string]$config.model.apiKey; $child.AI_TEST_MODEL_NAME=[string]$config.model.modelName
    $child.AI_TEST_AUTH_ENABLED=$config.security.enabled.ToString().ToLowerInvariant()
    $child.AI_TEST_AUTH_USERNAME=$config.security.username; $child.AI_TEST_AUTH_PASSWORD=$config.security.password
    $child.AI_TEST_SESSION_TIMEOUT=[string]$config.security.sessionMinutes + 'm'
    $child.AI_TEST_SECURE_COOKIE=$config.security.secureCookie.ToString().ToLowerInvariant()
    $child.AI_TEST_FILE_ROOTS=(@($config.paths.localFileRoots) | Where-Object { $_ } | ForEach-Object { Resolve-AiTestPath $Settings.Instance $_ }) -join ';'
    $child.AI_TEST_SHUTDOWN_TOKEN=$ShutdownToken
    if ($env:AI_TEST_MASTER_KEY) { $child.AI_TEST_MASTER_KEY=$env:AI_TEST_MASTER_KEY }
    return $child
}

function New-AiTestApiHeaders($Settings, [string]$BaseUrl) {
    $address = $null
    $parsed = $null
    if (-not [uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$address) -or $address.Scheme -notin @('http','https') -or
        -not [Net.IPAddress]::TryParse($address.DnsSafeHost, [ref]$parsed) -or -not [Net.IPAddress]::IsLoopback($parsed) -or
        $address.Port -ne $Settings.Port -or $address.UserInfo -or $address.Query -or $address.Fragment -or $address.AbsolutePath -ne '/') {
        throw 'Operations API authentication only accepts this instance''s literal loopback address and port.'
    }
    if (-not $Settings.Config.security.enabled) { return @{} }
    $base = $address.GetLeftPart([UriPartial]::Authority)
    $bootstrap = Invoke-WebRequest -Uri "$base/api/auth/session" -TimeoutSec 15 -MaximumRedirection 0 -NoProxy -SkipHttpErrorCheck
    if ($bootstrap.StatusCode -ne 200) { throw 'Could not read the platform session for the operations client.' }
    $state = $bootstrap.Content | ConvertFrom-Json
    $cookie = @($bootstrap.Headers['Set-Cookie'] | Where-Object { $_.StartsWith('AI_TEST_SESSION=') } | ForEach-Object { $_.Split(';')[0] }) | Select-Object -Last 1
    if (-not $state.enabled -or -not $state.csrfToken -or -not $cookie) { throw 'Platform authentication settings do not match the running instance.' }
    # The privileged operations client is restricted to the verified local listener.
    # An explicit cookie header also works when browser cookies require external HTTPS.
    $headers = @{Cookie=$cookie; 'X-CSRF-TOKEN'=$state.csrfToken}
    $login = Invoke-WebRequest -Uri "$base/api/auth/login" -Method Post -Headers $headers -ContentType 'application/x-www-form-urlencoded' `
        -Body @{username=$Settings.Config.security.username; password=$Settings.Config.security.password} -TimeoutSec 15 -MaximumRedirection 0 -NoProxy -SkipHttpErrorCheck
    if ($login.StatusCode -ne 200) { throw "The configured platform account could not sign in (HTTP $($login.StatusCode))." }
    $renewed = @($login.Headers['Set-Cookie'] | Where-Object { $_.StartsWith('AI_TEST_SESSION=') } | ForEach-Object { $_.Split(';')[0] }) | Select-Object -Last 1
    if ($renewed) { $headers.Cookie = $renewed }
    $current = Invoke-RestMethod -Uri "$base/api/auth/session" -Headers $headers -TimeoutSec 15 -MaximumRedirection 0 -NoProxy
    if (-not $current.authenticated -or -not $current.csrfToken) { throw 'The operations client did not receive an authenticated session.' }
    $headers['X-CSRF-TOKEN'] = $current.csrfToken
    return $headers
}

function Get-AiTestManagedProcess($State) {
    if (-not $State -or -not $State.pid -or -not $State.startedAt) { return $null }
    try {
        $managed = Get-Process -Id ([int]$State.pid) -ErrorAction Stop
        # PowerShell 7.5+ parses ISO JSON timestamps into DateTime; 7.4 returns text.
        # Compare instants at full precision instead of a culture-dependent string.
        $recordedStart = if ($State.startedAt -is [datetime]) { $State.startedAt.ToUniversalTime() }
            elseif ($State.startedAt -is [datetimeoffset]) { $State.startedAt.UtcDateTime }
            else { [DateTimeOffset]::Parse([string]$State.startedAt, [Globalization.CultureInfo]::InvariantCulture).UtcDateTime }
        if ($managed.ProcessName -notin @('java','javaw') -or $managed.StartTime.ToUniversalTime().Ticks -ne $recordedStart.Ticks) { return $null }
        return $managed
    } catch { return $null }
}

function Get-AiTestState($Settings) {
    $stateFile = Join-Path $Settings.RunDirectory 'state.json'
    if (-not (Test-Path -LiteralPath $stateFile)) { return $null }
    return Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json -AsHashtable
}

function Get-AiTestMasterKey($Settings) {
    if ($env:AI_TEST_MASTER_KEY) { $bytes = [Convert]::FromBase64String($env:AI_TEST_MASTER_KEY) }
    else { $bytes = [IO.File]::ReadAllBytes((Join-Path $Settings.Storage '.master-key')) }
    if ($bytes.Length -ne 32) { throw 'The effective master key must contain exactly 32 bytes.' }
    return ,$bytes
}

function Get-AiTestKeyHash([byte[]]$Bytes) { return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)) }

function Invoke-AiTestMySqlTool($Settings, [ValidateSet('mysql','mysqldump')][string]$Tool, [string[]]$Arguments, [string]$InputFile = '', [string]$OutputFile = '') {
    $mysqlRoot = [string]$Settings.Config.mysqlHome
    $executable = if ($mysqlRoot) { Join-Path $mysqlRoot "bin/$Tool.exe" } else { (Get-Command "$Tool.exe" -ErrorAction Stop).Source }
    if (-not (Test-Path -LiteralPath $executable)) { throw 'Set mysqlHome in config.json to a MySQL 8.4 client installation.' }
    $null = New-Item -ItemType Directory -Path $Settings.RunDirectory,$Settings.LogDirectory -Force
    $identity = [guid]::NewGuid().ToString('N')
    $clientFile = Join-Path $Settings.RunDirectory "mysql-$identity.cnf"
    $stdoutFile = Join-Path $Settings.RunDirectory "mysql-$identity.out"
    $errorFile = Join-Path $Settings.LogDirectory "mysql-$identity.log"
    function Quote-ClientValue([string]$Value) { return '"' + $Value.Replace('\','\\').Replace('"','\"').Replace("`r",'\r').Replace("`n",'\n') + '"' }
    $lines = @('[client]', ('host=' + (Quote-ClientValue $Settings.DatabaseHost)), ('port=' + $Settings.DatabasePort),
        ('user=' + (Quote-ClientValue ([string]$Settings.Config.database.username))), ('password=' + (Quote-ClientValue ([string]$Settings.Config.database.password))), 'default-character-set=utf8mb4')
    if ($Settings.Config.mysqlSslMode) { $lines += 'ssl-mode=' + [string]$Settings.Config.mysqlSslMode }
    if ($Settings.Config.mysqlSslCa) { $lines += 'ssl-ca=' + (Quote-ClientValue (Resolve-AiTestPath $Settings.Instance $Settings.Config.mysqlSslCa)) }
    [IO.File]::WriteAllText($clientFile, ($lines -join "`n"), [Text.UTF8Encoding]::new($false))
    $mysqlProcess = $null; $started = $false; $stdout = $null; $stderr = $null
    try {
        if ($OutputFile -and (Test-Path -LiteralPath $OutputFile)) { throw 'The MySQL output destination already exists; it was not overwritten.' }
        $info = [Diagnostics.ProcessStartInfo]::new($executable)
        $info.UseShellExecute=$false; $info.CreateNoWindow=$true
        $info.WorkingDirectory=$Settings.RunDirectory
        $info.RedirectStandardInput=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
        # MySQL's Windows defaults-file reader cannot open a Unicode absolute path.
        # The Unicode working directory is set through the OS; native file arguments
        # use an ASCII basename. SQL and dump bytes travel through redirected streams.
        $info.ArgumentList.Add('--defaults-file=' + [IO.Path]::GetFileName($clientFile))
        $info.ArgumentList.Add('--no-login-paths')
        foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
        $null = $info.Environment.Remove('MYSQL_PWD')
        $stdout = [IO.File]::Open($stdoutFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        $stderr = [IO.File]::Open($errorFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        $mysqlProcess = [Diagnostics.Process]::new(); $mysqlProcess.StartInfo=$info
        $started = $mysqlProcess.Start()
        $copyOutput = $mysqlProcess.StandardOutput.BaseStream.CopyToAsync($stdout)
        $copyError = $mysqlProcess.StandardError.BaseStream.CopyToAsync($stderr)
        try {
            if ($InputFile) {
                $sqlInputStream = [IO.File]::OpenRead($InputFile)
                try { $sqlInputStream.CopyTo($mysqlProcess.StandardInput.BaseStream) } finally { $sqlInputStream.Dispose() }
            }
        } finally { $mysqlProcess.StandardInput.Close() }
        $mysqlProcess.WaitForExit()
        [Threading.Tasks.Task]::WhenAll([Threading.Tasks.Task[]]@($copyOutput,$copyError)).GetAwaiter().GetResult()
        $stdout.Dispose(); $stdout=$null; $stderr.Dispose(); $stderr=$null
        if ($mysqlProcess.ExitCode -ne 0) { throw "MySQL command failed. Inspect $errorFile" }
        if ($OutputFile) { [IO.File]::Move($stdoutFile, [IO.Path]::GetFullPath($OutputFile), $false); return '' }
        return [IO.File]::ReadAllText($stdoutFile, [Text.Encoding]::UTF8).Trim()
    } finally {
        if ($started -and -not $mysqlProcess.HasExited) { $mysqlProcess.Kill($true); $mysqlProcess.WaitForExit() }
        if ($stdout) { $stdout.Dispose() }
        if ($stderr) { $stderr.Dispose() }
        if ($mysqlProcess) { $mysqlProcess.Dispose() }
        if (Test-Path -LiteralPath $clientFile) { Remove-Item -LiteralPath $clientFile -Force }
        if (Test-Path -LiteralPath $stdoutFile) { Remove-Item -LiteralPath $stdoutFile -Force }
    }
}

function Invoke-AiTestSql($Settings, [string]$Sql) {
    $null = New-Item -ItemType Directory -Path $Settings.RunDirectory -Force
    $inputFile = Join-Path $Settings.RunDirectory ('query-' + [guid]::NewGuid().ToString('N') + '.sql')
    [IO.File]::WriteAllText($inputFile, $Sql, [Text.UTF8Encoding]::new($false))
    try { return Invoke-AiTestMySqlTool -Settings $Settings -Tool mysql -Arguments @('--batch','--skip-column-names','--raw','--binary-mode',$Settings.DatabaseName) -InputFile $inputFile }
    finally { Remove-Item -LiteralPath $inputFile -Force }
}
