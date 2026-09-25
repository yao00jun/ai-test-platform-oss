#requires -Version 7.4
<#
AI-Test-Platform 唯一的 Windows 脚本。日常不用直接运行它：双击仓库根目录的 启动.cmd / 停止.cmd / 查看状态.cmd / 查看日志.cmd / 备份.cmd 即可。

用法：pwsh -File scripts\aitest.ps1 <命令> [选项]

  日常使用（源码目录和发行包都可用）
    up                 一键启动：Java → MySQL → 配置 → 程序包 → 浏览器内核 → 后端 → 打开网页
    down               停止后端（源码目录下同时停止项目 MySQL；-KeepMysql 保留）
    restart            重启后端
    status             查看 MySQL / 后端 / 模型 / 前端热更新的状态和日志位置
    logs               实时看后端日志（-Errors 看错误日志，-Vite 看前端日志，-Tail 100）
    check              启动前体检：Java、MySQL、目录、浏览器内核、模型配置（-TestModel 真实调一次模型）
    backup             一致备份到 instance\backups（-DestinationDirectory 指定目录，-LeaveStopped 备份后不重启）
    restore            把备份恢复到一个新的空实例：restore -BackupDirectory <备份目录> -InstanceDirectory <新实例目录>
    install-browsers   安装 Playwright 浏览器内核（-Browsers chromium,firefox,webkit；-DryRun 只预览）
    upgrade             从当前新发行包就地升级旧安装目录（-Target <旧目录>；-Yes 跳过确认）
    start / stop       只启停后端进程，不碰 MySQL（up/down 内部调用）

  开发与发布（只在源码目录可用）
    build              完整发行构建，输出到 artifacts\releases（-SkipTests 只用于调试包）
    verify             跑检查不打包（-Full 全量集成测试，-IncludeBrowser 再跑浏览器流程，-Forks 1 机械硬盘用）
    clean              清理构建过程文件和旧发行包（-WhatIf 只列不删，-KeepReleases 2）
    mysql              启动/初始化本项目专用 MySQL 8.4（-DataDirectory <固态盘目录> 首次可指定数据目录）
    reset-test-db      重建集成测试库（verify/build 自动调用）
    maven <参数...>    用 Java 21 执行仓库内的 Maven Wrapper，参数原样传给 Maven
    offline-bundle     给已有发行目录补做离线完整包：offline-bundle -ReleaseDirectory <发行目录>

    sync-public        把本仓库的公开部分同步到公开库工作区：sync-public -PublicDirectory <目录>（-Reverse 反向同步）

  通用选项：-InstanceDirectory <目录> 操作默认 instance\ 之外的实例；-Offline 离线模式（缺什么直接报错，不联网下载）；
           up 还接受 -Dev（Vite 热更新）、-Build（先重新打包）、-NoBrowser。

工具查找顺序：config.json 里填的路径 → 环境变量（JAVA_HOME、MYSQL_HOME、PLAYWRIGHT_BROWSERS_PATH）→ 包里自带的 .tools\ → 本机已安装的
→ 最后才联网下载到 .tools\。找到的路径会写回 instance\config.json，之后只认配置文件。离线完整包（*-offline-windows.zip）自带 MySQL、
Chromium 和 VC++ 运行库，只需本机装好 Java 21（或更高）和 PowerShell 7。
#>
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Text.UTF8Encoding]::new($false)
$Script:Utf8 = [Text.UTF8Encoding]::new($false)
$Script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$Script:IsSourceTree = Test-Path -LiteralPath (Join-Path $Script:ProjectRoot 'backend/pom.xml') -PathType Leaf
$Script:ToolsRoot = Join-Path $Script:ProjectRoot '.tools'
$Script:RuntimeRoot = Join-Path $Script:ProjectRoot '.runtime'
$Script:MysqlRuntime = Join-Path $Script:RuntimeRoot 'mysql'
$Script:MysqlConnectionFile = Join-Path $Script:MysqlRuntime 'connection.json'
$Script:ViteStateFile = Join-Path $Script:RuntimeRoot 'dev/vite.json'
$Script:Frontend = Join-Path $Script:ProjectRoot 'frontend'
$Script:DevJar = Join-Path $Script:ProjectRoot 'backend/target/ai-test-platform-1.0.0-SNAPSHOT.jar'
$Script:ReleaseJar = Join-Path $Script:ProjectRoot 'app.jar'
$Script:Offline = [bool]($env:AI_TEST_OFFLINE -and $env:AI_TEST_OFFLINE -ne '0' -and $env:AI_TEST_OFFLINE -ne 'false')
$Script:VcRedistUrl = 'https://aka.ms/vs/17/release/vc_redist.x64.exe'
$Script:ToolSources = @{}   # 记录每样工具是从哪里找到的，status 里显示

