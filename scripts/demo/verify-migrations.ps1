[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$migrationUser=([IO.File]::ReadAllLines((Join-Path $demoRoot '.env'))|Where-Object {$_ -match '^POSTGRES_USER='}) -replace '^POSTGRES_USER=',''
Push-Location $demoRoot
try {
 foreach($migrationService in 'identity','facility','schedule','booking','payment','transfer'){
  $migrationFiles=Get-ChildItem -LiteralPath (Join-Path $demoRoot "backend/$migrationService-service/src/main/resources/db/migration") -Filter 'V*__*.sql'
  $migrationExpected=($migrationFiles|ForEach-Object {if($_.Name -match '^V([0-9]+)__'){[int]$Matches[1]}}|Measure-Object -Maximum).Maximum
  $migrationSql='SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;'
  $migrationActual=(& docker compose exec -T postgres psql -U $migrationUser -d "${migrationService}_db" -tAc $migrationSql).Trim()
  if($LASTEXITCODE -ne 0 -or [int]$migrationActual -ne $migrationExpected){throw "Migration mismatch for $migrationService : expected V$migrationExpected, got $migrationActual"}
  $migrationFailed=(& docker compose exec -T postgres psql -U $migrationUser -d "${migrationService}_db" -tAc 'SELECT count(*) FROM flyway_schema_history WHERE NOT success;').Trim()
  if($LASTEXITCODE -ne 0 -or $migrationFailed -ne '0'){throw "Failed migration in $migrationService"}
  Write-Output "PASS $migrationService : Flyway V$migrationActual applied, no failed migrations."
 }
} finally {Pop-Location}
