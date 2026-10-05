[CmdletBinding()]
param([switch]$SkipBuild,[string]$PublicUrl)
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$demoValues=@{}
foreach($line in [IO.File]::ReadAllLines((Join-Path $demoRoot '.env'))){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$demoValues[$Matches[1]]=$Matches[2]}}
if(!$PublicUrl){$frontendPort=if($demoValues.FRONTEND_PORT){$demoValues.FRONTEND_PORT}else{'3000'};$PublicUrl="http://localhost:$frontendPort"}
$env:APP_PUBLIC_URL=$PublicUrl
$env:CORS_ALLOWED_ORIGIN=$PublicUrl
Push-Location $demoRoot
try{
 if(!$SkipBuild){& (Join-Path $PSScriptRoot 'build-apps.ps1')}
 $backendRoot=Join-Path $demoRoot 'backend'
 foreach($service in 'identity-service','facility-service','schedule-service','booking-service','payment-service','transfer-service','api-gateway'){
  $jar=Join-Path $backendRoot "$service/target/$service-0.0.1-SNAPSHOT.jar"
  foreach($process in Get-CimInstance Win32_Process -Filter "name='java.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains($jar)}){[System.Diagnostics.Process]::GetProcessById([int]$process.ProcessId).Kill()}
 }
 & docker compose --profile apps up -d --no-build --wait --wait-timeout 240
 if($LASTEXITCODE -ne 0){throw 'Application containers did not become healthy. Inspect docker compose --profile apps logs for this project.'}
 Write-Output "READY containerized SportHub: $PublicUrl"
}finally{Pop-Location}