# Playwright 版本以源码的 pom.xml 或发行包 app.jar 里的依赖为准，不在脚本里写死；浏览器内核目录名为 .tools\playwright-<版本>。
function Get-PlaywrightVersion {
    if ($Script:PlaywrightVersionCache) { return $Script:PlaywrightVersionCache }
    $version = $null
    $pom = Join-Path $Script:ProjectRoot 'backend/pom.xml'
    if (Test-Path -LiteralPath $pom) {
        $match = [regex]::Match((Get-Content -Raw -LiteralPath $pom), '<playwright\.version>([^<]+)</playwright\.version>')
        if ($match.Success) { $version = $match.Groups[1].Value.Trim() }
    }
    if (-not $version) {
        foreach ($jar in @($Script:ReleaseJar, $Script:DevJar)) {
            if (-not (Test-Path -LiteralPath $jar)) { continue }
            $archive = [IO.Compression.ZipFile]::OpenRead($jar)
            try { $entry = $archive.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/playwright-(\d+\.\d+\.\d+)\.jar$' } | Select-Object -First 1 } finally { $archive.Dispose() }
            if ($entry) { $version = [regex]::Match($entry.FullName, 'playwright-(\d+\.\d+\.\d+)\.jar').Groups[1].Value; break }
        }
    }
    if (-not $version) { throw '无法确定 Playwright 版本：源码目录需要 backend/pom.xml，发行包需要 app.jar。' }
    $Script:PlaywrightVersionCache = $version
    return $version
}
function Get-DefaultBrowsersDirectory { return Join-Path $Script:ToolsRoot ('playwright-' + (Get-PlaywrightVersion)) }

# 固定版本的官方下载源。第一个地址不通时依次尝试后面的；下载后校验 SHA-256。
$Script:Downloads = @{
    jdk = @{ folder = 'jdk-21.0.12.1+1'; file = 'OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip'; sha256 = 'f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e'
        urls = @('https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip',
                 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip') }
    node = @{ folder = 'node-v24.21.0-win-x64'; file = 'node-v24.21.0-win-x64.zip'; sha256 = '158f7685b44de51f6c0df1d153526cbcd3e1bc739a8dfc607721cef75de9e541'
        urls = @('https://npmmirror.com/mirrors/node/v24.21.0/node-v24.21.0-win-x64.zip', 'https://nodejs.org/dist/v24.21.0/node-v24.21.0-win-x64.zip') }
    mysql = @{ folder = 'mysql-8.4.10-winx64'; file = 'mysql-8.4.10-winx64.zip'; sha256 = '3b950db31c33fb59252568c012bd9ee5fac50811e778ca7c8f1a0dc91686cd6f'
        urls = @('https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.10-winx64.zip') }
}

# 后台子进程（mysqld、java、Vite）不能继承本脚本的标准句柄：脚本的输出被管道捕获时（CI、另一个脚本、`| tee`），
# 继承了句柄的子进程会让调用方一直等到它退出。启动子进程前临时关掉三个标准句柄的可继承标志。
Add-Type -Namespace AiTest -Name Handles -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true)] public static extern IntPtr GetStdHandle(int nStdHandle);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetHandleInformation(IntPtr hObject, uint dwMask, uint dwFlags);
'@
function Set-StdHandleInheritance([bool]$Inherit) {
    foreach ($id in -10, -11, -12) {
        $handle = [AiTest.Handles]::GetStdHandle($id)
        if ($handle -ne [IntPtr]::Zero -and $handle -ne [IntPtr](-1)) { $null = [AiTest.Handles]::SetHandleInformation($handle, 1, $(if ($Inherit) { 1 } else { 0 })) }
    }
}
function Start-DetachedProcess {
    # 与 Start-Process 参数相同，只是子进程不继承本脚本的标准句柄。
    param([string]$FilePath, [string[]]$ArgumentList, [string]$WorkingDirectory, [hashtable]$Environment, [string]$RedirectStandardOutput, [string]$RedirectStandardError)
    $parameters = @{ FilePath = $FilePath; WindowStyle = 'Hidden'; PassThru = $true }
    if ($ArgumentList) { $parameters.ArgumentList = $ArgumentList }
    if ($WorkingDirectory) { $parameters.WorkingDirectory = $WorkingDirectory }
    if ($Environment) { $parameters.Environment = $Environment }
    if ($RedirectStandardOutput) { $parameters.RedirectStandardOutput = $RedirectStandardOutput }
    if ($RedirectStandardError) { $parameters.RedirectStandardError = $RedirectStandardError }
    Set-StdHandleInheritance $false
    try { return Start-Process @parameters } finally { Set-StdHandleInheritance $true }
}

function Write-Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Ok([string]$Message) { Write-Host "    $Message" -ForegroundColor Green }
function Write-Warn([string]$Message) { Write-Host "    $Message" -ForegroundColor Yellow }
function Write-Note([string]$Message) { Write-Host "    $Message" -ForegroundColor DarkGray }

# ============================================================ 公共函数 ============================================================
# 这些函数也被 scripts/tests/*.ps1 和后端集成测试直接加载（. scripts/aitest.ps1）。

function Resolve-AiTestPath([string]$Base, [string]$Path) {
    if ([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
    return [IO.Path]::GetFullPath((Join-Path $Base $Path))
}

function Resolve-AiTestArchivePath([string]$Root, [string]$RelativePath) {
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath) -or $RelativePath.Contains(':')) {
        throw '备份内的路径必须是非空的相对路径。'
    }
    $resolvedRoot = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $resolvedPath = [IO.Path]::GetFullPath((Join-Path $resolvedRoot $RelativePath))
    if (-not $resolvedPath.StartsWith($resolvedRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw '备份内的路径越出了它所在的目录。'
    }
    return $resolvedPath
}

function Read-AiTestConfiguration([string]$InstanceDirectory, [string]$ConfigPath = '') {
    $instance = [IO.Path]::GetFullPath($InstanceDirectory)
    $configFile = if ($ConfigPath) { [IO.Path]::GetFullPath($ConfigPath) } else { Join-Path $instance 'config.json' }
    if (-not (Test-Path -LiteralPath $configFile -PathType Leaf)) { throw "找不到配置文件：$configFile。请复制 config.example.json 并填写数据库连接。" }
    $config = Get-Content -Raw -LiteralPath $configFile | ConvertFrom-Json -AsHashtable
    if ($config -isnot [Collections.IDictionary] -or $config.database -isnot [Collections.IDictionary]) { throw '配置文件必须包含 database 对象。' }
    $portNumber = 0
    if (-not [int]::TryParse([string]$config.port, [ref]$portNumber) -or $portNumber -lt 1 -or $portNumber -gt 65535) { throw 'port 必须是 1 到 65535 之间的整数。' }
    $bindAddress = if ($config.bind) { [string]$config.bind } else { '127.0.0.1' }
    $parsedAddress = $null
    if (-not [Net.IPAddress]::TryParse($bindAddress, [ref]$parsedAddress)) { throw 'bind 必须是 IP 地址，例如 127.0.0.1。' }
    if (-not [Net.IPAddress]::IsLoopback($parsedAddress) -and -not $parsedAddress.Equals([Net.IPAddress]::Any) -and -not $parsedAddress.Equals([Net.IPAddress]::IPv6Any)) {
        throw 'bind 只能是本机回环地址或 0.0.0.0 / ::，这样本机的停止通道才可用。'
    }
    if ($config.Contains('security') -and $config.security -isnot [Collections.IDictionary]) { throw 'security 必须是一个对象。' }
    if (-not $config.Contains('security')) { $config.security = @{} }
    foreach ($flag in @('enabled','secureCookie')) {
        if (-not $config.security.Contains($flag)) { $config.security[$flag] = $false }
        if ($config.security[$flag] -isnot [bool]) { throw "security.$flag 必须是 JSON 布尔值 true/false。" }
    }
    foreach ($credential in @('username','password')) {
        if (-not $config.security.Contains($credential)) { $config.security[$credential] = '' }
        if ($config.security[$credential] -isnot [string]) { throw "security.$credential 必须是字符串。" }
    }
    if (-not $config.security.Contains('sessionMinutes')) { $config.security.sessionMinutes = 30 }
    $sessionMinutes = 0
    if (-not [int]::TryParse([string]$config.security.sessionMinutes, [ref]$sessionMinutes) -or $sessionMinutes -lt 1 -or $sessionMinutes -gt 1440) {
        throw 'security.sessionMinutes 必须是 1 到 1440 之间的整数。'
    }
    $config.security.sessionMinutes = $sessionMinutes
    if ($config.security.enabled) {
        if ($config.security.username -notmatch '^[\p{L}\p{N}_.@-]{1,64}$') { throw '登录账号为 1 到 64 个字母、数字或 _.@- 字符。' }
        if ([string]::IsNullOrWhiteSpace($config.security.password) -or $config.security.password.Length -lt 12 -or [Text.Encoding]::UTF8.GetByteCount($config.security.password) -gt 72) {
            throw '登录密码至少 12 个字符，且不超过 72 个 UTF-8 字节。'
        }
    } elseif (-not [Net.IPAddress]::IsLoopback($parsedAddress)) {
        throw '监听非本机地址前必须先启用 security.enabled 并设置账号密码。'
    }
    $url = [string]$config.database.url
    if (-not $url.StartsWith('jdbc:mysql://') -or $url -match '(?i)[?&](password|user|username|socketFactory|autoDeserialize)=') { throw 'database.url 必须是 MySQL JDBC 地址，账号密码写在单独的字段里。' }
    try { $databaseUri = [uri]$url.Substring(5) } catch { throw 'database.url 不是合法的 MySQL JDBC 地址。' }
    $databaseName = [uri]::UnescapeDataString($databaseUri.AbsolutePath.TrimStart('/'))
    if ($databaseUri.UserInfo -or $databaseName -notmatch '^[A-Za-z0-9_]{1,64}$' -or -not $databaseUri.Host) { throw 'database.url 必须指定一个库名，且不能内嵌账号密码。' }
    if (-not $config.database.username -or [string]$config.database.username -match '[\r\n\0]') { throw '需要填写 database.username。' }
    if ($null -eq $config.database.password) { throw '需要填写 database.password（可以为空字符串）。' }
    if ($config.runtime -isnot [Collections.IDictionary]) { $config.runtime = @{} }
    foreach ($rule in @(@('heapMiB',1024,256,32768), @('concurrency',8,1,64), @('browserWorkers',2,1,8), @('businessConnections',32,1,256))) {
        if (-not $config.runtime.Contains($rule[0])) { $config.runtime[$rule[0]] = $rule[1] }
        $number = 0
        if (-not [int]::TryParse([string]$config.runtime[$rule[0]], [ref]$number) -or $number -lt $rule[2] -or $number -gt $rule[3]) { throw "runtime.$($rule[0]) 超出允许范围。" }
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
        $config.paths.browsers = Get-DefaultBrowsersDirectory
    }
    $null = New-Item -ItemType Directory -Path $InstanceDirectory -Force
    [IO.File]::WriteAllText($configFile, ($config | ConvertTo-Json -Depth 10), $Script:Utf8)
    Write-Output "已生成实例配置：$configFile"
}

# 把自动找到的路径写回 config.json（只改有变化的字段），之后每次启动都走同一条路，用户也能直接改。
function Update-AiTestConfiguration([string]$ConfigPath, [hashtable]$Values) {
    if (-not (Test-Path -LiteralPath $ConfigPath)) { return }
    $config = Get-Content -Raw -LiteralPath $ConfigPath | ConvertFrom-Json -AsHashtable
    $changed = $false
    foreach ($key in $Values.Keys) {
        $parts = $key.Split('.'); $node = $config
        for ($i = 0; $i -lt $parts.Count - 1; $i++) { if ($node[$parts[$i]] -isnot [Collections.IDictionary]) { $node[$parts[$i]] = @{} }; $node = $node[$parts[$i]] }
        if ([string]$node[$parts[-1]] -ne [string]$Values[$key]) { $node[$parts[-1]] = $Values[$key]; $changed = $true }
    }
    if ($changed) { [IO.File]::WriteAllText($ConfigPath, ($config | ConvertTo-Json -Depth 10), $Script:Utf8) }
}

function Get-JavaMajor([string]$JavaHome) {
    $release = Join-Path $JavaHome 'release'
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe')) -or -not (Test-Path -LiteralPath $release)) { return 0 }
    $match = [regex]::Match((Get-Content -Raw -LiteralPath $release), 'JAVA_VERSION="(\d+)')
    if ($match.Success) { return [int]$match.Groups[1].Value }
    return 0
}

# 运行程序接受 Java 21 及更高版本（21 优先）；打包/测试只用 21（backend/pom.xml 的 enforcer 规则）。
function Find-AiTestJava([string]$ConfiguredJavaHome = '', [switch]$Exact21) {
    $ordered = [Collections.Generic.List[object]]::new()
    foreach ($pair in @(@($ConfiguredJavaHome, 'config.json 的 javaHome'), @($env:AI_TEST_JAVA_HOME, '环境变量 AI_TEST_JAVA_HOME'), @($env:JAVA_HOME, '环境变量 JAVA_HOME'))) {
        if ($pair[0]) { $ordered.Add(@{ Home = [string]$pair[0]; Source = $pair[1] }) }
    }
    if (Test-Path -LiteralPath $Script:ToolsRoot) { foreach ($dir in Get-ChildItem -LiteralPath $Script:ToolsRoot -Directory | Where-Object Name -Like 'jdk-*' | Sort-Object Name -Descending) { $ordered.Add(@{ Home = $dir.FullName; Source = '.tools 自带' }) } }
    $system = [Collections.Generic.List[string]]::new()
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $system.Add((Split-Path -Parent (Split-Path -Parent $javaCommand.Source))) }
    foreach ($vendorRoot in @('C:/Program Files/Java', 'C:/Program Files/Eclipse Adoptium', 'C:/Program Files/Microsoft', 'C:/Program Files/Zulu', 'C:/Program Files/BellSoft', 'C:/Program Files/Amazon Corretto')) {
        if (Test-Path -LiteralPath $vendorRoot) { foreach ($dir in Get-ChildItem -LiteralPath $vendorRoot -Directory | Sort-Object Name -Descending) { $system.Add($dir.FullName) } }
    }
    # 本机装了多个版本时优先 21，其次更高版本。
    $rated = @($system | Where-Object { $_ } | Select-Object -Unique | ForEach-Object { @{ Home = $_; Major = (Get-JavaMajor $_) } } | Where-Object { $_.Major -ge 21 })
    foreach ($item in ($rated | Sort-Object { if ($_.Major -eq 21) { 0 } else { 1 } }, { -$_.Major })) { $ordered.Add(@{ Home = $item.Home; Source = '本机已安装' }) }
    foreach ($candidate in $ordered) {
        $major = Get-JavaMajor $candidate.Home
        if ($major -lt 21) { continue }
        if ($Exact21 -and $major -ne 21) { continue }
        if ($major -ne 21 -and -not $Script:JavaVersionWarned) { Write-Warn "使用的是 Java $major（$($candidate.Home)）。程序按 Java 21 测试，更高版本一般可用；如遇异常请改用 21。"; $Script:JavaVersionWarned = $true }
        $Script:ToolSources.java = "$($candidate.Source)：$($candidate.Home)（Java $major）"
        return (Join-Path $candidate.Home 'bin/java.exe')
    }
    if ($Exact21) { throw '打包和测试需要 Java 21（正好 21）。请安装 JDK 21，或在 config.json 的 javaHome / 环境变量 AI_TEST_JAVA_HOME 里指定。' }
    throw '没有找到 Java 21 或更高版本。请安装 JDK 21（推荐 Eclipse Temurin），或在 config.json 的 javaHome 里填写已安装的 JDK 目录；联网时双击 启动.cmd 会自动下载。'
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
        throw '运维客户端只接受本实例的本机回环地址和端口。'
    }
    if (-not $Settings.Config.security.enabled) { return @{} }
    $base = $address.GetLeftPart([UriPartial]::Authority)
    $bootstrap = Invoke-WebRequest -Uri "$base/api/auth/session" -TimeoutSec 15 -MaximumRedirection 0 -NoProxy -SkipHttpErrorCheck
    if ($bootstrap.StatusCode -ne 200) { throw '无法读取平台会话。' }
    $state = $bootstrap.Content | ConvertFrom-Json
    $cookie = @($bootstrap.Headers['Set-Cookie'] | Where-Object { $_.StartsWith('AI_TEST_SESSION=') } | ForEach-Object { $_.Split(';')[0] }) | Select-Object -Last 1
    if (-not $state.enabled -or -not $state.csrfToken -or -not $cookie) { throw '配置文件里的登录设置与正在运行的实例不一致。' }
    # 特权运维客户端只连接核对过的本机监听地址；经外部 HTTPS 访问时同样通过显式 Cookie 头工作。
    $headers = @{Cookie=$cookie; 'X-CSRF-TOKEN'=$state.csrfToken}
    $login = Invoke-WebRequest -Uri "$base/api/auth/login" -Method Post -Headers $headers -ContentType 'application/x-www-form-urlencoded' `
        -Body @{username=$Settings.Config.security.username; password=$Settings.Config.security.password} -TimeoutSec 15 -MaximumRedirection 0 -NoProxy -SkipHttpErrorCheck
    if ($login.StatusCode -ne 200) { throw "配置文件里的平台账号无法登录（HTTP $($login.StatusCode)）。" }
    $renewed = @($login.Headers['Set-Cookie'] | Where-Object { $_.StartsWith('AI_TEST_SESSION=') } | ForEach-Object { $_.Split(';')[0] }) | Select-Object -Last 1
    if ($renewed) { $headers.Cookie = $renewed }
    $current = Invoke-RestMethod -Uri "$base/api/auth/session" -Headers $headers -TimeoutSec 15 -MaximumRedirection 0 -NoProxy
    if (-not $current.authenticated -or -not $current.csrfToken) { throw '运维客户端没有拿到已登录的会话。' }
    $headers['X-CSRF-TOKEN'] = $current.csrfToken
    return $headers
}

function Get-AiTestManagedProcess($State) {
    if (-not $State -or -not $State.pid -or -not $State.startedAt) { return $null }
    try {
        $managed = Get-Process -Id ([int]$State.pid) -ErrorAction Stop
        # PowerShell 7.5+ 会把 JSON 里的 ISO 时间解析成 DateTime，7.4 保持文本；按精确时刻比较，不依赖区域格式。
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
    if ($bytes.Length -ne 32) { throw '主密钥必须正好 32 字节。' }
    return ,$bytes
}

function Get-AiTestKeyHash([byte[]]$Bytes) { return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)) }

# Windows 版 MySQL 的服务端和客户端都按系统 ANSI 代码页解析文件路径（相对文件名也会先拼成绝对路径再转码）。
# 路径里有代码页表示不了的字符（英文系统上的中文目录、中文系统上的其他文字）就打不开。能表示的路径直接用，否则换到纯英文目录。
function Test-AnsiRepresentable([string]$Text) {
    $ansi = [Text.Encoding]::GetEncoding([Globalization.CultureInfo]::CurrentCulture.TextInfo.ANSICodePage)
    return $ansi.GetString($ansi.GetBytes($Text)) -ceq $Text
}
function Get-AnsiSafeDirectory([string]$Preferred, [string]$Purpose) {
    foreach ($candidate in @($Preferred, (Join-Path $env:ProgramData "ai-test-platform/$Purpose"), (Join-Path $env:SystemRoot "Temp/ai-test-platform/$Purpose"))) {
        if ($candidate -and (Test-AnsiRepresentable $candidate)) { $null = New-Item -ItemType Directory -Path $candidate -Force; return [IO.Path]::GetFullPath($candidate) }
    }
    throw "找不到 MySQL 能读取的目录（路径含系统代码页无法表示的字符）：$Preferred"
}

function Invoke-AiTestMySqlTool($Settings, [ValidateSet('mysql','mysqldump')][string]$Tool, [string[]]$Arguments, [string]$InputFile = '', [string]$OutputFile = '') {
    $mysqlRoot = [string]$Settings.Config.mysqlHome
    $executable = if ($mysqlRoot) { Join-Path $mysqlRoot "bin/$Tool.exe" } else { (Get-Command "$Tool.exe" -ErrorAction Stop).Source }
    if (-not (Test-Path -LiteralPath $executable)) { throw '请在 config.json 的 mysqlHome 里填写 MySQL 8.4 的安装目录。' }
    $null = New-Item -ItemType Directory -Path $Settings.RunDirectory,$Settings.LogDirectory -Force
    $identity = [guid]::NewGuid().ToString('N')
    $clientDirectory = Get-AnsiSafeDirectory -Preferred $Settings.RunDirectory -Purpose 'client'
    $clientFile = Join-Path $clientDirectory "mysql-$identity.cnf"
    $stdoutFile = Join-Path $Settings.RunDirectory "mysql-$identity.out"
    $errorFile = Join-Path $Settings.LogDirectory "mysql-$identity.log"
    function Quote-ClientValue([string]$Value) { return '"' + $Value.Replace('\','\\').Replace('"','\"').Replace("`r",'\r').Replace("`n",'\n') + '"' }
    $lines = @('[client]', ('host=' + (Quote-ClientValue $Settings.DatabaseHost)), ('port=' + $Settings.DatabasePort),
        ('user=' + (Quote-ClientValue ([string]$Settings.Config.database.username))), ('password=' + (Quote-ClientValue ([string]$Settings.Config.database.password))), 'default-character-set=utf8mb4')
    if ($Settings.Config.mysqlSslMode) { $lines += 'ssl-mode=' + [string]$Settings.Config.mysqlSslMode }
    if ($Settings.Config.mysqlSslCa) { $lines += 'ssl-ca=' + (Quote-ClientValue (Resolve-AiTestPath $Settings.Instance $Settings.Config.mysqlSslCa)) }
    [IO.File]::WriteAllText($clientFile, ($lines -join "`n"), $Script:Utf8)
    $mysqlProcess = $null; $started = $false; $stdout = $null; $stderr = $null
    try {
        if ($OutputFile -and (Test-Path -LiteralPath $OutputFile)) { throw '输出文件已存在，没有覆盖。' }
        $info = [Diagnostics.ProcessStartInfo]::new($executable)
        $info.UseShellExecute=$false; $info.CreateNoWindow=$true
        $info.WorkingDirectory=$clientDirectory
        $info.RedirectStandardInput=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
        # defaults 文件放在代码页能表示的目录里，参数只给 ASCII 文件名；SQL 和导出内容走重定向流，不受路径限制。
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
        if ($mysqlProcess.ExitCode -ne 0) { throw "MySQL 命令失败，详情见 $errorFile" }
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
    [IO.File]::WriteAllText($inputFile, $Sql, $Script:Utf8)
    try { return Invoke-AiTestMySqlTool -Settings $Settings -Tool mysql -Arguments @('--batch','--skip-column-names','--raw','--binary-mode',$Settings.DatabaseName) -InputFile $inputFile }
    finally { Remove-Item -LiteralPath $inputFile -Force }
}

# 被其他脚本 dot-source 加载时只提供函数，不执行命令。
if ($MyInvocation.InvocationName -eq '.') { return }

# ============================================================ 工具下载 ============================================================

function Get-AiTestTool([string]$Name, [string]$Description) {
    $spec = $Script:Downloads[$Name]
    $target = Join-Path $Script:ToolsRoot $spec.folder
    if (Test-Path -LiteralPath $target -PathType Container) { return $target }
    $null = New-Item -ItemType Directory -Path $Script:ToolsRoot -Force
    $archive = Join-Path $Script:ToolsRoot $spec.file
    $valid = (Test-Path -LiteralPath $archive) -and ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ieq $spec.sha256)
    if (-not $valid) {
        if ($Script:Offline) { throw "离线模式：缺少 $Description。请把 $($spec.file) 放到 $Script:ToolsRoot，或在 config.json 里指定已安装的路径。" }
        $downloaded = $false
        foreach ($url in $spec.urls) {
            Write-Warn "下载 $Description（$url）"
            try {
                Invoke-WebRequest -Uri $url -OutFile $archive -ConnectionTimeoutSeconds 20 -OperationTimeoutSeconds 120 -MaximumRetryCount 2 -RetryIntervalSec 5
                if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ieq $spec.sha256) { $downloaded = $true; break }
                Write-Warn '下载的文件校验不通过，换下一个地址重试。'
            } catch { Write-Warn "这个地址下载失败：$($_.Exception.Message)" }
            if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
        }
        if (-not $downloaded) { throw "无法下载 $Description。请检查网络，或手动把 $($spec.file) 放到 $Script:ToolsRoot 后重试。" }
    }
    Write-Note "解压 $($spec.file) 到 .tools\"
    Expand-Archive -LiteralPath $archive -DestinationPath $Script:ToolsRoot -Force
    if (-not (Test-Path -LiteralPath $target -PathType Container)) { throw "解压后没有找到 $target。" }
    return $target
}

function Get-AiTestJavaOrInstall([string]$ConfiguredJavaHome = '', [switch]$Exact21) {
    try { return Find-AiTestJava $ConfiguredJavaHome -Exact21:$Exact21 } catch { $reason = $_.Exception.Message }
    if ($Script:Offline) { throw "离线模式：$reason" }
    Write-Warn 'Java 21 未安装，自动下载 Eclipse Temurin JDK 21（约 200 MB，一次性）。'
    $jdkHome = Get-AiTestTool 'jdk' 'Java 21'
    $Script:ToolSources.java = ".tools 自带：$jdkHome（Java 21）"
    return (Join-Path $jdkHome 'bin/java.exe')
}

function Get-AiTestNodeDirectory {
    if ($env:AI_TEST_NODE_HOME -and (Test-Path -LiteralPath (Join-Path $env:AI_TEST_NODE_HOME 'node.exe'))) { return $env:AI_TEST_NODE_HOME }
    $node = Get-Command node.exe -ErrorAction SilentlyContinue
    if ($node) {
        $major = 0
        $version = (& $node.Source -p 'process.versions.node' 2>$null)
        if ($LASTEXITCODE -eq 0 -and $version -and [int]::TryParse($version.Split('.')[0], [ref]$major) -and $major -ge 24) { return (Split-Path -Parent $node.Source) }
    }
    $local = Join-Path $Script:ToolsRoot $Script:Downloads.node.folder
    if (-not (Test-Path -LiteralPath (Join-Path $local 'node.exe'))) {
        if ($Script:Offline) { throw '离线模式：没有找到 Node.js 24（打包源码需要）。请安装 Node.js 24，或设置环境变量 AI_TEST_NODE_HOME。' }
        Write-Warn 'Node.js 24 未安装，自动下载到 .tools\（约 30 MB，一次性）。'; $local = Get-AiTestTool 'node' 'Node.js 24'
    }
    return $local
}

