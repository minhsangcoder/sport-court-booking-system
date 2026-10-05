[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1')
$ErrorActionPreference='Stop'
$facilitySmokeRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$facilitySmokePassword=([IO.File]::ReadAllLines((Join-Path $facilitySmokeRoot '.env')) | Where-Object {$_ -like 'DEMO_PASSWORD=*'}).Substring(14)
$facilitySmokeSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$facilitySmokeLogin=(Invoke-RestMethod "$ApiBase/auth/login" -Method Post -ContentType 'application/json' -Body (@{identifier='owner@sporthub.local';password=$facilitySmokePassword}|ConvertTo-Json) -WebSession $facilitySmokeSession).data
$facilitySmokeHeaders=@{Authorization='Bearer '+$facilitySmokeLogin.accessToken}
function Facility-Request([string]$Path,[string]$Method='Get',$Body=$null){
 $arguments=@{Uri="$ApiBase$Path";Method=$Method;Headers=$facilitySmokeHeaders}
 if($null -ne $Body){$arguments.ContentType='application/json';$arguments.Body=$Body|ConvertTo-Json -Depth 8}
 Invoke-RestMethod @arguments
}
$facilitySmokeOwned=(Facility-Request '/owner/facilities').data
if(!$facilitySmokeOwned){throw 'Owner demo seed not available'}
$facilitySmokeInput=@{name='Smoke '+[Guid]::NewGuid().ToString('N');phone='+84901234567';addressLine='Demo smoke address';province='Hà Nội';district='Cầu Giấy';ward='Dịch Vọng';timezone='Asia/Ho_Chi_Minh';amenities=@('Parking')}
$facilitySmokeCreated=(Facility-Request '/owner/facilities' 'Post' $facilitySmokeInput).data
if($facilitySmokeCreated.status -ne 'DRAFT'){throw 'CRUD unexpectedly bypassed approval'}
$facilitySmokeInput.name+=' Updated'
Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)" 'Put' $facilitySmokeInput | Out-Null
$facilitySmokeCategory=(Facility-Request '/sport-categories').data | Where-Object active | Select-Object -First 1
$facilitySmokeCourtInput=@{code='SMOKE-1';name='Smoke court';sportCategoryId=$facilitySmokeCategory.id;enabled=$true}
$facilitySmokeCourt=(Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)/courts" 'Post' $facilitySmokeCourtInput).data
$facilitySmokeCourtInput.enabled=$false
Facility-Request "/owner/courts/$($facilitySmokeCourt.id)" 'Put' $facilitySmokeCourtInput | Out-Null
$facilitySmokeMaintenance=(Facility-Request "/owner/courts/$($facilitySmokeCourt.id)/maintenance" 'Post' @{startsAt=[DateTimeOffset]::UtcNow.AddHours(3).ToString('o');endsAt=[DateTimeOffset]::UtcNow.AddHours(4).ToString('o');reason='Runtime smoke maintenance'}).data
Facility-Request "/owner/maintenance/$($facilitySmokeMaintenance.id)" 'Delete' | Out-Null
$facilitySmokeImage=(Invoke-RestMethod "$ApiBase/owner/facilities/$($facilitySmokeCreated.id)/images" -Method Post -Headers $facilitySmokeHeaders -Form @{file=Get-Item (Join-Path $facilitySmokeRoot 'frontend/public/icon-light-32x32.png')}).data
$facilitySmokeImageResponse=Invoke-WebRequest $facilitySmokeImage.url -SkipHttpErrorCheck
if($facilitySmokeImageResponse.StatusCode -ne 200){throw 'MinIO signed image access failed'}
$facilitySmokeReplacement=(Invoke-RestMethod "$ApiBase/owner/facilities/$($facilitySmokeCreated.id)/images/$($facilitySmokeImage.id)" -Method Put -Headers $facilitySmokeHeaders -Form @{file=Get-Item (Join-Path $facilitySmokeRoot 'frontend/public/icon-dark-32x32.png')}).data
if($facilitySmokeReplacement.id -ne $facilitySmokeImage.id -or $facilitySmokeReplacement.objectKey -eq $facilitySmokeImage.objectKey){throw 'Image replacement did not preserve metadata identity and rotate object key'}
if((Invoke-WebRequest $facilitySmokeReplacement.url -SkipHttpErrorCheck).StatusCode -ne 200){throw 'Replacement image was not accessible'}
for($facilityCleanupAttempt=0;$facilityCleanupAttempt -lt 20;$facilityCleanupAttempt++){$facilityOldResponse=Invoke-WebRequest $facilitySmokeImage.url -SkipHttpErrorCheck;if($facilityOldResponse.StatusCode -eq 404){break};Start-Sleep -Milliseconds 300}
if($facilityOldResponse.StatusCode -ne 404){throw 'Old replaced object was not deleted by durable cleanup'}
Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)/images/$($facilitySmokeImage.id)" 'Delete' | Out-Null
$facilitySmokeCustomer=(Invoke-RestMethod "$ApiBase/auth/login" -Method Post -ContentType 'application/json' -Body (@{identifier='customer@sporthub.local';password=$facilitySmokePassword}|ConvertTo-Json)).data
$facilitySmokeDenied=Invoke-WebRequest "$ApiBase/owner/facilities/$($facilitySmokeCreated.id)" -Headers @{Authorization='Bearer '+$facilitySmokeCustomer.accessToken;'X-User-Role'='OWNER';'X-User-Id'=$facilitySmokeLogin.user.id} -SkipHttpErrorCheck
if($facilitySmokeDenied.StatusCode -ne 403){throw 'Facility ownership/role scope was not enforced'}
$facilitySmokeDirect=Invoke-WebRequest "http://localhost:18082/api/v1/owner/facilities" -Headers @{'X-User-Role'='OWNER';'X-User-Id'=$facilitySmokeLogin.user.id} -SkipHttpErrorCheck
if($facilitySmokeDirect.StatusCode -ne 401){throw 'Direct service trusted spoofable identity headers'}
Facility-Request '/auth/logout' 'Post' @{} | Out-Null
Write-Output 'PASS: owner facility CRUD remains DRAFT; courts/maintenance; real MinIO upload/access/replacement/durable cleanup/delete; customer scope and direct header spoofing rejected.'
