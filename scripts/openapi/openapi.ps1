[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('validate', 'frontend', 'backend')]
    [string]$Action,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z][a-z0-9-]*$')]
    [string]$Contract,
    [ValidatePattern('^[a-z][a-z0-9-]*$')]
    [string]$ServiceDirectory
)

$ErrorActionPreference = 'Stop'
$generatorImage = 'openapitools/openapi-generator-cli:v7.15.0'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$contractPath = Join-Path $repositoryRoot "contracts\openapi\v1\$Contract.yaml"
if (-not (Test-Path -LiteralPath $contractPath -PathType Leaf)) { throw "Contract not found: $contractPath" }

$dockerRoot = $repositoryRoot.Replace('\', '/')
$dockerContract = "/workspace/contracts/openapi/v1/$Contract.yaml"
docker run --rm --volume "${dockerRoot}:/workspace" $generatorImage validate -i $dockerContract
if ($LASTEXITCODE -ne 0) { throw "OpenAPI validation failed for $Contract." }
if ($Action -eq 'validate') { return }

if ($Action -eq 'frontend') {
    docker run --rm --volume "${dockerRoot}:/workspace" $generatorImage generate -i $dockerContract -g typescript-fetch -o "/workspace/frontend/src/lib/api/generated/$Contract" --additional-properties=supportsES6=true,npmName=@sporthub/$Contract-api,typescriptThreePlus=true
    if ($LASTEXITCODE -ne 0) { throw "Frontend client generation failed for $Contract." }
    return
}

if ([string]::IsNullOrWhiteSpace($ServiceDirectory)) { throw '-ServiceDirectory is required for backend generation.' }
$servicePath = Join-Path $repositoryRoot "backend\$ServiceDirectory"
if (-not (Test-Path -LiteralPath $servicePath -PathType Container)) { throw "Backend service directory not found: $servicePath" }
docker run --rm --volume "${dockerRoot}:/workspace" $generatorImage generate -i $dockerContract -g spring -o "/workspace/backend/$ServiceDirectory/target/generated-sources/openapi" --additional-properties=interfaceOnly=true,useSpringBoot3=true,useJakartaEe=true,skipDefaultInterface=true,openApiNullable=false
if ($LASTEXITCODE -ne 0) { throw "Backend interface/model generation failed for $Contract." }