# 前端命令统一经 corepack 执行，自动使用 frontend/package.json 里钉住的 pnpm 版本，不依赖本机装的是哪个 pnpm。
function Invoke-Pnpm {
    # 简单函数用 $args 收参数：--frozen-lockfile、-B 这类词不会被 PowerShell 当成自己的参数。
    [string[]]$PnpmArguments = $args
    $nodeDirectory = Get-AiTestNodeDirectory
    $env:PATH = $nodeDirectory + [IO.Path]::PathSeparator + $env:PATH
    $env:COREPACK_ENABLE_DOWNLOAD_PROMPT = '0'
    $env:COREPACK_HOME = Join-Path $Script:ToolsRoot 'corepack'
    $corepack = Join-Path $nodeDirectory 'corepack.cmd'
    if (-not (Test-Path -LiteralPath $corepack)) { throw "找不到 $corepack；请使用官方 Node.js 24 发行版。" }
    # corepack 按当前目录的 package.json 选 pnpm 版本，所以必须先进入 frontend/。
    Push-Location $Script:Frontend
    try { & $corepack pnpm @PnpmArguments | Out-Host } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "前端命令失败：pnpm $($PnpmArguments -join ' ')" }
}

function Invoke-Maven {
    # 输出直接打到控制台，函数只返回 Maven 的退出码。
    [string[]]$MavenArguments = $args
    if (-not $Script:IsSourceTree) { throw 'maven 只能在源码目录使用。' }
    $java = Get-AiTestJavaOrInstall -Exact21
    $javaHome = Split-Path -Parent (Split-Path -Parent $java)
    $env:JAVA_HOME = $javaHome
    $env:PATH = (Join-Path $javaHome 'bin') + [IO.Path]::PathSeparator + $env:PATH
    $env:MAVEN_OPTS = '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Duser.language=en -Duser.country=US'
    if (-not $MavenArguments -or $MavenArguments.Count -eq 0) { $MavenArguments = @('verify') }
    $wrapper = Join-Path $Script:ProjectRoot 'backend/mvnw.cmd'
    if (-not (Test-Path -LiteralPath $wrapper)) { throw '仓库内的 Maven Wrapper（backend/mvnw.cmd）缺失。' }
    & $wrapper -f (Join-Path $Script:ProjectRoot 'backend/pom.xml') @MavenArguments | Out-Host
    return $LASTEXITCODE
}

# ============================================================ 项目 MySQL ============================================================

function Test-AiTestPortOpen([string]$HostName, [int]$Port, [int]$TimeoutMs = 800) {
    $client = [Net.Sockets.TcpClient]::new()
    try { return $client.ConnectAsync($HostName, $Port).Wait($TimeoutMs) -and $client.Connected }
    catch { return $false } finally { $client.Dispose() }
}

# my.ini / admin.cnf / 日志所在目录：.runtime/mysql 能被代码页表示就用它，否则用 %ProgramData%\ai-test-platform\mysql（记在 connection.json 的 runtime 里）。
function Get-MysqlRuntimeDirectory {
    if (Test-Path -LiteralPath $Script:MysqlConnectionFile) {
        $recorded = (Get-Content -Raw -LiteralPath $Script:MysqlConnectionFile | ConvertFrom-Json -AsHashtable).runtime
        if ($recorded) { return [string]$recorded }
    }
    return (Get-AnsiSafeDirectory -Preferred $Script:MysqlRuntime -Purpose 'mysql')
}
function Get-ProjectMysql {
    if (-not (Test-Path -LiteralPath $Script:MysqlConnectionFile)) { return $null }
    $connection = Get-Content -Raw -LiteralPath $Script:MysqlConnectionFile | ConvertFrom-Json -AsHashtable
    $process = if ($connection.pid) { Get-Process -Id ([int]$connection.pid) -ErrorAction SilentlyContinue } else { $null }
    if ($process -and $process.ProcessName -ne 'mysqld') { $process = $null }
    return [pscustomobject]@{ Port=[int]$connection.port; Home=[string]$connection.home; Username=[string]$connection.username; Process=$process; Listening=(Test-AiTestPortOpen '127.0.0.1' ([int]$connection.port)) }
}

# MySQL 的 Windows 压缩包版需要微软 VC++ 运行库（Visual C++ 2015-2022 x64）。缺失时优先用包里自带的安装程序，联网时从微软官网下载。
function Test-VcRuntime {
    foreach ($key in @('HKLM:\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\VisualStudio\14.0\VC\Runtimes\x64')) {
        if (Test-Path -LiteralPath $key) { $value = Get-ItemProperty -LiteralPath $key; if ($value.Installed -eq 1 -and [string]$value.Version -match '^v14\.(2[0-9]|[3-9][0-9])') { return $true } }
    }
    return $false
}
function Install-VcRuntime {
    if (Test-VcRuntime) { return }
    $installer = Join-Path $Script:ToolsRoot 'vc_redist.x64.exe'
    if (-not (Test-Path -LiteralPath $installer)) {
        if ($Script:Offline) { throw '离线模式：本机缺少微软 VC++ 运行库（MySQL 需要）。请把 vc_redist.x64.exe 放到 .tools\ 后重试，或先手动安装它。' }
        Write-Warn '本机缺少微软 VC++ 运行库（MySQL 需要），从微软官网下载（约 25 MB）。'
        $null = New-Item -ItemType Directory -Path $Script:ToolsRoot -Force
        Invoke-WebRequest -Uri $Script:VcRedistUrl -OutFile $installer -ConnectionTimeoutSeconds 20 -OperationTimeoutSeconds 120 -MaximumRetryCount 2 -RetryIntervalSec 5
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $installer
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Microsoft Corporation') { Remove-Item -LiteralPath $installer -Force; throw 'vc_redist.x64.exe 的微软数字签名校验失败，已删除，请重新获取。' }
    Write-Warn '安装 VC++ 运行库，需要管理员权限，请在弹出的窗口里点「是」……'
    $process = Start-Process -FilePath $installer -ArgumentList @('/install', '/passive', '/norestart') -Verb RunAs -Wait -PassThru
    if ($process.ExitCode -notin @(0, 1638, 3010)) { throw "VC++ 运行库安装失败（退出码 $($process.ExitCode)）。请手动运行 $installer 后重试。" }
    if (-not (Test-VcRuntime)) { throw 'VC++ 运行库安装后仍未检测到，请重启电脑后重试。' }
    Write-Ok 'VC++ 运行库已安装。'
}

# 本机已安装的 MySQL 8.4：MYSQL_HOME → PATH 上的 mysqld → 官方安装器的默认目录。只借用程序文件，数据目录仍在本项目里。
function Find-SystemMysqlHome {
    $candidates = [Collections.Generic.List[object]]::new()
    if ($env:MYSQL_HOME) { $candidates.Add(@{ Home = $env:MYSQL_HOME; Source = '环境变量 MYSQL_HOME' }) }
    $onPath = Get-Command mysqld.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates.Add(@{ Home = (Split-Path -Parent (Split-Path -Parent $onPath.Source)); Source = 'PATH' }) }
    foreach ($root in @("$env:ProgramFiles\MySQL", "${env:ProgramFiles(x86)}\MySQL")) {
        if ($root -and (Test-Path -LiteralPath $root)) { foreach ($dir in Get-ChildItem -LiteralPath $root -Directory | Where-Object Name -Like 'MySQL Server 8.4*' | Sort-Object Name -Descending) { $candidates.Add(@{ Home = $dir.FullName; Source = '本机已安装' }) } }
    }
    foreach ($candidate in $candidates) {
        $mysqld = Join-Path $candidate.Home 'bin/mysqld.exe'
        if (-not (Test-Path -LiteralPath $mysqld)) { continue }
        $version = & $mysqld --version 2>$null
        if ($LASTEXITCODE -eq 0 -and $version -match ' 8\.4\.') { return @{ Home = [IO.Path]::GetFullPath($candidate.Home); Source = $candidate.Source } }
    }
    return $null
}

