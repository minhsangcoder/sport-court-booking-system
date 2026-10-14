[CmdletBinding()]
param([switch]$SkipBuild,[string]$PublicUrl)
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
# Use Compose's dotenv parser for quoting/interpolation; capture its output
# because the resolved configuration contains private environment values.
try{
 $demoConfigLines=& docker compose --project-directory $demoRoot --env-file (Join-Path $demoRoot '.env') -f (Join-Path $demoRoot 'docker-compose.yml') --profile apps config --format json 2>&1
 if($LASTEXITCODE -ne 0){throw 'Invalid Compose configuration.'}
 $demoConfig=($demoConfigLines -join "`n") | ConvertFrom-Json
}catch{throw 'Cannot load Compose configuration; check .env and Docker Compose installation.'}
$demoValues=$demoConfig.services.'identity-service'.environment
if(!$PublicUrl){$frontendPort=($demoConfig.services.frontend.ports | Where-Object {$_.target -eq 3000} | Select-Object -First 1).published;$PublicUrl="http://localhost:$frontendPort"}
$env:APP_PUBLIC_URL=$PublicUrl
$env:CORS_ALLOWED_ORIGIN=$PublicUrl
if($PSBoundParameters.ContainsKey('PublicUrl') -or !$demoValues.GOOGLE_REDIRECT_URI){
 $env:GOOGLE_REDIRECT_URI=$PublicUrl.TrimEnd('/')+'/api/v1/auth/oauth2/callback/google'
}else{$env:GOOGLE_REDIRECT_URI=$demoValues.GOOGLE_REDIRECT_URI}
if($PSBoundParameters.ContainsKey('PublicUrl') -or !$demoValues.SECURE_COOKIE){
 $env:SECURE_COOKIE=if($PublicUrl.StartsWith('https://')){'true'}else{'false'}
}else{$env:SECURE_COOKIE=$demoValues.SECURE_COOKIE}
Push-Location $demoRoot
try{
 if(!$SkipBuild){& (Join-Path $PSScriptRoot 'build-apps.ps1')}
 # The image entrypoint only initializes empty volumes. Repair missing databases
 # idempotently before applications/Flyway start against an existing volume.
 & docker compose up -d --wait postgres redis rabbitmq minio
 if($LASTEXITCODE -ne 0){throw 'Infrastructure startup failed.'}
 & docker compose exec -T postgres sh /docker-entrypoint-initdb.d/01-init-databases.sh
 if($LASTEXITCODE -ne 0){throw 'Service database initialization failed.'}
 $backendRoot=Join-Path $demoRoot 'backend'
 foreach($service in 'identity-service','facility-service','schedule-service','booking-service','payment-service','transfer-service','api-gateway'){
  $jar=Join-Path $backendRoot "$service/target/$service-0.0.1-SNAPSHOT.jar"
  foreach($process in Get-CimInstance Win32_Process -Filter "name='java.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains($jar)}){[System.Diagnostics.Process]::GetProcessById([int]$process.ProcessId).Kill()}
 }
 & docker compose --profile apps up -d --no-build --wait --wait-timeout 240
 if($LASTEXITCODE -ne 0){throw 'Application containers did not become healthy. Inspect docker compose --profile apps logs for this project.'}
 Write-Output "READY containerized SportHub: $PublicUrl"
}finally{Pop-Location}
