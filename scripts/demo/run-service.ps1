[CmdletBinding()]
param([Parameter(Mandatory=$true)][ValidateSet('api-gateway','identity-service','facility-service','schedule-service','booking-service','payment-service','transfer-service')][string]$Service)
$ErrorActionPreference='Stop'
$demoRepositoryRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach($demoEnvLine in [IO.File]::ReadAllLines((Join-Path $demoRepositoryRoot '.env'))) {
  if($demoEnvLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process') }
}
$demoPorts=@{'api-gateway'=18080;'identity-service'=18081;'facility-service'=18082;'schedule-service'=18083;'booking-service'=18084;'payment-service'=18085;'transfer-service'=18086}
$env:SERVER_PORT=[string]$demoPorts[$Service]
$env:IDENTITY_SERVICE_URL='http://localhost:18081'
$env:FACILITY_SERVICE_URL='http://localhost:18082'
$env:SCHEDULE_SERVICE_URL='http://localhost:18083'
$env:BOOKING_SERVICE_URL='http://localhost:18084'
$env:PAYMENT_SERVICE_URL='http://localhost:18085'
$env:TRANSFER_SERVICE_URL='http://localhost:18086'
$env:IDENTITY_DB_URL="jdbc:postgresql://localhost:$($env:POSTGRES_PORT)/identity_db"
$env:FACILITY_DB_URL="jdbc:postgresql://localhost:$($env:POSTGRES_PORT)/facility_db"
$env:SCHEDULE_DB_URL="jdbc:postgresql://localhost:$($env:POSTGRES_PORT)/schedule_db"
$env:BOOKING_DB_URL="jdbc:postgresql://localhost:$($env:POSTGRES_PORT)/booking_db"
$env:PAYMENT_DB_URL="jdbc:postgresql://localhost:$($env:POSTGRES_PORT)/payment_db"
$env:TRANSFER_DB_URL="jdbc:postgresql://localhost:$($env:POSTGRES_PORT)/transfer_db"
$env:RABBITMQ_USERNAME=$env:RABBITMQ_DEFAULT_USER
$env:RABBITMQ_PASSWORD=$env:RABBITMQ_DEFAULT_PASS
$env:MAIL_PORT=$env:MAILPIT_SMTP_PORT
$env:MINIO_ENDPOINT="http://localhost:$($env:MINIO_API_PORT)"
$env:MINIO_ACCESS_KEY=$env:MINIO_ROOT_USER
$env:MINIO_SECRET_KEY=$env:MINIO_ROOT_PASSWORD
if(!$env:DEMO_PAYMENT_CALLBACK_SECRET){throw 'Set a separate DEMO_PAYMENT_CALLBACK_SECRET in .env (at least 32 characters).'}
if(!$env:SERVICE_CALL_SECRET){throw 'Set a separate SERVICE_CALL_SECRET in .env (at least 32 characters).'}
$env:SPRING_DATA_REDIS_HOST='localhost'
$env:SPRING_DATA_REDIS_PORT=$env:REDIS_PORT
$env:SPRING_DATA_REDIS_PASSWORD=$env:REDIS_PASSWORD
$demoJarPath=Join-Path $demoRepositoryRoot "backend/$Service/target/$Service-0.0.1-SNAPSHOT.jar"
if(!(Test-Path -LiteralPath $demoJarPath)) { throw "Build first: mvn -f backend/pom.xml package" }
& java -Xmx256m -jar $demoJarPath
exit $LASTEXITCODE