function Invoke-Mysql {
    param([int]$Port = 3307, [string]$MySqlHome = '', [string]$DataDirectory = '')
    $null = New-Item -ItemType Directory -Path $Script:MysqlRuntime,$Script:ToolsRoot -Force
    $runtime = Get-MysqlRuntimeDirectory
    if ($runtime -ne [IO.Path]::GetFullPath($Script:MysqlRuntime)) { Write-Warn "项目路径含系统代码页无法表示的字符，MySQL 配置、日志和数据放在 $runtime" }
    $existing = $null
    if (Test-Path -LiteralPath $Script:MysqlConnectionFile) {
        $existing = Get-Content -Raw -LiteralPath $Script:MysqlConnectionFile | ConvertFrom-Json
        $running = if ($existing.pid) { Get-Process -Id ([int]$existing.pid) -ErrorAction SilentlyContinue } else { $null }
        if ($running -and $running.ProcessName -eq 'mysqld') { Write-Output "项目 MySQL 已在运行：127.0.0.1:$($existing.port)"; return }
        $MySqlHome = $existing.home; $Port = $existing.port
        if ($existing.dataDirectory) { $DataDirectory = $existing.dataDirectory }
    }
    $source = if (-not $MySqlHome) { '' } elseif ($MySqlHome.StartsWith($Script:ToolsRoot, [StringComparison]::OrdinalIgnoreCase)) { '.tools 自带' } else { '指定路径' }
    if (-not $MySqlHome -and (Test-Path -LiteralPath (Join-Path $Script:ToolsRoot "$($Script:Downloads.mysql.folder)/bin/mysqld.exe"))) { $MySqlHome = Join-Path $Script:ToolsRoot $Script:Downloads.mysql.folder; $source = '.tools 自带' }
    if (-not $MySqlHome -and (Test-Path -LiteralPath (Join-Path $Script:ToolsRoot $Script:Downloads.mysql.file))) { $MySqlHome = Get-AiTestTool 'mysql' 'MySQL 8.4'; $source = '.tools 自带' }
    if (-not $MySqlHome) {
        $found = Find-SystemMysqlHome
        if ($found) { $MySqlHome = $found.Home; $source = $found.Source }
    }
    if (-not $MySqlHome) {
        if ($Script:Offline) { throw '离线模式：本机没有 MySQL 8.4。请把 mysql-8.4.x-winx64.zip 放到 .tools\，或安装 MySQL 8.4 后设置环境变量 MYSQL_HOME。' }
        Write-Warn '本机没有 MySQL 8.4，自动下载官方压缩包（约 270 MB，一次性）。'
        $MySqlHome = Get-AiTestTool 'mysql' 'MySQL 8.4'; $source = '.tools 自带'
    }
    if ($source -eq '.tools 自带') { Install-VcRuntime }
    $Script:ToolSources.mysql = "${source}：$MySqlHome"
    $mysqld = Join-Path $MySqlHome 'bin/mysqld.exe'
    $mysql = Join-Path $MySqlHome 'bin/mysql.exe'
    if (-not (Test-Path -LiteralPath $mysqld)) { throw "找不到 $mysqld。" }
    $versionOutput = & $mysqld --version
    if ($LASTEXITCODE -eq -1073741515) { throw 'mysqld 缺少系统库（通常是微软 VC++ 运行库）。请安装 .tools\vc_redist.x64.exe 或从微软官网安装 Visual C++ 2015-2022 x64 运行库后重试。' }
    if ($versionOutput -notmatch '8\.4\.') { throw "本项目需要 MySQL 8.4 LTS，找到的是：$versionOutput" }
    # 数据目录默认在 .runtime/mysql/data。源码放在机械硬盘时可用 -DataDirectory 指到固态盘（选择会记住）：
    # 集成测试每建一个新库要执行 65 张 CREATE TABLE，机械盘约一分钟，固态盘几秒。
    $dataDirectory = if ($DataDirectory) { [IO.Path]::GetFullPath($DataDirectory) } else { Join-Path $runtime 'data' }
    $logFile = Join-Path $runtime 'mysql.log'
    if (-not (Test-AnsiRepresentable "$MySqlHome$dataDirectory")) { throw "MySQL 服务端不支持这个路径里的字符：$MySqlHome 或 $dataDirectory。请把 MySQL 或数据目录放到纯英文路径下。" }
    $null = New-Item -ItemType Directory -Path $dataDirectory -Force
    $configPath = Join-Path $runtime 'my.ini'
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
# 本地开发/测试实例：集成测试写入数百 MB 随机主键的数据，工作集放内存，重做日志每秒刷一次而不是每次提交都刷。
innodb_buffer_pool_size=1G
innodb_flush_log_at_trx_commit=2
log-error=$($logFile.Replace('\','/'))
"@
    $ansi = [Text.Encoding]::GetEncoding([Globalization.CultureInfo]::CurrentCulture.TextInfo.ANSICodePage)
    [IO.File]::WriteAllBytes($configPath, $ansi.GetBytes($config))
    $firstStart = -not (Test-Path -LiteralPath (Join-Path $dataDirectory 'mysql'))
    Push-Location $runtime
    try {
        if ($firstStart) {
            Write-Note '首次初始化数据目录……'
            & $mysqld --defaults-file=my.ini --initialize-insecure
            if ($LASTEXITCODE -ne 0) { throw "MySQL 初始化失败，详情见 $logFile" }
        }
        # --no-monitor：Windows 版 mysqld 默认先起一个监护进程再由它拉起真正的服务进程，而监护进程拼子进程命令行时不给带空格的路径加引号，
        # 项目放在「离线 测试」这类目录下时子进程会读不到 --defaults-file。直接运行服务进程即可（我们不用 RESTART 语句）。
        # 标准输出/错误重定向到文件：mysqld 不能继承父进程的管道，否则从 CI 或另一个脚本里调用时父进程会一直等它退出。
        $process = Start-DetachedProcess -FilePath $mysqld -ArgumentList @('--defaults-file=my.ini', '--no-monitor') -WorkingDirectory $runtime `
            -RedirectStandardOutput (Join-Path $runtime 'mysqld.out.log') -RedirectStandardError (Join-Path $runtime 'mysqld.err.log')
    } finally { Pop-Location }
    $ready = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        if ($process.HasExited) { throw "MySQL 启动后立即退出，详情见 $logFile（常见原因：端口 $Port 被占用，或数据目录被另一个 MySQL 使用）。" }
        if (Test-AiTestPortOpen '127.0.0.1' $Port 500) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "MySQL 在 60 秒内没有就绪，详情见 $logFile" }
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
        if ($LASTEXITCODE -ne 0) { throw '创建平台数据库和账号失败。' }
        [IO.File]::WriteAllText((Join-Path $runtime 'admin.cnf'), "[client]`nuser=root`npassword=$rootPassword`nhost=127.0.0.1`nport=$Port`n", $Script:Utf8)
        $existing = @{username='aitest'; password=$appPassword}
    }
    # 测试代码按固定路径读 .runtime/mysql/admin.cnf；运行目录挪走时留一份副本。
    if ($runtime -ne [IO.Path]::GetFullPath($Script:MysqlRuntime) -and (Test-Path -LiteralPath (Join-Path $runtime 'admin.cnf'))) { Copy-Item -LiteralPath (Join-Path $runtime 'admin.cnf') -Destination (Join-Path $Script:MysqlRuntime 'admin.cnf') -Force }
    $settings = @{home=$MySqlHome; port=$Port; username=$existing.username; password=$existing.password; version=$versionOutput; pid=$process.Id; dataDirectory=$dataDirectory; runtime=$runtime}
    [IO.File]::WriteAllText($Script:MysqlConnectionFile, ($settings | ConvertTo-Json), $Script:Utf8)
    Write-Output "项目 MySQL 8.4 已就绪：127.0.0.1:$Port（账号密码在 .runtime\mysql\connection.json，已被 Git 忽略）"
}

function Stop-ProjectMysql {
    $mysql = Get-ProjectMysql
    if (-not $mysql -or -not $mysql.Process) { Write-Ok '未运行'; return }
    $mysqladmin = Join-Path $mysql.Home 'bin/mysqladmin.exe'
    $runtime = Get-MysqlRuntimeDirectory
    $stopped = $false
    if ((Test-Path -LiteralPath $mysqladmin) -and (Test-Path -LiteralPath (Join-Path $runtime 'admin.cnf'))) {
        $shutdown = Start-Process -FilePath $mysqladmin -ArgumentList @('--defaults-file=admin.cnf', 'shutdown') -WorkingDirectory $runtime -WindowStyle Hidden -PassThru -Wait
        $stopped = ($shutdown.ExitCode -eq 0) -and $mysql.Process.WaitForExit(30000)
    }
    if (-not $stopped) { Write-Warn '优雅关闭未成功，直接结束 mysqld 进程。'; $mysql.Process.Kill(); $mysql.Process.WaitForExit(15000) | Out-Null }
    Write-Ok "已停止 MySQL（PID $($mysql.Process.Id)）"
}

function Invoke-ResetTestDb {
    param([int]$Forks = 3, [switch]$Quiet)
    # 集成测试共用持久库，不重建会越用越大，残留的晨报排期还会抢走模型 fixture 的应答（AI 流水线测试报 503）。
    # Failsafe 分 $Forks 个 JVM 跑，第 N 个用带 _N 后缀的库。AI_TEST_INTEGRATION_DB_URL 指向外部库时跳过。
    if ($env:AI_TEST_INTEGRATION_DB_URL) { if (-not $Quiet) { Write-Output '已设置 AI_TEST_INTEGRATION_DB_URL，不动外部测试库。' }; return }
    $runtime = Get-MysqlRuntimeDirectory
    $adminFile = Join-Path $runtime 'admin.cnf'
    if (-not (Test-Path -LiteralPath $Script:MysqlConnectionFile) -or -not (Test-Path -LiteralPath $adminFile)) { throw '项目 MySQL 尚未初始化，先运行 aitest.ps1 mysql（或 启动.cmd）。' }
    $connection = Get-Content -Raw -LiteralPath $Script:MysqlConnectionFile | ConvertFrom-Json
    if ($connection.username -notmatch '^[A-Za-z0-9_]+$') { throw '测试账号名不符合预期。' }
    $mysql = Join-Path ([string]$connection.home) 'bin/mysql.exe'
    if (-not (Test-Path -LiteralPath $mysql)) { throw "找不到 MySQL 客户端：$mysql" }
    if (Get-Process java -ErrorAction SilentlyContinue) { throw '有 Java 进程正在运行。请先结束其他构建/测试，或双击 停止.cmd 停掉开发实例，再重建测试库。' }
    if ($Forks -lt 1 -or $Forks -gt 16) { throw 'Forks 必须在 1 到 16 之间。' }
    $statements = [Text.StringBuilder]::new()
    for ($fork = 1; $fork -le $Forks; $fork++) {
        $suffix = if ($fork -eq 1) { '' } else { "_$fork" }
        foreach ($schema in @("ai_test_platform_test$suffix", "ai_test_business_test$suffix")) {
            $null = $statements.AppendLine("DROP DATABASE IF EXISTS $schema;")
            $null = $statements.AppendLine("CREATE DATABASE $schema CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
            $null = $statements.AppendLine("GRANT ALL ON $schema.* TO '$($connection.username)'@'localhost';")
        }
    }
    # 上次用更多 fork 或崩溃的 ProcessRecoveryIT 子进程留下的库。
    $null = $statements.AppendLine("SELECT CONCAT('DROP DATABASE `', schema_name, '`;') FROM information_schema.schemata WHERE schema_name LIKE 'ai_test_acceptance_%' OR (schema_name REGEXP '^ai_test_(platform|business)_test_[0-9]+$' AND CAST(SUBSTRING_INDEX(schema_name, '_', -1) AS UNSIGNED) > $Forks);")
    Push-Location $runtime
    try {
        $output = $statements.ToString() | & $mysql --defaults-file=admin.cnf --default-character-set=utf8mb4 --connect-timeout=10 --batch --skip-column-names 2>&1
        if ($LASTEXITCODE -ne 0) { throw "重建测试库失败：$output" }
        $stale = @($output | Where-Object { $_ -like 'DROP DATABASE*' })
        if ($stale.Count -gt 0) {
            $null = ($stale -join "`n") | & $mysql --defaults-file=admin.cnf --connect-timeout=10 --batch 2>&1
            if ($LASTEXITCODE -ne 0) { throw '删除残留测试库失败。' }
        }
    } finally { Pop-Location }
    if (-not $Quiet) { Write-Output "已重建 $Forks 组集成测试库（127.0.0.1:$($connection.port)）$(if ($stale.Count -gt 0) { "，并删除 $($stale.Count) 个残留库" })。" }
}

# ============================================================ 运行时命令 ============================================================

function Resolve-InstanceDirectory([string]$InstanceDirectory) {
    if ($InstanceDirectory) { return [IO.Path]::GetFullPath($InstanceDirectory) }
    return Join-Path $Script:ProjectRoot 'instance'
}

function Resolve-JarPath([string]$JarPath) {
    if ($JarPath) { $JarPath = [IO.Path]::GetFullPath($JarPath) }
    elseif (Test-Path -LiteralPath $Script:ReleaseJar) { $JarPath = $Script:ReleaseJar }
    else { $JarPath = $Script:DevJar }
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "找不到程序包 $JarPath。源码目录请双击 启动.cmd 自动打包，或运行 aitest.ps1 build。" }
    return $JarPath
}

function Get-BackendState($Settings) {
    $state = Get-AiTestState $Settings
    $process = Get-AiTestManagedProcess $state
    $healthy = $false
    if ($process -and $state.baseUrl) {
        try { $healthy = (Invoke-RestMethod -Uri "$($state.baseUrl)/actuator/health" -TimeoutSec 2 -NoProxy).status -eq 'UP' } catch { }
    }
    return [pscustomobject]@{ State=$state; Process=$process; Healthy=$healthy; Url=$(if ($state -and $state.baseUrl) { [string]$state.baseUrl } else { "http://127.0.0.1:$($Settings.Port)" }) }
}

function Invoke-Start {
    param([string]$InstanceDirectory = '', [string]$ConfigPath = '', [string]$JarPath = '', [int]$StartupTimeoutSeconds = 240)
    $InstanceDirectory = Resolve-InstanceDirectory $InstanceDirectory
    if (-not $ConfigPath) { Initialize-AiTestConfiguration -PackageRoot $Script:ProjectRoot -InstanceDirectory $InstanceDirectory }
    $settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory -ConfigPath $ConfigPath
    if (Test-Path -LiteralPath (Join-Path $settings.RunDirectory 'restore-incomplete.json')) { throw '上一次恢复没有完成。请查看 run\restore-incomplete.json，并恢复到一个新的空实例后再启动。' }
    $previous = Get-AiTestState $settings
    $existing = Get-AiTestManagedProcess $previous
    if ($existing) { Write-Output "实例已在运行（PID $($existing.Id)）：$($previous.baseUrl)"; return }
    $JarPath = Resolve-JarPath $JarPath
    $java = Get-AiTestJavaOrInstall ([string]$settings.Config.javaHome)
    $null = New-Item -ItemType Directory -Path $settings.RunDirectory,$settings.LogDirectory,$settings.Storage -Force
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Parse($settings.Bind), $settings.Port)
    try { $listener.Start() } catch { throw "端口 $($settings.Port) 已被其他程序占用，没有结束任何进程。请改 config.json 里的 port，或先关掉占用端口的程序。" } finally { $listener.Stop() }
    $token = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $environment = Get-AiTestEnvironment -Settings $settings -ShutdownToken $token
    $probeHost = switch ($settings.Bind) { '0.0.0.0' { '127.0.0.1' }; '::' { '::1' }; default { $settings.Bind } }
    if ($probeHost.Contains(':')) { $probeHost = '[' + $probeHost + ']' }
    $baseUrl = "http://${probeHost}:$($settings.Port)"
    $identity = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
    $stdout = Join-Path $settings.LogDirectory "application-$identity.log"
    $stderr = Join-Path $settings.LogDirectory "application-$identity.err.log"
    $arguments = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Xms128m', "-Xmx$($settings.Config.runtime.heapMiB)m", '-jar', ('"' + $JarPath + '"'),
        "--aitest.execution.business-connections=$($settings.Config.runtime.businessConnections)")
    $application = Start-DetachedProcess -FilePath $java -ArgumentList $arguments -Environment $environment -WorkingDirectory $Script:ProjectRoot -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $state = @{pid=$application.Id; startedAt=$application.StartTime.ToUniversalTime().ToString('O'); baseUrl=$baseUrl; token=$token; jar=$JarPath;
        configPath=$settings.ConfigPath; stdout=$stdout; stderr=$stderr; status='STARTING'}
    $stateFile = Join-Path $settings.RunDirectory 'state.json'
    [IO.File]::WriteAllText($stateFile, ($state | ConvertTo-Json), $Script:Utf8)
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
        $ready = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            $application.Refresh()
            if ($application.HasExited) { throw "程序启动后退出了。请查看日志：$stdout 和 $stderr" }
            try {
                $health = Invoke-RestMethod -Uri "$baseUrl/actuator/health" -TimeoutSec 2 -NoProxy
                if ($health.status -eq 'UP') { $ready=$true; break }
            } catch { }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ready) { throw "启动超时（$StartupTimeoutSeconds 秒）。请查看日志：$stdout" }
        $keyBytes = Get-AiTestMasterKey $settings
        $keyHash = Get-AiTestKeyHash $keyBytes
        if ($previous -and $previous.masterKeyHash -and $previous.masterKeyHash -ne $keyHash) {
            throw '当前主密钥与上次运行的实例不一致。请从备份恢复 .master-key，或设置与原密钥一致的 AI_TEST_MASTER_KEY；没有覆盖旧的密钥指纹。'
        }
        if (-not $state.masterKeyHash) { $state.masterKeyHash = $keyHash }
        [Array]::Clear($keyBytes)
        $state.status='RUNNING'
        [IO.File]::WriteAllText($stateFile, ($state | ConvertTo-Json), $Script:Utf8)
        Write-Output "AI-Test-Platform 已就绪：$baseUrl（PID $($application.Id)）"
        Write-Output "日志：$stdout"
    } catch {
        if (Get-AiTestManagedProcess $state) { Stop-Process -Id $application.Id -Force -ErrorAction SilentlyContinue }
        throw
    }
}

function Invoke-Stop {
    param([string]$InstanceDirectory = '', [string]$ConfigPath = '', [switch]$Force, [int]$TimeoutSeconds = 90)
    $settings = Read-AiTestConfiguration -InstanceDirectory (Resolve-InstanceDirectory $InstanceDirectory) -ConfigPath $ConfigPath
    $state = Get-AiTestState $settings
    $application = Get-AiTestManagedProcess $state
    if (-not $application) { Write-Output '这个实例没有正在运行的后端。'; return }
    if ($Force) {
        $application.Kill($true)
    } else {
        try {
            $null = Invoke-RestMethod -Method Post -Uri "$($state.baseUrl)/internal/lifecycle/stop" -Headers @{'X-AITest-Shutdown-Token'=$state.token} -TimeoutSec 10 -NoProxy
        } catch { throw '优雅停止请求失败。请查看实例日志；确认是本实例的进程后可加 -Force 强制结束。' }
    }
    if (-not $application.WaitForExit($TimeoutSeconds * 1000)) { throw '进程还没有退出。请查看日志，或对同一实例加 -Force 再停一次。' }
    $state.status='STOPPED'; $state.stoppedAt=[DateTime]::UtcNow.ToString('O'); $state.Remove('token')
    [IO.File]::WriteAllText((Join-Path $settings.RunDirectory 'state.json'), ($state | ConvertTo-Json), $Script:Utf8)
    Write-Output "已停止后端（PID $($application.Id)）。"
}

function Invoke-Check {
    param([string]$InstanceDirectory = '', [string]$ConfigPath = '', [switch]$TestModel)
    $settings = Read-AiTestConfiguration -InstanceDirectory (Resolve-InstanceDirectory $InstanceDirectory) -ConfigPath $ConfigPath
    $java = Find-AiTestJava ([string]$settings.Config.javaHome)
    Write-Output "Java：$($Script:ToolSources.java)"
    if ($settings.Config.mysqlHome -and ([string]$settings.Config.mysqlHome).StartsWith($Script:ToolsRoot, [StringComparison]::OrdinalIgnoreCase) -and -not (Test-VcRuntime)) { Write-Warning '本机缺少微软 VC++ 运行库，自带的 MySQL 无法启动；双击 启动.cmd 会自动安装。' }
    $version = Invoke-AiTestSql -Settings $settings -Sql 'SELECT VERSION();'
    if ($version -notmatch '^8\.4\.') { throw "需要 MySQL 8.4，实际连到的是 $version。" }
    Write-Output "MySQL：$version（库 $($settings.DatabaseName) @ $($settings.DatabaseHost):$($settings.DatabasePort)）"
    $null = New-Item -ItemType Directory -Path $settings.Storage -Force
    $probe = Join-Path $settings.Storage ('.write-check-' + [guid]::NewGuid().ToString('N'))
    try { [IO.File]::WriteAllText($probe, 'storage-check') } finally { if (Test-Path -LiteralPath $probe) { Remove-Item -LiteralPath $probe } }
    Write-Output "存储目录可写：$($settings.Storage)"
    if (Test-PlaywrightBrowsersPresent -BrowsersPath $settings.Browsers -JavaHome ([string]$settings.Config.javaHome)) { Write-Output "Chromium 已安装：$($settings.Browsers)" }
    else { Write-Warning "配置的目录里没有本版本需要的 Chromium：$($settings.Browsers)。执行 UI/PDF 前请运行 aitest.ps1 install-browsers（启动.cmd 会自动装）。" }
    $modelConfigured = $settings.Config.model.baseUrl -and $settings.Config.model.apiKey -and $settings.Config.model.modelName
    if (-not $modelConfigured) { Write-Output '配置文件里的模型信息不完整：手工功能都能用；界面「模型设置」里保存的模型同样有效。' }
    if ($TestModel) {
        $state = Get-AiTestState $settings
        if (-not (Get-AiTestManagedProcess $state)) { throw '请先启动实例，再测试它实际生效的模型配置。' }
        $headers = New-AiTestApiHeaders -Settings $settings -BaseUrl $state.baseUrl
        try {
            $result = Invoke-RestMethod -Method Post -Uri "$($state.baseUrl)/api/settings/model/test" -Headers $headers -TimeoutSec 120 -MaximumRedirection 0 -NoProxy
            Write-Output ($result | ConvertTo-Json -Depth 5)
        } finally {
            if ($settings.Config.security.enabled) {
                try { $null = Invoke-RestMethod -Method Post -Uri "$($state.baseUrl)/api/auth/logout" -Headers $headers -TimeoutSec 15 -MaximumRedirection 0 -NoProxy }
                catch { Write-Warning '临时运维会话没能主动退出，它会按超时自动失效。' }
            }
        }
    }
}

