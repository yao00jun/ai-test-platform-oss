#requires -Version 7.4
<#
本机开发一键入口（源码目录专用，不进入发行包）。

  .\scripts\dev.ps1            等同 up
  .\scripts\dev.ps1 up         项目 MySQL → 实例配置 → JAR（缺失时自动打包）→ 浏览器内核（缺失时自动安装）→ 后端 → 打开浏览器
  .\scripts\dev.ps1 down       停止 Vite、后端、项目 MySQL
  .\scripts\dev.ps1 restart    保留 MySQL，重启后端
  .\scripts\dev.ps1 status     查看 MySQL / 后端 / 模型 / Vite 状态与日志位置
  .\scripts\dev.ps1 logs       实时跟踪后端日志（Ctrl+C 退出）

开关：
  up / restart   -Dev 额外启动 Vite 热更新并打开 5173   -Build 先重新打包 JAR   -NoBrowser 不弹浏览器
  down           -KeepMysql 保留数据库   -Force 优雅停止失败时强制结束后端
  logs           -Errors 看错误输出   -Vite 看前端日志   -Tail 60
#>
param(
    [Parameter(Position = 0)][ValidateSet('up', 'down', 'restart', 'status', 'logs')][string]$Command = 'up',
    [switch]$Dev, [switch]$Build, [switch]$NoBrowser,
    [switch]$KeepMysql, [switch]$Force,
    [switch]$Errors, [switch]$Vite, [int]$Tail = 60,
    [string]$InstanceDirectory = ''
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
. (Join-Path $PSScriptRoot 'operations-common.ps1')
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Frontend = Join-Path $ProjectRoot 'frontend'
$MysqlConnectionFile = Join-Path $ProjectRoot '.runtime/mysql/connection.json'
$ViteStateFile = Join-Path $ProjectRoot '.runtime/dev/vite.json'
$DevJar = Join-Path $ProjectRoot 'backend/target/ai-test-platform-1.0.0-SNAPSHOT.jar'
if (-not $InstanceDirectory) { $InstanceDirectory = Join-Path $ProjectRoot 'instance' }

function Write-Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Ok([string]$Message) { Write-Host "    $Message" -ForegroundColor Green }
function Write-Warn([string]$Message) { Write-Host "    $Message" -ForegroundColor Yellow }

function Test-PortOpen([string]$HostName, [int]$Port, [int]$TimeoutMs = 800) {
    $client = [Net.Sockets.TcpClient]::new()
    try { return $client.ConnectAsync($HostName, $Port).Wait($TimeoutMs) -and $client.Connected }
    catch { return $false } finally { $client.Dispose() }
}

function Get-ProjectMysql {
    if (-not (Test-Path -LiteralPath $MysqlConnectionFile)) { return $null }
    $connection = Get-Content -Raw -LiteralPath $MysqlConnectionFile | ConvertFrom-Json -AsHashtable
    $process = if ($connection.pid) { Get-Process -Id ([int]$connection.pid) -ErrorAction SilentlyContinue } else { $null }
    if ($process -and $process.ProcessName -ne 'mysqld') { $process = $null }
    return [pscustomobject]@{ Port=[int]$connection.port; Home=[string]$connection.home; Process=$process; Listening=(Test-PortOpen '127.0.0.1' ([int]$connection.port)) }
}

function Get-ViteState {
    if (-not (Test-Path -LiteralPath $ViteStateFile)) { return $null }
    $state = Get-Content -Raw -LiteralPath $ViteStateFile | ConvertFrom-Json -AsHashtable
    $process = if ($state.pid) { Get-Process -Id ([int]$state.pid) -ErrorAction SilentlyContinue } else { $null }
    return [pscustomobject]@{ Port=[int]$state.port; Url=[string]$state.url; Process=$process; Log=[string]$state.log }
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

function Install-FrontendDependencies {
    if (Test-Path -LiteralPath (Join-Path $Frontend 'node_modules')) { return }
    & pnpm --dir $Frontend install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' }
}

function Invoke-Up {
    Write-Step '1/5 项目 MySQL 8.4（端口 3307）'
    & (Join-Path $PSScriptRoot 'bootstrap-mysql.ps1') | ForEach-Object { Write-Ok $_ }
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 未能启动，查看 .runtime/mysql/mysql.log。' }

    Write-Step '2/5 实例配置'
    Initialize-AiTestConfiguration -PackageRoot $ProjectRoot -InstanceDirectory $InstanceDirectory | ForEach-Object { Write-Ok $_ }
    $settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory
    Write-Ok "配置：$($settings.ConfigPath)"

    Write-Step '3/5 可执行 JAR'
    if ($Build -or -not (Test-Path -LiteralPath $DevJar)) {
        Write-Warn $(if ($Build) { '按要求重新打包（跳过测试）。' } else { 'JAR 不存在，首次自动打包（跳过测试），约需几分钟。' })
        Install-FrontendDependencies
        & pnpm --dir $Frontend build
        if ($LASTEXITCODE -ne 0) { throw '前端构建失败。' }
        & (Join-Path $PSScriptRoot 'maven.ps1') -B -ntp -Pdistribution -DskipTests clean package
        if ($LASTEXITCODE -ne 0) { throw '后端打包失败。' }
    }
    $jarAge = [DateTime]::Now - (Get-Item -LiteralPath $DevJar).LastWriteTime
    Write-Ok ("JAR：{0}（{1:N0} 分钟前构建）" -f $DevJar, $jarAge.TotalMinutes)

    Write-Step '4/5 Playwright 浏览器内核'
    $chromium = if (Test-Path -LiteralPath $settings.Browsers) { Get-ChildItem -LiteralPath $settings.Browsers -Recurse -File -Filter 'chrome*.exe' -ErrorAction SilentlyContinue | Select-Object -First 1 } else { $null }
    if ($chromium) { Write-Ok "Chromium 已就绪：$($settings.Browsers)" }
    else {
        Write-Warn 'Chromium 缺失，自动安装（需要联网，一次性）。'
        & (Join-Path $PSScriptRoot 'install-browsers.ps1') -InstanceDirectory $InstanceDirectory -JarPath $DevJar
        if ($LASTEXITCODE -ne 0) { throw '浏览器安装失败。UI/PDF 之外的功能不受影响，可稍后重试。' }
    }

    Write-Step '5/5 后端服务'
    $backend = Get-BackendState $settings
    if ($backend.Process) { Write-Ok "已在运行：$($backend.Url)（PID $($backend.Process.Id)）" }
    else {
        & (Join-Path $PSScriptRoot 'start.ps1') -InstanceDirectory $InstanceDirectory -JarPath $DevJar | ForEach-Object { Write-Ok $_ }
        $backend = Get-BackendState $settings
    }
    $openUrl = $backend.Url

    if ($Dev) {
        Write-Step '附加：Vite 前端热更新（端口 5173）'
        $vite = Get-ViteState
        if ($vite -and $vite.Process) { Write-Ok "已在运行：$($vite.Url)（PID $($vite.Process.Id)）" }
        else {
            if (Test-PortOpen '127.0.0.1' 5173) { throw '5173 端口已被其他进程占用，未启动 Vite。' }
            Install-FrontendDependencies
            $devRoot = Join-Path $ProjectRoot '.runtime/dev'
            $null = New-Item -ItemType Directory -Path $devRoot -Force
            $log = Join-Path $devRoot 'vite.log'; $errorLog = Join-Path $devRoot 'vite.err.log'
            $vitePath = Join-Path $Frontend 'node_modules/vite/bin/vite.js'
            if (-not (Test-Path -LiteralPath $vitePath)) { throw "找不到 $vitePath，请先安装前端依赖。" }
            $process = Start-Process -FilePath 'node' -ArgumentList @($vitePath, '--host', '127.0.0.1', '--port', '5173', '--strictPort') -WorkingDirectory $Frontend `
                -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError $errorLog -Environment @{ AI_TEST_API_URL = $backend.Url }
            $deadline = [DateTime]::UtcNow.AddSeconds(90)
            while ([DateTime]::UtcNow -lt $deadline -and -not (Test-PortOpen '127.0.0.1' 5173)) {
                if ($process.HasExited) { throw "Vite 已退出，查看 $errorLog" }
                Start-Sleep -Milliseconds 500
            }
            if (-not (Test-PortOpen '127.0.0.1' 5173)) { $process.Kill($true); throw "Vite 启动超时，查看 $log" }
            $state = @{ pid=$process.Id; port=5173; url='http://127.0.0.1:5173'; log=$log; startedAt=[DateTime]::UtcNow.ToString('O') }
            [IO.File]::WriteAllText($ViteStateFile, ($state | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
            Write-Ok "Vite 就绪：http://127.0.0.1:5173（PID $($process.Id)），/api 代理到 $($backend.Url)"
        }
        $openUrl = 'http://127.0.0.1:5173'
    }

    Write-Host ''
    Write-Host "✔ 平台已就绪：$openUrl" -ForegroundColor Green
    if (-not ($settings.Config.model.baseUrl -and $settings.Config.model.modelName)) { Write-Warn '模型未配置：手工功能全部可用；AI 生成/诊断请在界面「模型设置」填写 OpenAI 兼容服务。' }
    Write-Host '  .\scripts\dev.ps1 down | restart | status | logs' -ForegroundColor DarkGray
    if (-not $NoBrowser) { Start-Process $openUrl }
}

function Invoke-Down {
    Write-Step '1/3 Vite 前端'
    $vite = Get-ViteState
    if ($vite -and $vite.Process) {
        $vite.Process.Kill($true); $vite.Process.WaitForExit(10000) | Out-Null
        Write-Ok "已停止 Vite（PID $($vite.Process.Id)）"
    } else { Write-Ok '未运行' }
    if (Test-Path -LiteralPath $ViteStateFile) { Remove-Item -LiteralPath $ViteStateFile -Force }

    Write-Step '2/3 后端服务'
    if (Test-Path -LiteralPath (Join-Path $InstanceDirectory 'config.json')) {
        $settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory
        $backend = Get-BackendState $settings
        if ($backend.Process) {
            try { & (Join-Path $PSScriptRoot 'stop.ps1') -InstanceDirectory $InstanceDirectory | ForEach-Object { Write-Ok $_ } }
            catch {
                if (-not $Force) { throw }
                Write-Warn "优雅停止失败，按 -Force 强制结束：$($_.Exception.Message)"
                & (Join-Path $PSScriptRoot 'stop.ps1') -InstanceDirectory $InstanceDirectory -Force | ForEach-Object { Write-Ok $_ }
            }
        } else { Write-Ok '未运行' }
    } else { Write-Ok '尚无实例配置，跳过' }

    Write-Step '3/3 项目 MySQL'
    $mysql = Get-ProjectMysql
    if ($KeepMysql) { Write-Ok '按要求保留运行' }
    elseif ($mysql -and $mysql.Process) {
        $runtime = Split-Path -Parent $MysqlConnectionFile
        $mysqladmin = Join-Path $mysql.Home 'bin/mysqladmin.exe'
        $stopped = $false
        if ((Test-Path -LiteralPath $mysqladmin) -and (Test-Path -LiteralPath (Join-Path $runtime 'admin.cnf'))) {
            # admin.cnf 以相对文件名传入：MySQL 的 Windows 客户端读不了含中文的绝对路径。
            $shutdown = Start-Process -FilePath $mysqladmin -ArgumentList @('--defaults-file=admin.cnf', 'shutdown') -WorkingDirectory $runtime -WindowStyle Hidden -PassThru -Wait
            $stopped = ($shutdown.ExitCode -eq 0) -and $mysql.Process.WaitForExit(30000)
        }
        if (-not $stopped) { Write-Warn '优雅关闭未成功，直接结束 mysqld 进程。'; $mysql.Process.Kill(); $mysql.Process.WaitForExit(15000) | Out-Null }
        Write-Ok "已停止 MySQL（PID $($mysql.Process.Id)）"
    } else { Write-Ok '未运行' }

    Write-Host ''
    Write-Host '✔ 已停止。再次启动：.\scripts\dev.ps1' -ForegroundColor Green
}

function Invoke-Status {
    function Show([string]$Name, [bool]$Up, [string]$Detail) {
        $mark = if ($Up) { '●' } else { '○' }; $color = if ($Up) { 'Green' } else { 'DarkGray' }
        Write-Host ("  {0} {1,-6} {2}" -f $mark, $Name, $Detail) -ForegroundColor $color
    }
    Write-Host 'AI-Test-Platform 本地状态' -ForegroundColor Cyan
    $mysql = Get-ProjectMysql
    if ($mysql) { Show 'MySQL' ($null -ne $mysql.Process -and $mysql.Listening) $(if ($mysql.Process) { "127.0.0.1:$($mysql.Port)  PID $($mysql.Process.Id)" } else { "未运行（端口 $($mysql.Port)）" }) }
    else { Show 'MySQL' $false '尚未初始化，dev.ps1 up 会自动创建' }
    if (Test-Path -LiteralPath (Join-Path $InstanceDirectory 'config.json')) {
        $settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory
        $backend = Get-BackendState $settings
        Show '后端' ($null -ne $backend.Process) $(if ($backend.Process) { "$($backend.Url)  PID $($backend.Process.Id)  健康检查 $(if ($backend.Healthy) { 'UP' } else { '未通过' })" } else { "未运行（将监听 $($backend.Url)）" })
        if ($backend.Process -and $backend.State.stdout) { Write-Host "           日志 $($backend.State.stdout)" -ForegroundColor DarkGray }
        $model = $settings.Config.model
        Show '模型' ([bool]($model.baseUrl -and $model.modelName)) $(if ($model.baseUrl -and $model.modelName) { "$($model.modelName) @ $($model.baseUrl)" } else { '配置文件未填写；界面「模型设置」保存的配置优先生效' })
    } else { Show '后端' $false '尚无实例配置' }
    $vite = Get-ViteState
    Show 'Vite' ($null -ne $vite -and $null -ne $vite.Process) $(if ($vite -and $vite.Process) { "$($vite.Url)  PID $($vite.Process.Id)" } else { '未运行（dev.ps1 up -Dev 启动热更新）' })
}

function Invoke-Logs {
    if ($Vite) {
        $state = Get-ViteState
        if (-not $state) { throw 'Vite 未通过 dev.ps1 up -Dev 启动，没有日志。' }
        $file = $state.Log
    } else {
        $settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory
        $state = Get-AiTestState $settings
        if (-not $state) { throw '后端尚未启动过，没有日志。' }
        $file = if ($Errors) { $state.stderr } else { $state.stdout }
    }
    Write-Host "跟踪 $file（Ctrl+C 退出）" -ForegroundColor DarkGray
    Get-Content -LiteralPath $file -Tail $Tail -Wait -Encoding UTF8
}

switch ($Command) {
    'up' { Invoke-Up }
    'down' { Invoke-Down }
    'restart' { $KeepMysql = $true; Invoke-Down; Invoke-Up }
    'status' { Invoke-Status }
    'logs' { Invoke-Logs }
}
