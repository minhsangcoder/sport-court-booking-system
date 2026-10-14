[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$demoLauncher=Join-Path $PSScriptRoot 'local.py'
$demoPython=Get-Command python3,python -ErrorAction SilentlyContinue | Select-Object -First 1
if(!$demoPython){throw 'Install Python 3.9+ to use the shared local launcher.'}
& $demoPython.Source $demoLauncher verify-migrations
if($LASTEXITCODE -ne 0){throw 'Migration verification failed.'}
