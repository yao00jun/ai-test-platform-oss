#requires -Version 7.4
param([string]$InstanceDirectory = '', [string]$ConfigPath = '', [string]$JarPath = '', [int]$StartupTimeoutSeconds = 240)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'operations-common.ps1')
$packageRoot = Split-Path -Parent $PSScriptRoot
if (-not $InstanceDirectory) { $InstanceDirectory = Join-Path $packageRoot 'instance' }
if (-not $ConfigPath) { Initialize-AiTestConfiguration -PackageRoot $packageRoot -InstanceDirectory $InstanceDirectory }
$settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory -ConfigPath $ConfigPath
if (Test-Path -LiteralPath (Join-Path $settings.RunDirectory 'restore-incomplete.json')) { throw 'A previous restore is incomplete. Inspect run/restore-incomplete.json and restore into a new empty instance before starting.' }
$previous = Get-AiTestState $settings
$existing = Get-AiTestManagedProcess $previous
if ($existing) { Write-Output "Instance is already running (PID $($existing.Id)): $($previous.baseUrl)"; exit 0 }
if (-not $JarPath) {
    $JarPath = Join-Path $packageRoot 'app.jar'
    if (-not (Test-Path -LiteralPath $JarPath)) { $JarPath = Join-Path $packageRoot 'backend/target/ai-test-platform-1.0.0-SNAPSHOT.jar' }
}
$JarPath = [IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'Executable JAR is missing. Run scripts/build.ps1 first.' }
$java = Find-AiTestJava ([string]$settings.Config.javaHome)
$null = New-Item -ItemType Directory -Path $settings.RunDirectory,$settings.LogDirectory,$settings.Storage -Force
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Parse($settings.Bind), $settings.Port)
try { $listener.Start() } catch { throw "Port $($settings.Port) is already occupied; no process was stopped." } finally { $listener.Stop() }
$token = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$environment = Get-AiTestEnvironment -Settings $settings -ShutdownToken $token
$probeHost = switch ($settings.Bind) { '0.0.0.0' { '127.0.0.1' }; '::' { '::1' }; default { $settings.Bind } }
if ($probeHost.Contains(':')) { $probeHost = '[' + $probeHost + ']' }
$baseUrl = "http://${probeHost}:$($settings.Port)"
$identity = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$stdout = Join-Path $settings.LogDirectory "application-$identity.log"
$stderr = Join-Path $settings.LogDirectory "application-$identity.err.log"
$arguments = @('-Dfile.encoding=UTF-8', '-Xms128m', "-Xmx$($settings.Config.runtime.heapMiB)m", '-jar', ('"' + $JarPath + '"'),
    "--aitest.execution.business-connections=$($settings.Config.runtime.businessConnections)")
$application = Start-Process -FilePath $java -ArgumentList $arguments -Environment $environment -WorkingDirectory $packageRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$state = @{pid=$application.Id; startedAt=$application.StartTime.ToUniversalTime().ToString('O'); baseUrl=$baseUrl; token=$token; jar=$JarPath;
    configPath=$settings.ConfigPath; stdout=$stdout; stderr=$stderr; status='STARTING'}
$stateFile = Join-Path $settings.RunDirectory 'state.json'
[IO.File]::WriteAllText($stateFile, ($state | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
try {
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $application.Refresh()
        if ($application.HasExited) { throw "Application exited. Inspect $stdout and $stderr" }
        try {
            $health = Invoke-RestMethod -Uri "$baseUrl/actuator/health" -TimeoutSec 2 -NoProxy
            if ($health.status -eq 'UP') { $ready=$true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "Startup timed out. Inspect $stdout" }
    $keyBytes = Get-AiTestMasterKey $settings
    $state.masterKeyHash = Get-AiTestKeyHash $keyBytes
    [Array]::Clear($keyBytes)
    $state.status='RUNNING'
    [IO.File]::WriteAllText($stateFile, ($state | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    Write-Output "AI-Test-Platform ready: $baseUrl (PID $($application.Id))"
    Write-Output "Logs: $stdout"
} catch {
    if (Get-AiTestManagedProcess $state) { Stop-Process -Id $application.Id -Force -ErrorAction SilentlyContinue }
    throw
}
