[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$demoPath=Join-Path $demoRoot '.env'
if(Test-Path -LiteralPath $demoPath){Write-Output '.env already exists; its values were preserved.';return}
function New-DemoSecret { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48)) }
$demoValues=[ordered]@{
 COMPOSE_PROJECT_NAME='sporthub-demo';POSTGRES_USER='sporthub_demo';POSTGRES_PASSWORD=(New-DemoSecret)
 REDIS_PASSWORD=(New-DemoSecret);RABBITMQ_DEFAULT_USER='sporthub_demo';RABBITMQ_DEFAULT_PASS=(New-DemoSecret)
 MINIO_ROOT_USER='sporthub_demo';MINIO_ROOT_PASSWORD=(New-DemoSecret);JWT_SECRET=(New-DemoSecret)
 DEMO_PAYMENT_CALLBACK_SECRET=(New-DemoSecret);SERVICE_CALL_SECRET=(New-DemoSecret);DEMO_PASSWORD=('Demo!'+(New-DemoSecret))
 POSTGRES_PORT='15432';REDIS_PORT='16379';RABBITMQ_PORT='15682';RABBITMQ_MANAGEMENT_PORT='25672'
 MINIO_API_PORT='19000';MINIO_CONSOLE_PORT='19001';MAILPIT_SMTP_PORT='11025';MAILPIT_UI_PORT='18025'
 GATEWAY_PORT='18080';FRONTEND_PORT='13000';SPRING_PROFILES_ACTIVE='local';DEMO_SEED_ENABLED='true';DEMO_SMS_ENABLED='true'
 MINIO_PUBLIC_ENDPOINT='http://localhost:19000';APP_PUBLIC_URL='http://localhost:13000'
 CORS_ALLOWED_ORIGIN='http://localhost:13000';NEXT_PUBLIC_DEMO_MAILPIT_URL='http://localhost:18025'
 GROUP_PAYMENT_DEADLINE_SECONDS='1800';GROUP_MIN_LEAD_SECONDS='7200'
 TRANSFER_ACQUISITION_SECONDS='600'
}
[IO.File]::WriteAllLines($demoPath,($demoValues.GetEnumerator()|ForEach-Object {"$($_.Key)=$($_.Value)"}))
Write-Output 'Created ignored .env with random local credentials, isolated ports and explicit local/demo opt-in. Credentials were not printed.'