# Playwright 命令行：发行包用 app.jar；源码目录没有 JAR 时用 Maven 算出的 classpath 直接调 Playwright 自己的 CLI 类。
function Get-PlaywrightCliCommand([string]$JarPath, [string]$JavaHome = '') {
    $java = Get-AiTestJavaOrInstall $JavaHome
    if ($JarPath -or (Test-Path -LiteralPath $Script:ReleaseJar) -or (Test-Path -LiteralPath $Script:DevJar)) {
        return @{ Java = $java; Arguments = @('-jar', (Resolve-JarPath $JarPath), '--playwright-cli') }
    }
    if (-not $Script:IsSourceTree) { throw '找不到 app.jar。' }
    $classpathFile = Join-Path $Script:RuntimeRoot 'playwright-classpath.txt'
    $null = New-Item -ItemType Directory -Path $Script:RuntimeRoot -Force
    $pom = Get-Item -LiteralPath (Join-Path $Script:ProjectRoot 'backend/pom.xml')
    if (-not (Test-Path -LiteralPath $classpathFile) -or (Get-Item -LiteralPath $classpathFile).LastWriteTime -lt $pom.LastWriteTime) {
        if ((Invoke-Maven -B -ntp -q dependency:build-classpath "-Dmdep.outputFile=$classpathFile" -DincludeScope=runtime) -ne 0) { throw '无法生成 Playwright 的 classpath。' }
    }
    # classpath 有近 300 项，直接放命令行会超过 Windows 的长度上限；写成 Java 参数文件（@file）传入。
    $argumentFile = Join-Path $Script:RuntimeRoot 'playwright-cli.args'
    $classpath = (Get-Content -Raw -LiteralPath $classpathFile).Trim().Replace('\', '/').Replace('"', '\"')
    [IO.File]::WriteAllText($argumentFile, "-cp`n`"$classpath`"`ncom.microsoft.playwright.CLI`n", $Script:Utf8)
    return @{ Java = $java; Arguments = @("@$argumentFile") }
}

# 问 Playwright 它需要哪些内核目录（版本号由 Playwright 自己决定），再检查这些目录是否齐全。
function Get-PlaywrightInstallLocations([string]$BrowsersPath, [string[]]$Browsers, [string]$JarPath = '', [string]$JavaHome = '') {
    $cli = Get-PlaywrightCliCommand -JarPath $JarPath -JavaHome $JavaHome
    $info = [Diagnostics.ProcessStartInfo]::new($cli.Java)
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    foreach ($argument in $cli.Arguments + @('install', '--dry-run') + $Browsers) { $info.ArgumentList.Add($argument) }
    $info.Environment['PLAYWRIGHT_BROWSERS_PATH'] = $BrowsersPath
    $process = [Diagnostics.Process]::Start($info)
    try { $output = $process.StandardOutput.ReadToEnd(); $null = $process.StandardError.ReadToEnd(); $process.WaitForExit() } finally { $process.Dispose() }
    return @([regex]::Matches($output, 'Install location:\s*(.+?)\s*(\r?\n|$)') | ForEach-Object { $_.Groups[1].Value.Trim() })
}
function Test-PlaywrightBrowsersPresent([string]$BrowsersPath, [string]$JarPath = '', [string]$JavaHome = '') {
    if (-not $BrowsersPath -or -not (Test-Path -LiteralPath $BrowsersPath)) { return $false }
    $locations = Get-PlaywrightInstallLocations -BrowsersPath $BrowsersPath -Browsers @('chromium') -JarPath $JarPath -JavaHome $JavaHome
    if ($locations.Count -eq 0) { return $false }
    foreach ($location in $locations) { if (-not (Test-Path -LiteralPath (Join-Path $location 'INSTALLATION_COMPLETE'))) { return $false } }
    return $true
}
# 找已有的 Chromium：config.json 的 paths.browsers → PLAYWRIGHT_BROWSERS_PATH → .tools 自带（目录或压缩包）→ Playwright 默认目录。
function Find-PlaywrightBrowsers([string]$Configured, [string]$JarPath = '', [string]$JavaHome = '') {
    $bundledDirectory = Get-DefaultBrowsersDirectory
    $bundledArchive = "$bundledDirectory-chromium-win64.zip"
    if (-not (Test-Path -LiteralPath $bundledDirectory) -and (Test-Path -LiteralPath $bundledArchive)) {
        # 离线包的 .tools/manifest.json 记录了每个自带文件的指纹，解压前先核对，避免下载不完整的包解压出半个内核。
        $manifestFile = Join-Path $Script:ToolsRoot 'manifest.json'
        if (Test-Path -LiteralPath $manifestFile) {
            $entry = (Get-Content -Raw -LiteralPath $manifestFile | ConvertFrom-Json).files | Where-Object name -eq ([IO.Path]::GetFileName($bundledArchive))
            if ($entry -and (Get-FileHash -LiteralPath $bundledArchive -Algorithm SHA256).Hash -ine $entry.sha256) { throw "自带的 $([IO.Path]::GetFileName($bundledArchive)) 与清单指纹不符，文件可能损坏或下载不完整，请重新获取离线包。" }
        }
        Write-Note "解压自带的 Chromium 内核 $([IO.Path]::GetFileName($bundledArchive))……"
        Expand-Archive -LiteralPath $bundledArchive -DestinationPath $bundledDirectory -Force
    }
    $candidates = @(@($Configured, 'config.json 的 paths.browsers'), @($env:PLAYWRIGHT_BROWSERS_PATH, '环境变量 PLAYWRIGHT_BROWSERS_PATH'), @($bundledDirectory, '.tools 自带'), @((Join-Path $env:LOCALAPPDATA 'ms-playwright'), 'Playwright 默认目录'))
    foreach ($candidate in $candidates) {
        if (-not $candidate[0]) { continue }
        if (Test-PlaywrightBrowsersPresent -BrowsersPath $candidate[0] -JarPath $JarPath -JavaHome $JavaHome) { $Script:ToolSources.browsers = "$($candidate[1])：$($candidate[0])"; return [IO.Path]::GetFullPath($candidate[0]) }
    }
    return $null
}

function Invoke-InstallBrowsers {
    param([ValidateSet('chromium','firefox','webkit')][string[]]$Browsers = @('chromium'), [string]$InstanceDirectory = '', [string]$ConfigPath = '', [string]$JarPath = '', [switch]$DryRun)
    $InstanceDirectory = Resolve-InstanceDirectory $InstanceDirectory
    if (-not $ConfigPath) { Initialize-AiTestConfiguration -PackageRoot $Script:ProjectRoot -InstanceDirectory $InstanceDirectory }
    $settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory -ConfigPath $ConfigPath
    if ($Script:Offline -and -not $DryRun) { throw "离线模式：不能联网安装浏览器内核。请把浏览器内核目录复制到 $($settings.Browsers)，或在 config.json 的 paths.browsers 里指定已有的目录。" }
    $cli = Get-PlaywrightCliCommand -JarPath $JarPath -JavaHome ([string]$settings.Config.javaHome)
    $info = [Diagnostics.ProcessStartInfo]::new($cli.Java)
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    foreach ($argument in $cli.Arguments + @('install') + $(if ($DryRun) { @('--dry-run') } else { @() }) + $Browsers) { $info.ArgumentList.Add($argument) }
    $info.Environment['PLAYWRIGHT_BROWSERS_PATH']=$settings.Browsers
    # 国内网络：先走 npmmirror 的 Playwright 镜像，失败时 Playwright 会自动回退到官方源。
    if (-not $info.Environment.ContainsKey('PLAYWRIGHT_DOWNLOAD_HOST')) { $info.Environment['PLAYWRIGHT_DOWNLOAD_HOST'] = 'https://npmmirror.com/mirrors/playwright' }
    foreach ($name in @($info.Environment.Keys | Where-Object { $_ -like 'AI_TEST_*' -or $_ -like 'SPRING_*' -or $_ -in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD') })) { $null = $info.Environment.Remove($name) }
    $process = [Diagnostics.Process]::Start($info)
    try {
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw '浏览器内核安装失败，请看上面 Playwright 的输出（通常是网络问题，稍后重试即可）。' }
    } finally { $process.Dispose() }
    Write-Output "浏览器内核目录：$($settings.Browsers)"
}

function Invoke-Backup {
    param([string]$InstanceDirectory = '', [string]$ConfigPath = '', [string]$DestinationDirectory = '', [switch]$LeaveStopped)
    $settings = Read-AiTestConfiguration -InstanceDirectory (Resolve-InstanceDirectory $InstanceDirectory) -ConfigPath $ConfigPath
    $state = Get-AiTestState $settings
    $wasRunning = $null -ne (Get-AiTestManagedProcess $state)
    $key = Get-AiTestMasterKey $settings
    if ($state -and $state.masterKeyHash -and $state.masterKeyHash -ne (Get-AiTestKeyHash $key)) { throw '当前主密钥与上次运行的实例不一致，没有生成备份。' }
    if (-not $DestinationDirectory) { $DestinationDirectory = Join-Path $settings.Instance 'backups' }
    $destination = [IO.Path]::GetFullPath($DestinationDirectory)
    $storagePrefix = $settings.Storage.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if ($destination -eq $settings.Storage -or $destination.StartsWith($storagePrefix, [StringComparison]::OrdinalIgnoreCase)) { throw '备份目录不能放在正在使用的存储目录里面。' }
    $backupRoot = Join-Path $destination ('backup-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
    if ($wasRunning) { Invoke-Stop -InstanceDirectory $settings.Instance -ConfigPath $settings.ConfigPath }
    try {
        if (Get-AiTestManagedProcess (Get-AiTestState $settings)) { throw '备份数据库和文件前必须先停止应用。' }
        $version = Invoke-AiTestSql $settings 'SELECT VERSION();'
        if ($version -notmatch '^8\.4\.') { throw '备份需要 MySQL 8.4。' }
        $null = New-Item -ItemType Directory -Path (Join-Path $backupRoot 'storage') -Force
        $dumpFile = Join-Path $backupRoot 'database.sql'
        $null = Invoke-AiTestMySqlTool -Settings $settings -Tool mysqldump -Arguments @('--single-transaction','--skip-lock-tables','--skip-add-locks','--routines','--events','--triggers','--hex-blob','--set-gtid-purged=OFF','--no-tablespaces',$settings.DatabaseName) -OutputFile $dumpFile
        foreach ($entry in Get-ChildItem -LiteralPath $settings.Storage -Force -Recurse) {
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '存储目录里有链接文件，请先处理掉再做可移植备份。' }
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
        [IO.File]::WriteAllText((Join-Path $backupRoot 'manifest.json'), ($manifest | ConvertTo-Json -Depth 10), $Script:Utf8)
        Write-Output "备份完成：$backupRoot"
        Write-Output '备份里包含解密密钥和数据库口令，请像保管数据库备份一样保管它。'
    } finally {
        [Array]::Clear($key)
        if ($wasRunning -and -not $LeaveStopped) { Invoke-Start -InstanceDirectory $settings.Instance -ConfigPath $settings.ConfigPath -JarPath $state.jar }
    }
}

function Get-AiTestReleaseVersion([string]$PackageRoot) {
    $releaseFile = Join-Path $PackageRoot 'release.json'
    if (-not (Test-Path -LiteralPath $releaseFile -PathType Leaf)) { return '未知版本' }
    try {
        $release = Get-Content -Raw -LiteralPath $releaseFile | ConvertFrom-Json
        if ($release.version) { return [string]$release.version }
    } catch { }
    return '未知版本'
}

function Invoke-AiTestPackageScript([string]$PackageRoot, [string[]]$Arguments) {
    $script = Join-Path $PackageRoot 'scripts/aitest.ps1'
    if (-not (Test-Path -LiteralPath $script -PathType Leaf)) { throw "找不到旧安装目录的脚本：$script" }
    $pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
    if (-not $pwsh) { $pwsh = Join-Path $PSHOME 'pwsh.exe' }
    & $pwsh -NoProfile -ExecutionPolicy Bypass -File $script @Arguments | ForEach-Object { Write-Output $_ }
    if ($LASTEXITCODE -ne 0) { throw "旧安装目录的命令失败（退出码 $LASTEXITCODE）：$($Arguments[0])" }
}

function Copy-AiTestMissingTree([string]$Source, [string]$Destination) {
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) { return }
    $null = New-Item -ItemType Directory -Path $Destination -Force
    foreach ($entry in Get-ChildItem -LiteralPath $Source -Force) {
        $target = Join-Path $Destination $entry.Name
        if ($entry.PSIsContainer) {
            Copy-AiTestMissingTree -Source $entry.FullName -Destination $target
        } elseif (-not (Test-Path -LiteralPath $target)) {
            Copy-Item -LiteralPath $entry.FullName -Destination $target -Force
        }
    }
}

function Invoke-Upgrade {
    param([Parameter(Mandatory)][string]$Target, [switch]$Yes)
    if ($Script:IsSourceTree -and -not (Test-Path -LiteralPath $Script:ReleaseJar -PathType Leaf)) {
        throw 'upgrade 必须从包含 app.jar 的新发行包运行，不能直接从源码目录升级。'
    }
    $targetRoot = [IO.Path]::GetFullPath($Target)
    $currentRoot = [IO.Path]::GetFullPath($Script:ProjectRoot)
    if (-not (Test-Path -LiteralPath $targetRoot -PathType Container)) { throw "旧安装目录不存在：$targetRoot" }
    if ($targetRoot.TrimEnd('\','/') -eq $currentRoot.TrimEnd('\','/')) { throw '旧安装目录不能是当前新发行包目录。' }
    foreach ($required in @('app.jar', 'scripts/aitest.ps1', 'instance')) {
        if (-not (Test-Path -LiteralPath (Join-Path $targetRoot $required))) { throw "旧安装目录缺少 $required，不能升级。" }
    }
    $oldVersion = Get-AiTestReleaseVersion $targetRoot
    $newVersion = Get-AiTestReleaseVersion $currentRoot
    Write-Output "旧版本：$oldVersion"
    Write-Output "新版本：$newVersion"
    if (-not $Yes) {
        $answer = Read-Host '升级会先备份并停止旧实例，然后修改旧文件夹。确认继续请输入 YES'
        if ($answer -cne 'YES') { throw '已取消升级，没有修改旧安装目录。' }
    }

    Write-Step '1/6 备份旧实例'
    Invoke-AiTestPackageScript -PackageRoot $targetRoot -Arguments @('backup', '-InstanceDirectory', $targetRoot, '-LeaveStopped')
    Write-Ok '逻辑备份完成。'
    Write-Step '2/6 停止旧实例和数据库'
    Invoke-AiTestPackageScript -PackageRoot $targetRoot -Arguments @('down', '-InstanceDirectory', $targetRoot)
    Write-Ok '旧实例已停止。'

    $parent = Split-Path -Parent $targetRoot
    $leaf = Split-Path -Leaf $targetRoot
    $rollback = Join-Path $parent ($leaf + '-升级前-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss'))
    if (Test-Path -LiteralPath $rollback) { $rollback += '-' + [guid]::NewGuid().ToString('N').Substring(0, 8) }
    Write-Step '3/6 创建升级前回退副本'
    Copy-Item -LiteralPath $targetRoot -Destination $rollback -Recurse -Force
    Write-Ok "回退副本：$rollback"
    if (-not (Test-Path -LiteralPath (Join-Path $targetRoot '.runtime/mysql/data') -PathType Container)) {
        Write-Warn '旧实例的 MySQL 数据目录不在安装文件夹内，升级前副本不包含数据库；逻辑备份仍然已保存。'
    }

    Write-Step '4/6 替换程序文件'
    foreach ($directory in @('scripts', 'docs', 'database', 'licenses')) {
        $source = Join-Path $currentRoot $directory
        if (-not (Test-Path -LiteralPath $source -PathType Container)) { continue }
        $destination = Join-Path $targetRoot $directory
        if (Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination -Recurse -Force }
        Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
    }
    foreach ($name in @('app.jar', 'README.md', 'NOTICE.md', 'release.json', 'SHA256SUMS', 'config.example.json', 'source.zip')) {
        $source = Join-Path $currentRoot $name
        if (Test-Path -LiteralPath $source -PathType Leaf) { Copy-Item -LiteralPath $source -Destination (Join-Path $targetRoot $name) -Force }
    }
    foreach ($cmd in Get-ChildItem -LiteralPath $currentRoot -Filter '*.cmd' -File) { Copy-Item -LiteralPath $cmd.FullName -Destination (Join-Path $targetRoot $cmd.Name) -Force }
    Copy-AiTestMissingTree -Source (Join-Path $currentRoot '.tools') -Destination (Join-Path $targetRoot '.tools')
    Write-Ok '已保留 instance、data、.runtime，并补齐未覆盖的 .tools 子目录。'

    Write-Step '5/6 启动新版本'
    Invoke-AiTestPackageScript -PackageRoot $targetRoot -Arguments @('up', '-InstanceDirectory', $targetRoot, '-NoBrowser')
    $settings = Read-AiTestConfiguration -InstanceDirectory $targetRoot
    $backend = Get-BackendState $settings
    if (-not $backend.Process -or -not $backend.Healthy) {
        $logTail = @()
        if ($backend.State.stdout -and (Test-Path -LiteralPath $backend.State.stdout)) { $logTail += Get-Content -LiteralPath $backend.State.stdout -Tail 30 }
        if ($backend.State.stderr -and (Test-Path -LiteralPath $backend.State.stderr)) { $logTail += Get-Content -LiteralPath $backend.State.stderr -Tail 30 }
        throw "升级后健康检查未通过。请查看 $($backend.State.stdout)；日志末尾：`n$($logTail -join "`n")"
    }
    Write-Step '6/6 完成'
    Write-Output "升级完成：$targetRoot（$newVersion）"
    Write-Output "如需回退：停止实例，把旧目录改名，再把 $rollback 改回 $leaf。"
}

function Invoke-Restore {
    param([Parameter(Mandatory)][string]$BackupDirectory, [Parameter(Mandatory)][string]$InstanceDirectory, [string]$ConfigPath = '')
    $settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory -ConfigPath $ConfigPath
    if (Get-AiTestManagedProcess (Get-AiTestState $settings)) { throw '请先停止目标实例再恢复。' }
    $incompleteFile = Join-Path $settings.RunDirectory 'restore-incomplete.json'
    if (Test-Path -LiteralPath $incompleteFile) { throw '这个目标实例有一次未完成的恢复。请换一个新的空库和空目录；未完成的现场已保留供检查。' }
    $backupRoot = [IO.Path]::GetFullPath($BackupDirectory)
    $manifest = Get-Content -Raw -LiteralPath (Join-Path $backupRoot 'manifest.json') | ConvertFrom-Json -AsHashtable
    if ($manifest.formatVersion -ne 'aitest.backup/v1' -or -not $manifest.files) { throw '备份不完整或格式不支持。' }
    if ((Test-Path -LiteralPath $settings.Storage) -and @(Get-ChildItem -LiteralPath $settings.Storage -Force).Count -ne 0) { throw '恢复只接受空的目标存储目录。' }
    $paths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $resolvedPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($file in $manifest.files) {
        if (-not $paths.Add([string]$file.path)) { throw '备份清单里有重复路径。' }
        if ($file.path -notin @('database.sql','configuration.json') -and -not $file.path.StartsWith('storage/')) { throw '备份清单里有意料之外的文件。' }
        $source = Resolve-AiTestArchivePath -Root $backupRoot -RelativePath $file.path
        if (-not $resolvedPaths.Add($source)) { throw '备份里同一个文件出现了多个名字。' }
        $entry = Get-Item -LiteralPath $source -Force
        if ($entry.PSIsContainer -or $entry.Length -ne $file.bytes -or (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $file.sha256) { throw "备份文件校验失败：$($file.path)" }
        $current = $entry
        while ($current -and $current.FullName -ne $backupRoot) {
            if ($current.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '备份里不接受链接。' }
            $current = if ($current -is [IO.FileInfo]) { $current.Directory } else { $current.Parent }
        }
    }
    foreach ($required in @('database.sql','configuration.json','storage/.master-key')) { if (-not $paths.Contains($required)) { throw "备份缺少必需文件：$required" } }
    $key = [IO.File]::ReadAllBytes((Join-Path $backupRoot 'storage/.master-key'))
    try {
        if ($key.Length -ne 32 -or (Get-AiTestKeyHash $key) -ne $manifest.masterKeyHash) { throw '备份里的主密钥与清单不一致。' }
        if ($env:AI_TEST_MASTER_KEY -and (Get-AiTestKeyHash ([Convert]::FromBase64String($env:AI_TEST_MASTER_KEY))) -ne $manifest.masterKeyHash) { throw '恢复前请清除 AI_TEST_MASTER_KEY，或让它与备份密钥一致。' }
    } finally { [Array]::Clear($key) }
    $count = Invoke-AiTestSql $settings 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();'
    if ($count -ne '0') { throw '恢复只接受已存在的空数据库，没有改动任何表。' }
    $version = Invoke-AiTestSql $settings 'SELECT VERSION();'
    if ($version -notmatch '^8\.4\.') { throw '恢复需要 MySQL 8.4。' }
    $dump = Join-Path $backupRoot 'database.sql'
    $reader = [IO.File]::OpenText($dump)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            if ($line -match '^\s*(USE\s|(?:CREATE|DROP|ALTER)\s+DATABASE\s)') { throw '这个导出文件会切换数据库，不是平台的可移植实例备份。' }
        }
    } finally { $reader.Dispose() }
    $marker = @{startedAt=[DateTime]::UtcNow.ToString('O'); backup=$backupRoot; targetDatabase=$settings.DatabaseName; storage=$settings.Storage; phase='COPY_FILES'}
    [IO.File]::WriteAllText($incompleteFile, ($marker | ConvertTo-Json), $Script:Utf8)
    try {
        $null = New-Item -ItemType Directory -Path $settings.Storage -Force
        foreach ($file in $manifest.files) {
            if (-not $file.path.StartsWith('storage/')) { continue }
            $source = Resolve-AiTestArchivePath -Root $backupRoot -RelativePath $file.path
            $target = Resolve-AiTestArchivePath -Root $settings.Storage -RelativePath $file.path.Substring(8)
            $null = New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force
            [IO.File]::Copy($source, $target, $false)
        }
        $marker.phase='IMPORT_DATABASE'
        [IO.File]::WriteAllText($incompleteFile, ($marker | ConvertTo-Json), $Script:Utf8)
        $null = Invoke-AiTestMySqlTool -Settings $settings -Tool mysql -Arguments @('--binary-mode',$settings.DatabaseName) -InputFile $dump
        $migration = Invoke-AiTestSql $settings 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;'
        if ($migration -ne [string]$manifest.schemaVersion) { throw '恢复后的数据库迁移版本与备份不一致。' }
        $referencePath = Join-Path $settings.Instance ('restored-configuration-' + [guid]::NewGuid().ToString('N') + '.json')
        [IO.File]::Copy((Join-Path $backupRoot 'configuration.json'), $referencePath, $false)
        Remove-Item -LiteralPath $incompleteFile -Force
    } catch {
        throw "恢复没有完成。现场已保留，$incompleteFile 会阻止启动。请查看 MySQL 日志、修复原因后，用新的空目标重新恢复。$($_.Exception.Message)"
    }
    Write-Output "已把数据库和受管文件恢复到空实例：$($settings.Instance)"
    Write-Output "目标实例的连接、端口和路径配置保持不变；原配置另存为：$referencePath"
}

