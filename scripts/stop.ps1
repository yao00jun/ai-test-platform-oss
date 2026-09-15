#requires -Version 7.4
param([string]$InstanceDirectory = '', [string]$ConfigPath = '', [switch]$Force, [int]$TimeoutSeconds = 90)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'operations-common.ps1')
if (-not $InstanceDirectory) { $InstanceDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'instance' }
$settings = Read-AiTestConfiguration -InstanceDirectory $InstanceDirectory -ConfigPath $ConfigPath
$state = Get-AiTestState $settings
$application = Get-AiTestManagedProcess $state
if (-not $application) { Write-Output 'This instance has no running managed application.'; exit 0 }
if ($Force) {
    $application.Kill($true)
} else {
    try {
        $null = Invoke-RestMethod -Method Post -Uri "$($state.baseUrl)/internal/lifecycle/stop" -Headers @{'X-AITest-Shutdown-Token'=$state.token} -TimeoutSec 10 -NoProxy
    } catch { throw 'Graceful shutdown request failed. Inspect the instance logs; use -Force only to interrupt this managed process.' }
}
if (-not $application.WaitForExit($TimeoutSeconds * 1000)) { throw 'The process has not exited; inspect logs or stop the same instance with -Force.' }
$state.status='STOPPED'; $state.stoppedAt=[DateTime]::UtcNow.ToString('O'); $state.Remove('token')
[IO.File]::WriteAllText((Join-Path $settings.RunDirectory 'state.json'), ($state | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Write-Output "Stopped managed application PID $($application.Id)."
