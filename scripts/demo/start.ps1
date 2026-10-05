[CmdletBinding()]
param([switch]$SkipInfrastructure)
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if(!(Test-Path -LiteralPath (Join-Path $demoRoot '.env'))){throw 'Create the local .env first.'}
if(!$SkipInfrastructure){Push-Location $demoRoot;try{& docker compose up -d --wait;if($LASTEXITCODE -ne 0){throw 'Infrastructure startup failed'}}finally{Pop-Location}}
$demoLogRoot=Join-Path $demoRoot 'tmp/demo';[IO.Directory]::CreateDirectory($demoLogRoot)|Out-Null
$demoShell=(Get-Process -Id $PID).Path
$demoScript=Join-Path $PSScriptRoot 'run-service.ps1'
$demoServices=@{'identity-service'=18081;'facility-service'=18082;'schedule-service'=18083;'booking-service'=18084;'payment-service'=18085;'transfer-service'=18086;'api-gateway'=18080}
foreach($demoService in 'identity-service','facility-service','schedule-service','booking-service','payment-service','transfer-service','api-gateway'){
 $demoJar=Join-Path $demoRoot "backend/$demoService/target/$demoService-0.0.1-SNAPSHOT.jar"
 if(!(Test-Path -LiteralPath $demoJar)){throw "Missing JAR for $demoService; run scripts/demo/build-backend.ps1 first."}
 $demoExisting=@(Get-CimInstance Win32_Process -Filter "name='java.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($demoJar) })
 if($demoExisting.Count -eq 0){
  Start-Process -FilePath $demoShell -ArgumentList @('-NoProfile','-File',"`"$demoScript`"",'-Service',$demoService) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $demoLogRoot "$demoService.log") -RedirectStandardError (Join-Path $demoLogRoot "$demoService.error.log")|Out-Null
 }
 $demoReady=$false
 for($demoAttempt=0;$demoAttempt -lt 120;$demoAttempt++){
  try{$demoHealth=Invoke-RestMethod "http://localhost:$($demoServices[$demoService])/actuator/health" -TimeoutSec 2;if($demoHealth.status -eq 'UP'){$demoReady=$true;break}}catch{}
  Start-Sleep -Milliseconds 500
 }
 if(!$demoReady){throw "$demoService did not become healthy. Inspect tmp/demo/$demoService.log and .error.log."}
 Write-Output "READY $demoService"
}
Write-Output 'Gateway ready at http://localhost:18080. Frontend: set GATEWAY_URL=http://localhost:18080 and run pnpm dev in frontend.'
