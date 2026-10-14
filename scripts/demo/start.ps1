[CmdletBinding()]
param([switch]$SkipInfrastructure)
$ErrorActionPreference='Stop'
$demoLauncher=Join-Path $PSScriptRoot 'local.py'
$demoPython=Get-Command python3,python -ErrorAction SilentlyContinue | Select-Object -First 1
if(!$demoPython){throw 'Install Python 3.9+ to use the shared local launcher.'}
$demoArguments=@($demoLauncher,'start','--skip-build')
if($SkipInfrastructure){$demoArguments+='--skip-infrastructure'}
& $demoPython.Source @demoArguments
if($LASTEXITCODE -ne 0){throw 'Local service startup failed.'}
