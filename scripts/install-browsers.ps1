#requires -Version 7.4
param([ValidateSet('chromium','firefox','webkit')][string[]]$Browsers = @('chromium'),
      [string]$InstanceDirectory = '', [string]$ConfigPath = '', [string]$JarPath = '', [switch]$DryRun)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'operations-common.ps1')
$packageRoot = Split-Path -Parent $PSScriptRoot
if (-not $InstanceDirectory) { $InstanceDirectory = Join-Path $packageRoot 'instance' }
if (-not $ConfigPath) { Initialize-AiTestConfiguration -PackageRoot $packageRoot -InstanceDirectory $InstanceDirectory }
$settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory -ConfigPath $ConfigPath
if (-not $JarPath) {
    $JarPath = Join-Path $packageRoot 'app.jar'
    if (-not (Test-Path -LiteralPath $JarPath)) { $JarPath = Join-Path $packageRoot 'backend/target/ai-test-platform-1.0.0-SNAPSHOT.jar' }
}
$JarPath = [IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'Build the executable JAR before installing its matching browser version.' }
$java = Find-AiTestJava ([string]$settings.Config.javaHome)
$info = [Diagnostics.ProcessStartInfo]::new($java)
$info.UseShellExecute=$false; $info.CreateNoWindow=$true
foreach ($argument in @('-jar',$JarPath,'--playwright-cli','install') + $(if ($DryRun) { @('--dry-run') } else { @() }) + $Browsers) { $info.ArgumentList.Add($argument) }
$info.Environment['PLAYWRIGHT_BROWSERS_PATH']=$settings.Browsers
foreach ($name in @($info.Environment.Keys | Where-Object { $_ -like 'AI_TEST_*' -or $_ -like 'SPRING_*' -or $_ -in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD') })) { $null = $info.Environment.Remove($name) }
$process = [Diagnostics.Process]::Start($info)
try {
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'Browser installation failed; inspect the Playwright output above.' }
} finally { $process.Dispose() }
Write-Output "Playwright browser path: $($settings.Browsers)"
