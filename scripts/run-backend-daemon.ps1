$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$logRoot = Join-Path $repositoryRoot 'tmp/backend-logs'
if (!(Test-Path $logRoot)) { New-Item -ItemType Directory -Path $logRoot -Force | Out-Null }

$runtimeConfig = @{}
foreach ($line in [IO.File]::ReadAllLines((Join-Path $repositoryRoot '.env'))) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        $runtimeConfig[$Matches[1]] = $Matches[2]
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}

$services = [ordered]@{
    'identity-service' = 18081
    'facility-service' = 18082
    'schedule-service' = 18083
    'booking-service'  = 18084
    'payment-service'  = 18085
    'transfer-service' = 18086
    'api-gateway'      = 18080
}

# Stop any previous backend JAR processes
foreach ($service in $services.Keys) {
    $jarName = "$service-0.0.1-SNAPSHOT.jar"
    $old = Get-CimInstance Win32_Process -Filter "name='java.exe'" -ErrorAction SilentlyContinue | Where-Object {
        $_.CommandLine -and $_.CommandLine.IndexOf($jarName, [StringComparison]::OrdinalIgnoreCase) -ge 0
    }
    foreach ($entry in $old) {
        try {
            $p = [Diagnostics.Process]::GetProcessById([int]$entry.ProcessId)
            $p.Kill()
            [void]$p.WaitForExit(5000)
            Write-Output "Stopped previous $service (PID $($entry.ProcessId))"
        } catch {}
    }
}

Remove-Item Env:SERVER_ADDRESS -ErrorAction SilentlyContinue
$env:APP_PUBLIC_URL = 'http://localhost:3001'
$env:FRONTEND_BASE_URL = $env:APP_PUBLIC_URL
$env:CORS_ALLOWED_ORIGIN = $env:APP_PUBLIC_URL
$env:SECURE_COOKIE = 'false'
$env:DEMO_SEED_ENABLED = 'false'
$env:TRANSFER_FINANCE_ENABLED = 'true'
$env:MANAGEMENT_HEALTH_MAIL_ENABLED = 'false'

foreach ($service in @('identity', 'facility', 'schedule', 'booking', 'payment', 'transfer')) {
    [Environment]::SetEnvironmentVariable($service.ToUpper() + '_SERVICE_URL', "http://127.0.0.1:$($services["$service-service"])", 'Process')
    [Environment]::SetEnvironmentVariable($service.ToUpper() + '_DB_URL', "jdbc:postgresql://127.0.0.1:$($runtimeConfig.POSTGRES_PORT)/${service}_db", 'Process')
}

$env:RABBITMQ_HOST = '127.0.0.1'
$env:RABBITMQ_PORT = $runtimeConfig.RABBITMQ_PORT
$env:RABBITMQ_USERNAME = $runtimeConfig.RABBITMQ_DEFAULT_USER
$env:RABBITMQ_PASSWORD = $runtimeConfig.RABBITMQ_DEFAULT_PASS
$env:MINIO_ENDPOINT = "http://127.0.0.1:$($runtimeConfig.MINIO_API_PORT)"
$env:MINIO_ACCESS_KEY = $runtimeConfig.MINIO_ROOT_USER
$env:MINIO_SECRET_KEY = $runtimeConfig.MINIO_ROOT_PASSWORD
$env:SPRING_DATA_REDIS_HOST = '127.0.0.1'
$env:SPRING_DATA_REDIS_PORT = $runtimeConfig.REDIS_PORT
$env:SPRING_DATA_REDIS_PASSWORD = $runtimeConfig.REDIS_PASSWORD
$env:MAIL_PORT = if ($runtimeConfig.MAIL_PORT) { $runtimeConfig.MAIL_PORT } else { '25' }

$java = 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe'
if (!(Test-Path -LiteralPath $java)) {
    throw "JDK 21 executable not found at $java"
}

$processes = @{}

foreach ($service in $services.Keys) {
    $port = $services[$service]
    $env:SERVER_PORT = [string]$port
    $jar = [IO.Path]::GetFullPath((Join-Path $repositoryRoot "backend/$service/target/$service-0.0.1-SNAPSHOT.jar"))
    if (!(Test-Path -LiteralPath $jar)) {
        throw "JAR not found: $jar"
    }

    $stdoutLog = Join-Path $logRoot "$service.log"
    $stderrLog = Join-Path $logRoot "$service.err.log"

    Write-Output "Starting $service on port $port..."
    $proc = Start-Process -FilePath $java -ArgumentList @(
        '-Xmx256m',
        '-Dmanagement.health.mail.enabled=false',
        '-jar',
        "`"$jar`""
    ) -WorkingDirectory $repositoryRoot -WindowStyle Hidden -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru

    $processes[$service] = $proc
    Write-Output "Launched $service with PID $($proc.Id)"

    # Wait for service health check
    $ready = $false
    for ($attempt = 1; $attempt -le 90; $attempt++) {
        if ($proc.HasExited) {
            Get-Content -LiteralPath $stderrLog -Tail 30 -ErrorAction SilentlyContinue
            Get-Content -LiteralPath $stdoutLog -Tail 30 -ErrorAction SilentlyContinue
            throw "$service exited unexpectedly with exit code $($proc.ExitCode)"
        }
        $probe = & curl.exe --noproxy '*' --silent --max-time 2 --header 'Accept: application/json' "http://127.0.0.1:$port/actuator/health"
        if ($LASTEXITCODE -eq 0 -and $probe) {
            try {
                $health = $probe | ConvertFrom-Json
                if ($health.status -eq 'UP') {
                    $ready = $true
                    break
                }
            } catch {}
        }
        if ($attempt % 10 -eq 0) {
            Write-Output "Waiting for $service ($attempt/90s)..."
        }
        Start-Sleep -Milliseconds 1000
    }

    if (!$ready) {
        throw "$service did not become ready in 90s. See $stdoutLog / $stderrLog"
    }
    Write-Output "READY: $service on http://127.0.0.1:$port"
}

Write-Output "========================================="
Write-Output "ALL 7 SPORTHUB BACKEND SERVICES ARE UP!"
Write-Output "API Gateway: http://127.0.0.1:18080"
Write-Output "========================================="

# Keep daemon process alive and monitor child services
while ($true) {
    Start-Sleep -Seconds 15
    foreach ($service in $services.Keys) {
        $p = $processes[$service]
        if ($p -and $p.HasExited) {
            Write-Warning "$service (PID $($p.Id)) has exited with code $($p.ExitCode)!"
        }
    }
}
