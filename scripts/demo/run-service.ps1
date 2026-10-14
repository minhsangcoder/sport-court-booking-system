[CmdletBinding()]
param([Parameter(Mandatory=$true)][ValidateSet('api-gateway','identity-service','facility-service','schedule-service','booking-service','payment-service','transfer-service')][string]$Service)
$ErrorActionPreference='Stop'
$demoLauncher=Join-Path $PSScriptRoot 'local.py'
$demoPython=Get-Command python3,python -ErrorAction SilentlyContinue | Select-Object -First 1
if(!$demoPython){throw 'Install Python 3.9+ to use the shared local launcher.'}
& $demoPython.Source $demoLauncher run-service $Service
exit $LASTEXITCODE