# ============================================================ 一键工作流 ============================================================

function Get-ViteState {
    if (-not (Test-Path -LiteralPath $Script:ViteStateFile)) { return $null }
    $state = Get-Content -Raw -LiteralPath $Script:ViteStateFile | ConvertFrom-Json -AsHashtable
    $process = if ($state.pid) { Get-Process -Id ([int]$state.pid) -ErrorAction SilentlyContinue } else { $null }
    return [pscustomobject]@{ Port=[int]$state.port; Url=[string]$state.url; Process=$process; Log=[string]$state.log }
}

function Build-DevJar {
    Invoke-Pnpm install --frozen-lockfile
    Invoke-Pnpm build
    if ((Invoke-Maven -B -ntp -Pdistribution -DskipTests clean package) -ne 0) { throw '后端打包失败，请看上面 Maven 的输出。' }
}

# 发行包：config.json 已指向外部数据库时不启动项目 MySQL；其余情况（源码目录、或还没配置）都用项目自带的 MySQL。
# 用项目自带的 MySQL 实例，还是 config.json 里填的外部数据库？
# 没有 config.json → 自带；config.json 指向本机且端口等于自带实例的端口（默认 3307）→ 自带；其余情况视为外部数据库，不启动自带实例。
function Test-UseProjectMysql([string]$InstanceDirectory) {
    $configFile = Join-Path $InstanceDirectory 'config.json'
    if (-not (Test-Path -LiteralPath $configFile)) { return $true }
    $projectPort = 3307
    if (Test-Path -LiteralPath $Script:MysqlConnectionFile) { $projectPort = [int](Get-Content -Raw -LiteralPath $Script:MysqlConnectionFile | ConvertFrom-Json).port }
    try {
        $config = Get-Content -Raw -LiteralPath $configFile | ConvertFrom-Json -AsHashtable
        $uri = [uri]([string]$config.database.url).Substring(5)
        $port = if ($uri.Port -gt 0) { $uri.Port } else { 3306 }
        return ($uri.Host -in @('127.0.0.1', 'localhost', '::1')) -and ($port -eq $projectPort)
    } catch { return $true }
}

