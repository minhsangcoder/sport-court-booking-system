[CmdletBinding()]
param([string[]]$Services=@('identity-service','facility-service','schedule-service','booking-service','payment-service','transfer-service','api-gateway','frontend'))
$ErrorActionPreference='Stop'
$known=@('identity-service','facility-service','schedule-service','booking-service','payment-service','transfer-service','api-gateway','frontend')
if(@($Services|Where-Object {$_ -notin $known}).Count){throw 'Only known SportHub application services may be built.'}
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $demoRoot
try{
 & docker compose --profile apps config --quiet
 if($LASTEXITCODE -ne 0){throw 'Invalid demo Compose configuration'}
 foreach($service in $Services){
  Write-Output "BUILD $service"
  & docker compose --profile apps build $service
  if($LASTEXITCODE -ne 0){throw "Application image build failed: $service"}
 }
}finally{Pop-Location}
