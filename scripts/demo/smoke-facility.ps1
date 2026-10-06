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
$facilitySmokeInput=@{name='Smoke '+[Guid]::NewGuid().ToString('N');phone='+84901234567';contactEmail=' CONTACT+CRUD@EXAMPLE.TEST ';addressLine='Demo smoke address';province='Hà Nội';district='Cầu Giấy';ward='Dịch Vọng';timezone='Asia/Ho_Chi_Minh';amenities=@('Parking')}
$facilitySmokeCreated=(Facility-Request '/owner/facilities' 'Post' $facilitySmokeInput).data
if($facilitySmokeCreated.status -ne 'DRAFT'){throw 'CRUD unexpectedly bypassed approval'}
if((Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)").data.contactEmail -ne 'contact+crud@example.test'){throw 'Owner create/reload contact failed'}
$facilitySmokeInput.contactEmail='updated+crud@example.test'
$facilitySmokeInput.name+=' Updated'
Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)" 'Put' $facilitySmokeInput | Out-Null
if((Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)").data.contactEmail -ne 'updated+crud@example.test'){throw 'Owner edit/reload contact failed'}
$facilitySmokeLegacy=$facilitySmokeInput.Clone();$facilitySmokeLegacy.Remove('contactEmail')
Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)" 'Put' $facilitySmokeLegacy | Out-Null
if((Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)").data.contactEmail -ne 'updated+crud@example.test'){throw 'Legacy PUT erased new contact data'}
# Local private fixture: actual pair update/reload, old-client omission and validation.
$facilitySmokeInput.latitude=21.028;$facilitySmokeInput.longitude=105.78
Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)" 'Put' $facilitySmokeInput | Out-Null
$facilitySmokeInput.latitude=21.0321234;$facilitySmokeInput.longitude=105.7912345
Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)" 'Put' $facilitySmokeInput | Out-Null
$facilitySmokeLocation=(Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)").data
if($facilitySmokeLocation.latitude -ne $facilitySmokeInput.latitude -or $facilitySmokeLocation.longitude -ne $facilitySmokeInput.longitude){throw 'Private location save/reload failed'}
$facilitySmokeLegacy=$facilitySmokeInput.Clone();$facilitySmokeLegacy.Remove('latitude');$facilitySmokeLegacy.Remove('longitude')
Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)" 'Put' $facilitySmokeLegacy | Out-Null
if((Facility-Request "/owner/facilities/$($facilitySmokeCreated.id)").data.latitude -ne $facilitySmokeInput.latitude){throw 'Old-client PUT erased location'}
foreach($field in @('latitude','longitude')){
 $facilitySmokeInvalid=$facilitySmokeInput.Clone();$facilitySmokeInvalid.Remove($field)
 $facilitySmokeResponse=Invoke-WebRequest "$ApiBase/owner/facilities/$($facilitySmokeCreated.id)" -Method Put -Headers $facilitySmokeHeaders -ContentType 'application/json' -Body ($facilitySmokeInvalid|ConvertTo-Json) -SkipHttpErrorCheck
 if($facilitySmokeResponse.StatusCode -ne 400){throw 'Half-pair was not rejected'}
}
foreach($pair in @(@(91,105),@(-91,105),@(21,181),@(21,-181),@('not-numeric',105))){
 $facilitySmokeInvalid=$facilitySmokeInput.Clone();$facilitySmokeInvalid.latitude=$pair[0];$facilitySmokeInvalid.longitude=$pair[1]
 $facilitySmokeResponse=Invoke-WebRequest "$ApiBase/owner/facilities/$($facilitySmokeCreated.id)" -Method Put -Headers $facilitySmokeHeaders -ContentType 'application/json' -Body ($facilitySmokeInvalid|ConvertTo-Json) -SkipHttpErrorCheck
 if($facilitySmokeResponse.StatusCode -ne 400){throw 'Invalid coordinate range/type was not rejected through Gateway'}
}
$facilitySmokeStaff=(Invoke-RestMethod "$ApiBase/auth/login" -Method Post -ContentType 'application/json' -Body (@{identifier='staff@sporthub.local';password=$facilitySmokePassword}|ConvertTo-Json)).data
$facilitySmokeDenied=Invoke-WebRequest "$ApiBase/owner/facilities/$($facilitySmokeCreated.id)" -Method Put -Headers @{Authorization='Bearer '+$facilitySmokeStaff.accessToken} -ContentType 'application/json' -Body ($facilitySmokeInput|ConvertTo-Json) -SkipHttpErrorCheck
if($facilitySmokeDenied.StatusCode -ne 403){throw 'STAFF role incorrectly granted facility editing'}
if(@((Invoke-RestMethod "$ApiBase/bookings/search?q=$([uri]::EscapeDataString($facilitySmokeInput.name))&date=$((Get-Date).AddDays(4).ToString('yyyy-MM-dd'))").data.items).Count -ne 0){throw 'Private coordinate update published a draft'}
# Only the named controlled demo seed is temporarily changed. Always restore it.
. (Join-Path $PSScriptRoot 'get-demo-facility.ps1')
$facilitySmokePublic=Get-DemoFacility $ApiBase
$facilitySmokeOriginal=(Facility-Request "/owner/facilities/$($facilitySmokePublic.id)").data
$facilitySmokeRestore=@{};foreach($field in @('name','phone','contactEmail','addressLine','province','district','ward','description','timezone','amenities','latitude','longitude')){$facilitySmokeRestore[$field]=$facilitySmokeOriginal.$field}
$facilitySmokeMoved=$facilitySmokeRestore.Clone();$facilitySmokeMoved.latitude=21.0321234;$facilitySmokeMoved.longitude=105.7912345
$facilitySmokeSearch="$ApiBase/bookings/search?q=SportHub%20Demo%20Center&date=$((Get-Date).AddDays(4).ToString('yyyy-MM-dd'))"
$facilitySmokeBefore=(Invoke-RestMethod $facilitySmokeSearch).data.items
if(@($facilitySmokeBefore).Count -lt 1){throw 'Controlled demo has no available courts for location verification'}
try {
 Facility-Request "/owner/facilities/$($facilitySmokePublic.id)" 'Put' $facilitySmokeMoved | Out-Null
 $facilitySmokeAfter=(Invoke-RestMethod $facilitySmokeSearch).data.items
 if(@($facilitySmokeAfter).Count -ne @($facilitySmokeBefore).Count -or @($facilitySmokeAfter|Where-Object {$_.facility.id -ne $facilitySmokePublic.id -or $_.facility.latitude -ne $facilitySmokeMoved.latitude -or $_.facility.longitude -ne $facilitySmokeMoved.longitude}).Count){throw 'Discovery did not read the new location'}
 foreach($item in $facilitySmokeAfter){$prior=$facilitySmokeBefore|Where-Object courtId -eq $item.courtId;if($item.fromPrice -ne $prior.fromPrice -or $item.availableSlots -ne $prior.availableSlots -or $item.date -ne $prior.date){throw 'Location edit changed prices/slots/date'}}
 if(($facilitySmokeAfter|ConvertTo-Json -Depth 12) -match 'contactEmail|identityNumber|bankAccountNumber'){throw 'Discovery privacy regression'}
} finally {
 Facility-Request "/owner/facilities/$($facilitySmokePublic.id)" 'Put' $facilitySmokeRestore | Out-Null
 $facilitySmokeRestored=(Facility-Request "/owner/facilities/$($facilitySmokePublic.id)").data
 if($facilitySmokeRestored.latitude -ne $facilitySmokeOriginal.latitude -or $facilitySmokeRestored.longitude -ne $facilitySmokeOriginal.longitude -or $facilitySmokeRestored.addressLine -ne $facilitySmokeOriginal.addressLine){throw 'Demo seed location restore failed'}
}
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
# App services are isolated in Compose; probe the direct service from its container.
Push-Location $facilitySmokeRoot
try {
 $facilitySmokeDirect=& docker compose exec -T facility-service sh -c 'wget -S -O /dev/null --header="X-User-Role: OWNER" --header="X-User-Id: $1" http://localhost:8082/api/v1/owner/facilities 2>&1 || true' -- $facilitySmokeLogin.user.id
 if($LASTEXITCODE -ne 0 -or ($facilitySmokeDirect|Out-String) -notmatch 'HTTP/\S+ 401'){throw 'Direct service did not reject spoofable identity headers'}
} finally { Pop-Location }
Facility-Request '/auth/logout' 'Post' @{} | Out-Null
Write-Output 'PASS: owner facility CRUD/contact and coordinates A-B/reload/legacy PUT/pair/range/type validation/Staff 403/private publication guard/public Discovery unchanged slots+prices+date/seed restored remains DRAFT; courts/maintenance; real MinIO upload/access/replacement/durable cleanup/delete; customer scope and direct header spoofing rejected.'