function Invoke-Up {
    param([string]$InstanceDirectory = '', [switch]$Dev, [switch]$Build, [switch]$NoBrowser)
    $InstanceDirectory = Resolve-InstanceDirectory $InstanceDirectory
    $useProjectMysql = Test-UseProjectMysql $InstanceDirectory
    $configFile = Join-Path $InstanceDirectory 'config.json'
    $configuredJavaHome = ''; $configuredMysqlHome = ''
    if (Test-Path -LiteralPath $configFile) {
        $existingConfig = Get-Content -Raw -LiteralPath $configFile | ConvertFrom-Json -AsHashtable
        $configuredJavaHome = [string]$existingConfig.javaHome; $configuredMysqlHome = [string]$existingConfig.mysqlHome
    }
    if ($Script:Offline) { Write-Note '离线模式：只使用本机和 .tools 里已有的组件，不联网下载。' }
    Write-Step '1/6 Java 21'
    $java = Get-AiTestJavaOrInstall $configuredJavaHome
    Write-Ok $Script:ToolSources.java

    Write-Step '2/6 MySQL 8.4'
    if ($useProjectMysql) {
        $mysqlArguments = @{}
        if ($configuredMysqlHome -and -not (Test-Path -LiteralPath $Script:MysqlConnectionFile)) { $mysqlArguments.MySqlHome = $configuredMysqlHome }
        Invoke-Mysql @mysqlArguments | ForEach-Object { Write-Ok $_ }
        if ($Script:ToolSources.mysql) { Write-Note $Script:ToolSources.mysql }
    } else { Write-Ok '使用 config.json 里配置的数据库'; $Script:ToolSources.mysql = 'config.json 里配置的外部数据库' }

    Write-Step '3/6 实例配置'
    Initialize-AiTestConfiguration -PackageRoot $Script:ProjectRoot -InstanceDirectory $InstanceDirectory | ForEach-Object { Write-Ok $_ }
    $writeBack = @{ javaHome = (Split-Path -Parent (Split-Path -Parent $java)) }
    if ($useProjectMysql -and (Test-Path -LiteralPath $Script:MysqlConnectionFile)) { $writeBack.mysqlHome = [string](Get-Content -Raw -LiteralPath $Script:MysqlConnectionFile | ConvertFrom-Json).home }
    Update-AiTestConfiguration -ConfigPath $configFile -Values $writeBack
    $settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory
    Write-Ok "配置文件：$($settings.ConfigPath)"

    Write-Step '4/6 程序包'
    if (Test-Path -LiteralPath $Script:ReleaseJar) { $jar = $Script:ReleaseJar }
    elseif ($Script:IsSourceTree) {
        $jar = $Script:DevJar
        if ($Build -or -not (Test-Path -LiteralPath $jar)) {
            Write-Warn $(if ($Build) { '按要求重新打包（跳过测试），约需几分钟。' } else { '程序包不存在，首次自动打包（跳过测试），约需几分钟；Maven 首次还要下载依赖。' })
            Build-DevJar
        }
    } else { throw '这个目录既没有 app.jar，也不是源码目录。' }
    $jarAge = [DateTime]::Now - (Get-Item -LiteralPath $jar).LastWriteTime
    Write-Ok ("{0}（{1:N0} 分钟前生成）" -f $jar, $jarAge.TotalMinutes)

    Write-Step '5/6 Playwright 浏览器内核'
    $browsers = Find-PlaywrightBrowsers -Configured $settings.Browsers -JarPath $jar -JavaHome ([string]$settings.Config.javaHome)
    if ($browsers) {
        Write-Ok "Chromium 已就绪：$($Script:ToolSources.browsers)"
        if ($browsers -ne $settings.Browsers) { Update-AiTestConfiguration -ConfigPath $settings.ConfigPath -Values @{ 'paths.browsers' = $browsers }; $settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory }
    } elseif ($Script:Offline) {
        Write-Warn "离线模式：没有找到 Chromium 内核。UI 自动化和 PDF 功能不可用，其余功能正常。请把内核目录复制到 $($settings.Browsers)，或在 config.json 的 paths.browsers 里指定。"
    } else {
        Write-Warn 'Chromium 缺失，自动安装（约 400 MB，一次性）。'
        try { Invoke-InstallBrowsers -InstanceDirectory $InstanceDirectory -JarPath $jar | ForEach-Object { Write-Ok $_ }; $Script:ToolSources.browsers = "新安装：$($settings.Browsers)" }
        catch { Write-Warn "浏览器内核安装失败：$($_.Exception.Message)"; Write-Warn '不影响 UI 自动化和 PDF 之外的功能，稍后再双击 启动.cmd 会重试。' }
    }

    Write-Step '6/6 后端服务'
    $backend = Get-BackendState $settings
    if ($backend.Process) { Write-Ok "已在运行：$($backend.Url)（PID $($backend.Process.Id)）" }
    else {
        Invoke-Start -InstanceDirectory $InstanceDirectory -JarPath $jar | ForEach-Object { Write-Ok $_ }
        $backend = Get-BackendState $settings
    }
    $openUrl = $backend.Url

    if ($Dev) {
        if (-not $Script:IsSourceTree) { throw '-Dev 只能在源码目录使用。' }
        Write-Step '附加：Vite 前端热更新（端口 5173）'
        $vite = Get-ViteState
        if ($vite -and $vite.Process) { Write-Ok "已在运行：$($vite.Url)（PID $($vite.Process.Id)）" }
        else {
            if (Test-AiTestPortOpen '127.0.0.1' 5173) { throw '5173 端口已被其他进程占用，未启动 Vite。' }
            Invoke-Pnpm install --frozen-lockfile
            $devRoot = Join-Path $Script:RuntimeRoot 'dev'
            $null = New-Item -ItemType Directory -Path $devRoot -Force
            $log = Join-Path $devRoot 'vite.log'; $errorLog = Join-Path $devRoot 'vite.err.log'
            $vitePath = Join-Path $Script:Frontend 'node_modules/vite/bin/vite.js'
            if (-not (Test-Path -LiteralPath $vitePath)) { throw "找不到 $vitePath，前端依赖没有装好。" }
            $node = Join-Path (Get-AiTestNodeDirectory) 'node.exe'
            $process = Start-DetachedProcess -FilePath $node -ArgumentList @($vitePath, '--host', '127.0.0.1', '--port', '5173', '--strictPort') -WorkingDirectory $Script:Frontend `
                -RedirectStandardOutput $log -RedirectStandardError $errorLog -Environment @{ AI_TEST_API_URL = $backend.Url }
            $deadline = [DateTime]::UtcNow.AddSeconds(90)
            while ([DateTime]::UtcNow -lt $deadline -and -not (Test-AiTestPortOpen '127.0.0.1' 5173)) {
                if ($process.HasExited) { throw "Vite 已退出，查看 $errorLog" }
                Start-Sleep -Milliseconds 500
            }
            if (-not (Test-AiTestPortOpen '127.0.0.1' 5173)) { $process.Kill($true); throw "Vite 启动超时，查看 $log" }
            $state = @{pid=$process.Id; port=5173; url='http://127.0.0.1:5173'; log=$log; startedAt=[DateTime]::UtcNow.ToString('O')}
            [IO.File]::WriteAllText($Script:ViteStateFile, ($state | ConvertTo-Json), $Script:Utf8)
            Write-Ok "Vite 就绪：http://127.0.0.1:5173（PID $($process.Id)），/api 代理到 $($backend.Url)"
        }
        $openUrl = 'http://127.0.0.1:5173'
    }

    Write-Host ''
    Write-Host "✔ 平台已就绪：$openUrl" -ForegroundColor Green
    if (-not ($settings.Config.model.baseUrl -and $settings.Config.model.modelName)) { Write-Warn '模型未配置：手工功能全部可用；AI 生成/诊断请在网页右上角「模型设置」里填写 OpenAI 兼容服务。' }
    Write-Note '停止：双击 停止.cmd    状态：查看状态.cmd    日志：查看日志.cmd'
    if (-not $NoBrowser) { Start-Process $openUrl }
}

function Invoke-Down {
    param([string]$InstanceDirectory = '', [switch]$KeepMysql, [switch]$Force)
    $InstanceDirectory = Resolve-InstanceDirectory $InstanceDirectory
    Write-Step '1/3 Vite 前端'
    $vite = Get-ViteState
    if ($vite -and $vite.Process) {
        $vite.Process.Kill($true); $vite.Process.WaitForExit(10000) | Out-Null
        Write-Ok "已停止 Vite（PID $($vite.Process.Id)）"
    } else { Write-Ok '未运行' }
    if (Test-Path -LiteralPath $Script:ViteStateFile) { Remove-Item -LiteralPath $Script:ViteStateFile -Force }

    Write-Step '2/3 后端服务'
    if (Test-Path -LiteralPath (Join-Path $InstanceDirectory 'config.json')) {
        $settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory
        $backend = Get-BackendState $settings
        if ($backend.Process) {
            try { Invoke-Stop -InstanceDirectory $InstanceDirectory | ForEach-Object { Write-Ok $_ } }
            catch {
                if (-not $Force) { throw }
                Write-Warn "优雅停止失败，按 -Force 强制结束：$($_.Exception.Message)"
                Invoke-Stop -InstanceDirectory $InstanceDirectory -Force | ForEach-Object { Write-Ok $_ }
            }
        } else { Write-Ok '未运行' }
    } else { Write-Ok '尚无实例配置，跳过' }

    Write-Step '3/3 项目 MySQL'
    if ($KeepMysql) { Write-Ok '按要求保留运行' } else { Stop-ProjectMysql }

    Write-Host ''
    Write-Host '✔ 已全部停止。再次启动：双击 启动.cmd' -ForegroundColor Green
}

function Invoke-Status {
    param([string]$InstanceDirectory = '')
    $InstanceDirectory = Resolve-InstanceDirectory $InstanceDirectory
    function Show([string]$Name, [bool]$Up, [string]$Detail) {
        $mark = if ($Up) { '●' } else { '○' }; $color = if ($Up) { 'Green' } else { 'DarkGray' }
        Write-Host ("  {0} {1,-6} {2}" -f $mark, $Name, $Detail) -ForegroundColor $color
    }
    Write-Host 'AI-Test-Platform 本地状态' -ForegroundColor Cyan
    $mysql = Get-ProjectMysql
    if ($mysql) { Show 'MySQL' ($null -ne $mysql.Process -and $mysql.Listening) $(if ($mysql.Process) { "127.0.0.1:$($mysql.Port)  PID $($mysql.Process.Id)" } else { "未运行（端口 $($mysql.Port)）" }) }
    else { Show 'MySQL' $false '尚未初始化，双击 启动.cmd 会自动创建' }
    if (Test-Path -LiteralPath (Join-Path $InstanceDirectory 'config.json')) {
        $settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory
        $backend = Get-BackendState $settings
        Show '后端' ($null -ne $backend.Process) $(if ($backend.Process) { "$($backend.Url)  PID $($backend.Process.Id)  健康检查 $(if ($backend.Healthy) { '通过' } else { '未通过' })" } else { "未运行（将监听 $($backend.Url)）" })
        if ($backend.State -and $backend.State.stdout) { Write-Host "           日志 $($backend.State.stdout)" -ForegroundColor DarkGray }
        $model = $settings.Config.model
        Show '模型' ([bool]($model.baseUrl -and $model.modelName)) $(if ($model.baseUrl -and $model.modelName) { "$($model.modelName) @ $($model.baseUrl)" } else { '配置文件未填写；网页「模型设置」里保存的配置优先生效' })
    } else { Show '后端' $false '尚无实例配置' }
    $vite = Get-ViteState
    Show 'Vite' ($null -ne $vite -and $null -ne $vite.Process) $(if ($vite -and $vite.Process) { "$($vite.Url)  PID $($vite.Process.Id)" } else { '未运行（开发者用 aitest.ps1 up -Dev 启动热更新）' })
    Write-Host '环境（来源：路径）' -ForegroundColor Cyan
    $configuredJava = ''; $configuredBrowsers = ''
    if (Test-Path -LiteralPath (Join-Path $InstanceDirectory 'config.json')) { $configuredJava = [string]$settings.Config.javaHome; $configuredBrowsers = $settings.Browsers }
    try { $null = Find-AiTestJava $configuredJava; Write-Host "  Java      $($Script:ToolSources.java)" } catch { Write-Host '  Java      未找到 Java 21 或更高版本' -ForegroundColor Yellow }
    if ($mysql) { Write-Host "  MySQL     $(if ($mysql.Home.StartsWith($Script:ToolsRoot, [StringComparison]::OrdinalIgnoreCase)) { '.tools 自带' } else { '本机已安装' })：$($mysql.Home)" }
    elseif ($configuredJava -and -not (Test-UseProjectMysql $InstanceDirectory)) { Write-Host "  MySQL     config.json 里配置的外部数据库：$($settings.DatabaseHost):$($settings.DatabasePort)" }
    else { Write-Host '  MySQL     尚未准备' -ForegroundColor DarkGray }
    Write-Host "  VC++ 运行库 $(if (Test-VcRuntime) { '已安装' } else { '未安装（自带 MySQL 需要）' })"
    if ($configuredBrowsers) {
        $ready = $false
        try { $ready = Test-PlaywrightBrowsersPresent -BrowsersPath $configuredBrowsers -JavaHome $configuredJava } catch { }
        Write-Host "  Chromium  $(if ($ready) { '就绪' } else { '缺失' })：$configuredBrowsers" -ForegroundColor $(if ($ready) { 'Gray' } else { 'Yellow' })
    } else { Write-Host '  Chromium  尚未配置' -ForegroundColor DarkGray }
    Write-Host "  离线模式  $(if ($Script:Offline) { '开（AI_TEST_OFFLINE）' } else { '关' })"
}

function Invoke-Logs {
    param([string]$InstanceDirectory = '', [switch]$Errors, [switch]$Vite, [int]$Tail = 60)
    if ($Vite) {
        $state = Get-ViteState
        if (-not $state) { throw 'Vite 没有通过 aitest.ps1 up -Dev 启动，没有日志。' }
        $file = $state.Log
    } else {
        $settings = Read-AiTestConfiguration -InstanceDirectory (Resolve-InstanceDirectory $InstanceDirectory)
        $state = Get-AiTestState $settings
        if (-not $state) { throw '后端还没有启动过，没有日志。' }
        $file = if ($Errors) { $state.stderr } else { $state.stdout }
    }
    Write-Host "正在实时显示 $file（按 Ctrl+C 退出）" -ForegroundColor DarkGray
    Get-Content -LiteralPath $file -Tail $Tail -Wait -Encoding UTF8
}

# ============================================================ 构建与验证 ============================================================

function Invoke-Verify {
    param([string]$MavenSettings = '', [switch]$IncludeBrowser, [switch]$Full, [int]$Forks = 3)
    if (-not $Script:IsSourceTree) { throw 'verify 只能在源码目录使用。' }
    Invoke-Pnpm install --frozen-lockfile
    foreach ($command in @('lint','test:unit','build')) { Invoke-Pnpm $command }
    # 日常 verify 跳过 @Tag("slow") 的集成测试；-Full 加 nightly profile，跑与发行构建相同的全量套件。
    # -Forks 是 Failsafe 并行 JVM 数；MySQL 数据目录在机械硬盘上时用 1。
    Invoke-ResetTestDb -Forks $Forks
    $arguments = @('-B','-ntp','verify',"-Daitest.it.forks=$Forks")
    if ($Full) { $arguments += '-Pnightly' }
    if ($MavenSettings) { $arguments += @('-s', [IO.Path]::GetFullPath($MavenSettings)) }
    if ((Invoke-Maven @arguments) -ne 0) { throw '后端验证失败，请看上面 Maven 的输出。' }
    if ($IncludeBrowser) {
        $classpathFile = Join-Path $Script:RuntimeRoot 'runtime-classpath.txt'
        $arguments = @('-B','-ntp','dependency:build-classpath',"-Dmdep.outputFile=$classpathFile",'-DincludeScope=runtime')
        if ($MavenSettings) { $arguments += @('-s', [IO.Path]::GetFullPath($MavenSettings)) }
        if ((Invoke-Maven @arguments) -ne 0) { throw '生成浏览器测试的 classpath 失败。' }
        # 浏览器测试的 fixture 会找最新的不可变类快照；始终用本次验证过的类生成，不用可能过期的开发实例。
        $snapshot = Join-Path $Script:RuntimeRoot ('backend-classes-' + [guid]::NewGuid().ToString('N'))
        Copy-Item -LiteralPath (Join-Path $Script:ProjectRoot 'backend/target/classes') -Destination $snapshot -Recurse
        $classpath = $snapshot + [IO.Path]::PathSeparator + (Get-Content -Raw -LiteralPath $classpathFile).Trim()
        $javaArguments = "-cp`n`"" + $classpath.Replace('\','/').Replace('"','\"') + "`"`ncom.aitest.AiTestApplication`n"
        [IO.File]::WriteAllText((Join-Path $snapshot 'java.args'), $javaArguments, $Script:Utf8)
        $env:AI_TEST_JAVA_HOME = Split-Path -Parent (Split-Path -Parent (Get-AiTestJavaOrInstall))
        Invoke-Pnpm test:e2e:ai
        Invoke-Pnpm test:e2e:auth
    }
    Write-Output '所选检查全部通过。公司模型的生成质量仍需在真实环境单独验收。'
}

# 离线完整包：在发行目录里加 .tools\（MySQL 压缩包、Chromium 内核压缩包、VC++ 运行库安装程序和清单），另打一个 *-offline-windows.zip。
function Add-OfflineBundle([string]$ReleaseRoot, [string]$Jar) {
    $bundle = Join-Path $ReleaseRoot '.tools'
    $null = New-Item -ItemType Directory -Path $bundle -Force
    $mysqlArchive = Join-Path $Script:ToolsRoot $Script:Downloads.mysql.file
    if (-not (Test-Path -LiteralPath $mysqlArchive) -or (Get-FileHash -LiteralPath $mysqlArchive -Algorithm SHA256).Hash -ine $Script:Downloads.mysql.sha256) { $null = Get-AiTestTool 'mysql' 'MySQL 8.4' }
    [IO.File]::Copy($mysqlArchive, (Join-Path $bundle $Script:Downloads.mysql.file), $false)
    Install-VcRuntimeInstallerCopy (Join-Path $bundle 'vc_redist.x64.exe')
    # Chromium：让 Playwright 报出本版本需要的目录，逐个装齐后打成一个压缩包，首次启动时解压。
    $browsersDirectory = Get-DefaultBrowsersDirectory
    $env:PLAYWRIGHT_BROWSERS_PATH = $null
    $locations = Get-PlaywrightInstallLocations -BrowsersPath $browsersDirectory -Browsers @('chromium') -JarPath $Jar
    if ($locations.Count -eq 0) { throw '无法从 Playwright 获取 Chromium 内核目录列表。' }
    if ($locations | Where-Object { -not (Test-Path -LiteralPath (Join-Path $_ 'INSTALLATION_COMPLETE')) }) {
        Write-Note '本机的 Chromium 内核不齐，先安装……'
        Invoke-InstallBrowsers -InstanceDirectory (Join-Path $Script:ProjectRoot 'instance') -JarPath $Jar | Out-Null
    }
    $chromiumArchive = Join-Path $bundle ([IO.Path]::GetFileName($browsersDirectory) + '-chromium-win64.zip')
    $zip = [IO.Compression.ZipFile]::Open($chromiumArchive, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($location in $locations) {
            $name = [IO.Path]::GetFileName($location)
            foreach ($file in Get-ChildItem -LiteralPath $location -Recurse -File -Force) {
                $relative = $name + '/' + [IO.Path]::GetRelativePath($location, $file.FullName).Replace('\', '/')
                $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $relative, [IO.Compression.CompressionLevel]::Optimal)
            }
        }
    } finally { $zip.Dispose() }
    $manifest = @{ playwright = (Get-PlaywrightVersion); mysql = $Script:Downloads.mysql.folder; chromium = @($locations | ForEach-Object { [IO.Path]::GetFileName($_) }); createdAt = [DateTime]::UtcNow.ToString('O')
        files = @(Get-ChildItem -LiteralPath $bundle -File | ForEach-Object { @{ name = $_.Name; bytes = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } }) }
    [IO.File]::WriteAllText((Join-Path $bundle 'manifest.json'), ($manifest | ConvertTo-Json -Depth 5), $Script:Utf8)
}
function Install-VcRuntimeInstallerCopy([string]$Destination) {
    $installer = Join-Path $Script:ToolsRoot 'vc_redist.x64.exe'
    if (-not (Test-Path -LiteralPath $installer)) {
        if ($Script:Offline) { throw '离线模式：.tools\ 里没有 vc_redist.x64.exe，无法制作离线包。' }
        Invoke-WebRequest -Uri $Script:VcRedistUrl -OutFile $installer -ConnectionTimeoutSeconds 20 -OperationTimeoutSeconds 120 -MaximumRetryCount 2 -RetryIntervalSec 5
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $installer
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Microsoft Corporation') { throw 'vc_redist.x64.exe 的微软数字签名校验失败。' }
    [IO.File]::Copy($installer, $Destination, $false)
}

# 给已有的发行目录补做离线完整包（维护者用；build 默认已包含这一步）。
function Invoke-OfflineBundle {
    param([Parameter(Mandatory)][string]$ReleaseDirectory)
    $releaseRoot = [IO.Path]::GetFullPath($ReleaseDirectory)
    if (-not (Test-Path -LiteralPath (Join-Path $releaseRoot 'app.jar'))) { throw "不是发行目录（缺少 app.jar）：$releaseRoot" }
    if (Test-Path -LiteralPath (Join-Path $releaseRoot '.tools')) { Remove-Item -LiteralPath (Join-Path $releaseRoot '.tools') -Recurse -Force }
    if (Test-Path -LiteralPath "$releaseRoot-offline-windows.zip") { Remove-Item -LiteralPath "$releaseRoot-offline-windows.zip" -Force }
    Add-OfflineBundle -ReleaseRoot $releaseRoot -Jar (Join-Path $releaseRoot 'app.jar')
    $checksums = Get-ChildItem -LiteralPath $releaseRoot -Force -Recurse -File | Where-Object Name -ne 'SHA256SUMS' | Sort-Object FullName | ForEach-Object {
        (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetRelativePath($releaseRoot, $_.FullName).Replace('\','/')
    }
    [IO.File]::WriteAllLines((Join-Path $releaseRoot 'SHA256SUMS'), [string[]]$checksums, $Script:Utf8)
    [IO.Compression.ZipFile]::CreateFromDirectory($releaseRoot, "$releaseRoot-offline-windows.zip", [IO.Compression.CompressionLevel]::Optimal, $false)
    Write-Output "离线完整包：$releaseRoot-offline-windows.zip"
    Write-Output "离线包 SHA-256：$((Get-FileHash -LiteralPath "$releaseRoot-offline-windows.zip" -Algorithm SHA256).Hash)"
}

function Invoke-Build {
    param([switch]$SkipTests, [switch]$SkipOffline, [string]$MavenSettings = '', [string]$OutputDirectory = '', [int]$Forks = 3)
    if (-not $Script:IsSourceTree) { throw 'build 只能在源码目录使用。' }
    Invoke-Pnpm install --frozen-lockfile
    Invoke-Pnpm lint
    if (-not $SkipTests) { Invoke-Pnpm test:unit }
    Invoke-Pnpm build
    # 发行构建跑全量集成测试：默认配置跳过 @Tag("slow")，nightly profile 把它们加回来。
    if (-not $SkipTests) { Invoke-ResetTestDb -Forks $Forks }
    $mavenArguments = @('-B','-ntp','-Pdistribution,nightly','clean','verify',"-Daitest.it.forks=$Forks")
    if ($MavenSettings) { $mavenArguments += @('-s', [IO.Path]::GetFullPath($MavenSettings)) }
    if ($SkipTests) { $mavenArguments += '-DskipTests' }
    if ((Invoke-Maven @mavenArguments) -ne 0) { throw '后端验证或打包失败，请看上面 Maven 的输出。' }

    [xml]$pom = Get-Content -Raw -LiteralPath (Join-Path $Script:ProjectRoot 'backend/pom.xml')
    $version = [string]$pom.project.version
    $jar = Join-Path $Script:ProjectRoot "backend/target/ai-test-platform-$version.jar"
    $jarArchive = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        foreach ($entry in @('BOOT-INF/classes/static/index.html','BOOT-INF/classes/com/aitest/AiTestApplication.class')) {
            if ($null -eq $jarArchive.GetEntry($entry)) { throw "发行 JAR 不完整，缺少 $entry" }
        }
        if (@($jarArchive.Entries | Where-Object FullName -Like 'BOOT-INF/classes/db/migration/V*.sql').Count -lt 23) { throw '发行 JAR 缺少数据库迁移脚本。' }
    } finally { $jarArchive.Dispose() }

    $revision = 'source'
    $dirty = $null
    if (Test-Path -LiteralPath (Join-Path $Script:ProjectRoot '.git')) {
        $revision = (& git -C $Script:ProjectRoot rev-parse --short=12 HEAD).Trim()
        if ($LASTEXITCODE -ne 0) { throw '无法识别源码版本。' }
        $dirty = -not [string]::IsNullOrWhiteSpace((& git -C $Script:ProjectRoot status --porcelain | Out-String))
    }
    if (-not $OutputDirectory) { $OutputDirectory = Join-Path $Script:ProjectRoot 'artifacts/releases' }
    $releaseName = "ai-test-platform-$version-" + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + "-$revision"
    $releaseRoot = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) $releaseName
    if (Test-Path -LiteralPath $releaseRoot) { throw '发行目录已存在，没有覆盖任何文件。' }
    $null = New-Item -ItemType Directory -Path $releaseRoot
    [IO.File]::Copy($jar, (Join-Path $releaseRoot 'app.jar'), $false)
    [IO.File]::Copy((Join-Path $Script:ProjectRoot 'deploy/config.example.json'), (Join-Path $releaseRoot 'config.example.json'), $false)
    # 发行包只带面向使用者的文档；验收记录、实施计划和设计草稿留在源码仓库（仍在 source.zip 里）。
    $docsRoot = Join-Path $Script:ProjectRoot 'docs'
    if (Test-Path -LiteralPath $docsRoot -PathType Container) {
        $releaseDocs = Join-Path $releaseRoot 'docs'
        $null = New-Item -ItemType Directory -Path $releaseDocs
        $internalDocs = @('roadmap.md','codex-implementation-prompt.md')
        foreach ($file in Get-ChildItem -LiteralPath $docsRoot -File) {
            if ($file.Extension -in @('.md','.sql') -and $internalDocs -notcontains $file.Name -and $file.Name -notlike 'session-handoff-*.md') { [IO.File]::Copy($file.FullName, (Join-Path $releaseDocs $file.Name), $false) }
        }
        if (Test-Path -LiteralPath (Join-Path $docsRoot 'prompts')) { Copy-Item -LiteralPath (Join-Path $docsRoot 'prompts') -Destination (Join-Path $releaseDocs 'prompts') -Recurse }
    }
    Copy-Item -LiteralPath (Join-Path $Script:ProjectRoot 'licenses') -Destination (Join-Path $releaseRoot 'licenses') -Recurse
    $null = New-Item -ItemType Directory -Path (Join-Path $releaseRoot 'scripts'),(Join-Path $releaseRoot 'database')
    foreach ($script in @('aitest.ps1','aitest.sh')) { [IO.File]::Copy((Join-Path $PSScriptRoot $script), (Join-Path $releaseRoot "scripts/$script"), $false) }
    foreach ($launcher in Get-ChildItem -LiteralPath $Script:ProjectRoot -File -Filter '*.cmd') { [IO.File]::Copy($launcher.FullName, (Join-Path $releaseRoot $launcher.Name), $false) }
    Copy-Item -LiteralPath (Join-Path $Script:ProjectRoot 'backend/src/main/resources/db/migration') -Destination (Join-Path $releaseRoot 'database/migration') -Recurse
    foreach ($file in @('README.md','NOTICE.md')) { [IO.File]::Copy((Join-Path $Script:ProjectRoot $file), (Join-Path $releaseRoot $file), $false) }

    # 对应源码按白名单挑选；运行数据、target 和 node_modules 永远不进包。没有 .git 也能用。
    $sourceFiles = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($directory in @('backend/src','backend/.mvn','frontend/src','frontend/public','frontend/tests','scripts','docs','licenses','deploy')) {
        $sourceDirectory = Join-Path $Script:ProjectRoot $directory
        if (Test-Path -LiteralPath $sourceDirectory) {
            foreach ($file in Get-ChildItem -LiteralPath $sourceDirectory -Force -Recurse -File) { $null = $sourceFiles.Add($file.FullName) }
        }
    }
    $rootFiles = @('.gitignore','.gitattributes','.editorconfig','README.md','NOTICE.md','backend-pom-template.xml','frontend-package-template.json',
        'backend/pom.xml','backend/mvnw','backend/mvnw.cmd','frontend/.gitignore','frontend/README.md','frontend/THIRD_PARTY_NOTICES.md',
        'frontend/package.json','frontend/pnpm-lock.yaml','frontend/index.html','frontend/tsconfig.json','frontend/vite.config.ts',
        'frontend/vitest.config.ts','frontend/eslint.config.js','frontend/playwright.config.ts','frontend/playwright.ai.config.ts','frontend/playwright.auth.config.ts')
    $rootFiles += Get-ChildItem -LiteralPath $Script:ProjectRoot -File -Filter '*.cmd' | Select-Object -ExpandProperty Name
    foreach ($relative in $rootFiles) {
        $file = Join-Path $Script:ProjectRoot $relative
        if (Test-Path -LiteralPath $file -PathType Leaf) { $null = $sourceFiles.Add($file) }
    }
    $sourceArchive = [IO.Compression.ZipFile]::Open((Join-Path $releaseRoot 'source.zip'), [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in ($sourceFiles | Sort-Object)) {
            $entry = Get-Item -LiteralPath $file -Force
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '源码包不接受链接文件。' }
            $relative = [IO.Path]::GetRelativePath($Script:ProjectRoot, $file).Replace('\','/')
            $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($sourceArchive, $file, $relative, [IO.Compression.CompressionLevel]::Optimal)
        }
    } finally { $sourceArchive.Dispose() }
    $nodeVersion = (& (Join-Path (Get-AiTestNodeDirectory) 'node.exe') -p 'process.versions.node').Trim()
    $package = Get-Content -Raw -LiteralPath (Join-Path $Script:Frontend 'package.json') | ConvertFrom-Json
    $metadata = @{version=$version; sourceRevision=$revision; uncommittedSource=$dirty; builtAt=[DateTime]::UtcNow.ToString('O'); node=$nodeVersion; pnpm=$package.packageManager; testsSkipped=[bool]$SkipTests}
    [IO.File]::WriteAllText((Join-Path $releaseRoot 'release.json'), ($metadata | ConvertTo-Json), $Script:Utf8)
    $checksums = Get-ChildItem -LiteralPath $releaseRoot -Force -Recurse -File | Sort-Object FullName | ForEach-Object {
        (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetRelativePath($releaseRoot, $_.FullName).Replace('\','/')
    }
    [IO.File]::WriteAllLines((Join-Path $releaseRoot 'SHA256SUMS'), [string[]]$checksums, $Script:Utf8)
    [IO.Compression.ZipFile]::CreateFromDirectory($releaseRoot, "$releaseRoot.zip", [IO.Compression.CompressionLevel]::Optimal, $false)
    Write-Output "发行目录：$releaseRoot"
    Write-Output "发行压缩包：$releaseRoot.zip"
    Write-Output "压缩包 SHA-256：$((Get-FileHash -LiteralPath "$releaseRoot.zip" -Algorithm SHA256).Hash)"
    if (-not $SkipOffline) { Write-Step '离线完整包'; Invoke-OfflineBundle -ReleaseDirectory $releaseRoot }
}

function Invoke-Clean {
    param([int]$KeepReleases = 1, [switch]$WhatIf)
    # 清掉构建/测试过程文件，只留最新发行包和重建所需的工具；这里删的都能被 verify/build 重新生成，不碰 Git 跟踪的文件。
    if (Get-Process java -ErrorAction SilentlyContinue) { throw '有 Java 进程正在运行。请先结束构建/测试，或双击 停止.cmd。' }
    $keepRuntime = @('mysql', 'pnpm-shim', 'dev', 'cdx-schemas', 'bom-validator-classes', 'maven-central-settings.xml', 'build.cmd', 'run-release-build.ps1')
    $keepExtensions = @('.ps1', '.mjs', '.java', '.xml', '.cmd')
    $targets = [Collections.Generic.List[string]]::new()
    if (Test-Path -LiteralPath $Script:RuntimeRoot) {
        foreach ($entry in Get-ChildItem -LiteralPath $Script:RuntimeRoot -Force) {
            if ($keepRuntime -contains $entry.Name -or $entry.Name -like 'sbom-*') { continue }
            if (-not $entry.PSIsContainer -and $keepExtensions -contains $entry.Extension) { continue }
            $targets.Add($entry.FullName)
        }
    }
    $releases = Join-Path $Script:ProjectRoot 'artifacts/releases'
    if (Test-Path -LiteralPath $releases) {
        $zips = @(Get-ChildItem -LiteralPath $releases -Filter '*.zip' -File | Where-Object Name -notlike '*-offline-windows.zip' | Sort-Object LastWriteTime -Descending)
        $keep = @($zips | Select-Object -First $KeepReleases | ForEach-Object { $_.BaseName })
        foreach ($entry in Get-ChildItem -LiteralPath $releases -Force) {
            $base = if ($entry.PSIsContainer) { $entry.Name } else { ($entry.Name -replace '(-offline-windows)?\.(zip|acceptance\.json|dependencies\.json|sbom\.json)$', '') }
            if ($keep -notcontains $base) { $targets.Add($entry.FullName) }
        }
    }
    foreach ($relative in @('frontend/test-results', 'frontend/playwright-report', 'backend/target/failsafe-reports', 'backend/target/surefire-reports')) {
        $path = Join-Path $Script:ProjectRoot $relative
        if (Test-Path -LiteralPath $path) { $targets.Add($path) }
    }
    $bytes = 0
    foreach ($target in $targets) {
        $item = Get-Item -LiteralPath $target -Force
        $bytes += if ($item.PSIsContainer) { (Get-ChildItem -LiteralPath $target -Recurse -File -Force | Measure-Object Length -Sum).Sum } else { $item.Length }
    }
    if ($WhatIf) { $targets | ForEach-Object { Write-Output "将删除 $_" } }
    else { foreach ($target in $targets) { Remove-Item -LiteralPath $target -Recurse -Force } }
    Write-Output ("{0} {1} 项，共 {2} GB。" -f $(if ($WhatIf) { '将删除' } else { '已删除' }), $targets.Count, [math]::Round($bytes / 1GB, 1))
}

# ============================================================ 私有库 ⇄ 公开库 ============================================================
# 公开库不带内部材料：验收记录、分析、设计归档、会话交接、实施提示词与两个模板文件。其余 Git 跟踪的文件两边保持一致。
$Script:PrivateOnlyPatterns = @('^docs/acceptance/', '^docs/analysis/', '^docs/design-archive/', '^docs/superpowers/', '^docs/roadmap\.md$', '^docs/codex-implementation-prompt\.md$',
    '^docs/session-handoff-.*\.md$', '^\.superpowers/', '^backend-pom-template\.xml$', '^frontend-package-template\.json$')
function Test-PrivateOnly([string]$RelativePath) { foreach ($pattern in $Script:PrivateOnlyPatterns) { if ($RelativePath -match $pattern) { return $true } }; return $false }
function Invoke-SyncPublic {
    param([Parameter(Mandatory)][string]$PublicDirectory, [switch]$Reverse)
    if (-not $Script:IsSourceTree) { throw 'sync-public 只能在源码目录使用。' }
    $public = [IO.Path]::GetFullPath($PublicDirectory)
    if (-not (Test-Path -LiteralPath (Join-Path $public '.git'))) { throw "公开库工作区不存在或不是 Git 仓库：$public" }
    $source = if ($Reverse) { $public } else { $Script:ProjectRoot }
    $target = if ($Reverse) { $Script:ProjectRoot } else { $public }
    $files = @(& git -C $source ls-files -z | ForEach-Object { $_ } ) -join '' -split "`0" | Where-Object { $_ }
    if ($LASTEXITCODE -ne 0) { throw '读取 Git 文件列表失败。' }
    $copied = 0
    foreach ($relative in $files) {
        if (-not $Reverse -and (Test-PrivateOnly $relative)) { continue }
        $from = Join-Path $source $relative; $to = Join-Path $target $relative
        $null = New-Item -ItemType Directory -Path (Split-Path -Parent $to) -Force
        if (-not (Test-Path -LiteralPath $to) -or (Get-FileHash -LiteralPath $from -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $to -Algorithm SHA256).Hash) { [IO.File]::Copy($from, $to, $true); $copied++ }
    }
    $removed = 0
    if (-not $Reverse) {
        # 私有库已删除的文件，公开库也删掉（公开库独有的文件只可能是这份列表之外的内部文件，不存在）。
        $sourceSet = [Collections.Generic.HashSet[string]]::new([string[]]$files, [StringComparer]::OrdinalIgnoreCase)
        foreach ($relative in @(& git -C $public ls-files -z) -join '' -split "`0" | Where-Object { $_ }) {
            if (-not $sourceSet.Contains($relative) -and -not (Test-PrivateOnly $relative)) { & git -C $public rm -q --force -- $relative; $removed++ }
        }
    }
    Write-Output ("已同步到 {0}：复制 {1} 个文件，删除 {2} 个。请到该目录检查 git status，再提交推送。" -f $target, $copied, $removed)
    & git -C $target -c core.quotepath=false status --short | Out-Host
}

# ============================================================ 命令分发 ============================================================

$command = if ($args.Count -gt 0) { [string]$args[0] } else { 'help' }
[object[]]$rest = @($args | Select-Object -Skip 1)
if ($rest | Where-Object { "$_" -ieq '-Offline' }) { $Script:Offline = $true; [object[]]$rest = @($rest | Where-Object { "$_" -ine '-Offline' }) }
switch ($command.ToLowerInvariant()) {
    'up'               { Invoke-Up @rest }
    'down'             { Invoke-Down @rest }
    'restart'          { $downArguments = @{ KeepMysql = $true }; $instanceIndex = [Array]::FindIndex($rest, [Predicate[object]]{ param($item) "$item" -ieq '-InstanceDirectory' })
                         if ($instanceIndex -ge 0 -and $instanceIndex + 1 -lt $rest.Count) { $downArguments.InstanceDirectory = [string]$rest[$instanceIndex + 1] }
                         Invoke-Down @downArguments; Invoke-Up @rest }
    'status'           { Invoke-Status @rest }
    'logs'             { Invoke-Logs @rest }
    'check'            { Invoke-Check @rest }
    'start'            { Invoke-Start @rest }
    'stop'             { Invoke-Stop @rest }
    'backup'           { Invoke-Backup @rest }
    'upgrade'          { Invoke-Upgrade @rest }
    'restore'          { Invoke-Restore @rest }
    'install-browsers' { Invoke-InstallBrowsers @rest }
    'build'            { Invoke-Build @rest }
    'verify'           { Invoke-Verify @rest }
    'clean'            { Invoke-Clean @rest }
    'mysql'            { Invoke-Mysql @rest }
    'reset-test-db'    { Invoke-ResetTestDb @rest }
    'maven'            { exit (Invoke-Maven @rest) }
    'sync-public'      { Invoke-SyncPublic @rest }
    'offline-bundle'   { Invoke-OfflineBundle @rest }
    'help'             { $text = Get-Content -Raw -LiteralPath $PSCommandPath; Write-Host ($text.Substring($text.IndexOf('<#') + 2, $text.IndexOf('#>') - $text.IndexOf('<#') - 2)) }
    default            { throw "未知命令：$command。运行 aitest.ps1 help 查看用法。" }
}
