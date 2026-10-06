[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1',[string]$MailpitBase='http://localhost:18025',[switch]$RestartIdentityBeforeRetry)
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$values=@{};foreach($line in [IO.File]::ReadAllLines((Join-Path $demoRoot '.env'))){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
$password=$values.DEMO_PASSWORD
$png=[Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6T1kAAAAASUVORK5CYII=')
function Request($method,$path,$body=$null,$token=$null){
 $args=@{Method=$method;Uri="$ApiBase$path";ContentType='application/json'};if($null -ne $body){$args.Body=$body|ConvertTo-Json -Depth 25};if($token){$args.Headers=@{Authorization="Bearer $token"}};(Invoke-RestMethod @args).data
}
function Expect($code,$method,$path,$body=$null,$token=$null){
 $args=@{Method=$method;Uri="$ApiBase$path";ContentType='application/json';SkipHttpErrorCheck=$true};if($null -ne $body){$args.Body=$body|ConvertTo-Json -Depth 25};if($token){$args.Headers=@{Authorization="Bearer $token"}};$response=Invoke-WebRequest @args;if($response.StatusCode -ne $code){throw "$method $path expected $code, got $($response.StatusCode)"}
}
function Sql($database,$query){Push-Location $demoRoot;try{$result=& docker compose exec -T postgres psql -U $values.POSTGRES_USER -d $database -tAc $query;if($LASTEXITCODE -ne 0){throw 'Database verification failed'};($result|Out-String).Trim()}finally{Pop-Location}}
function Verify($registration,$email){
 for($i=0;$i -lt 40;$i++){$mail=(Invoke-RestMethod "$MailpitBase/api/v1/messages").messages|Where-Object {$_.Subject -like '*Verify*' -and (@($_.To|ForEach-Object {$_.Address}) -contains $email)}|Select-Object -First 1;if($mail){break};Start-Sleep -Milliseconds 200}
 if(!$mail){throw 'Verification email missing'};$text=(Invoke-RestMethod "$MailpitBase/api/v1/message/$($mail.ID)").Text;if($text -notmatch '(?m)code is: ([0-9]{6})'){throw 'Verification code missing'}
 Request POST '/auth/verify' @{challengeId=$registration.verificationChallengeId;code=$Matches[1]}|Out-Null
}
function Multipart($body,$key,$invalid=$false,$expected=201){
 $client=[System.Net.Http.HttpClient]::new();$content=[System.Net.Http.MultipartFormDataContent]::new()
 try{
  $content.Add([System.Net.Http.StringContent]::new(($body|ConvertTo-Json -Depth 25),[Text.Encoding]::UTF8,'application/json'),'request')
  foreach($kind in 'identityDocument','locationDocument','facilityImage'){$bytes=if($invalid -and $kind -eq 'identityDocument'){[Text.Encoding]::UTF8.GetBytes('bad signature')}else{$png};$file=[System.Net.Http.ByteArrayContent]::new($bytes);$file.Headers.ContentType=[System.Net.Http.Headers.MediaTypeHeaderValue]::new('image/png');$content.Add($file,$kind,"$kind.png")}
  $client.DefaultRequestHeaders.Add('Idempotency-Key',$key);$response=$client.PostAsync("$ApiBase/auth/register",$content).GetAwaiter().GetResult()
  if([int]$response.StatusCode -ne $expected){throw "Owner signup expected $expected, got $([int]$response.StatusCode)"}
  if($expected -eq 201){($response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json).data}
 }finally{$content.Dispose();$client.Dispose()}
}
function NewBody($email){
 $category=(Request GET '/sport-categories'|Where-Object active|Select-Object -First 1).id
 @{fullName='Controlled signup applicant';email=$email;password=$password;applyAsOwner=$true;role='OWNER';applicantUserId=[Guid]::NewGuid();ownerApplication=@{state='APPROVED';legal=@{representativeName='Controlled representative';identityNumber='SIGNUP-SMOKE-IDENTITY';businessName='Signup business '+[Guid]::NewGuid().ToString('N').Substring(0,8);bankName='Demo bank';bankAccountHolder='Controlled representative';bankAccountNumber='SIGNUP-SMOKE-BANK'};facility=@{name='Controlled signup facility';phone='+84901111222';contactEmail=' SIGNUP+FACILITY@EXAMPLE.TEST ';addressLine='Demo-only signup address';province='Hà Nội';district='Cầu Giấy';ward='Dịch Vọng';description='Controlled signup fixture';timezone='Asia/Ho_Chi_Minh';latitude=21.0281234;longitude=105.7801234;amenities=@();ownerId=[Guid]::NewGuid();status='ACTIVE'};setup=@{courtCode='SIGNUP-01';courtName='Signup first court';sportCategoryId=$category;days=@(1,2,3,4,5,6,7);opensAt='06:00';closesAt='22:00';slotMinutes=60;pricePerSlot=100000}}}
}
$admin=Request POST '/auth/login' @{identifier='admin@sporthub.local';password=$password}
$offEmail='signup-off-'+[Guid]::NewGuid().ToString('N')+'@sporthub.local'
$off=Request POST '/auth/register' @{fullName='Ordinary signup';email=$offEmail;password=$password;applyAsOwner=$false;ownerApplication='ignored malformed';role='OWNER'}
if($off.ownerApplication -or (Sql identity_db "SELECT count(*) FROM owner_applications WHERE user_id='$($off.userId)'") -ne '0' -or (Sql facility_db "SELECT count(*) FROM facilities WHERE owner_id='$($off.userId)'") -ne '0'){throw 'Owner option OFF produced Owner resources'}
Verify $off $offEmail;$normal=Request POST '/auth/login' @{identifier=$offEmail;password=$password};if(@($normal.user.roles).Count -ne 1 -or $normal.user.roles[0] -ne 'CUSTOMER'){throw 'Normal registration role regression'}
Write-Output 'PASS ordinary signup / false ignores malformed Owner payload and privileged fields.'
$email='owner-signup-'+[Guid]::NewGuid().ToString('N')+'@sporthub.local';$body=NewBody $email;$key=[Guid]::NewGuid().ToString()
Multipart $body $key $true 400
if((Sql identity_db "SELECT count(*) FROM users WHERE email='$email'") -ne '0'){throw 'Invalid file created account'}
$originalName=$body.ownerApplication.facility.name;$body.ownerApplication.facility.name='';Multipart $body $key $false 400;$body.ownerApplication.facility.name=$originalName
if((Sql identity_db "SELECT count(*) FROM users WHERE email='$email'") -ne '0'){throw 'Invalid facility created account'}
$registered=Multipart $body $key
if($RestartIdentityBeforeRetry){
 Push-Location $demoRoot
 try{
  & docker compose restart --no-deps identity-service | Out-Null;if($LASTEXITCODE -ne 0){throw 'Identity restart failed'}
  $container=& docker compose ps -q identity-service
  for($attempt=0;$attempt -lt 60;$attempt++){if((& docker inspect --format '{{.State.Health.Status}}' $container) -eq 'healthy'){break};Start-Sleep -Seconds 1}
  if($attempt -eq 60){throw 'Identity did not become healthy after restart'}
 }finally{Pop-Location}
}
$repeat=Multipart $body $key
$id=[Guid]$registered.ownerApplication.id;$facilityId=[Guid]$registered.ownerApplication.facilityId;$userId=[Guid]$registered.userId;$owner=$null
try{
 if($registered.accountStatus -ne 'PENDING_VERIFICATION' -or $registered.ownerApplication.state -ne 'PENDING_APPROVAL' -or $repeat.userId -ne $userId -or $repeat.ownerApplication.id -ne $id -or $repeat.verificationChallengeId -ne $registered.verificationChallengeId){throw 'Signup/retry invariant failed'}
 if((Sql identity_db "SELECT count(*) FROM user_roles WHERE user_id='$userId'") -ne '0'){throw 'Signup granted a role'}
 if((Sql identity_db "SELECT count(*) FROM owner_applications WHERE user_id='$userId'") -ne '1' -or (Sql facility_db "SELECT count(*) FROM first_facility_applications WHERE application_id='$id' AND facility_id='$facilityId' AND owner_id='$userId'") -ne '1'){throw 'First facility/account linkage failed'}
 if((Sql identity_db "SELECT count(*) FROM owner_signup_receipts WHERE user_id='$userId' AND application_id='$id' AND length(key_hash)=64 AND length(request_hash)=64") -ne '1' -or (Sql facility_db "SELECT status FROM facilities WHERE id='$facilityId'") -ne 'PENDING_APPROVAL'){throw 'Receipt or initial facility state incorrect'}
 if((Sql identity_db "SELECT private_payload NOT LIKE '%SIGNUP-SMOKE-IDENTITY%' AND private_payload NOT LIKE '%SIGNUP-SMOKE-BANK%' FROM owner_applications WHERE id='$id'") -ne 't'){throw 'Legal encryption missing'}
 Expect 404 GET "/facilities/$facilityId";Expect 401 POST '/owner-applications' $body.ownerApplication
 $approval=@{action='APPROVE';reason='Controlled signup application meets reviewed requirements';commissionPercent=5}
 Expect 409 POST "/admin/owner-applications/$id/decision" $approval $admin.accessToken
 Expect 403 POST '/auth/login' @{identifier=$email;password=$password}
 $page=Request GET "/admin/owner-applications/page?state=PENDING_APPROVAL&applicationId=$id&page=0&size=10" $null $admin.accessToken
 if($page.totalElements -ne 1){throw 'Signup application missing from filtered Admin queue'}
 $review=Request GET "/admin/owner-applications/$id" $null $admin.accessToken
 if($review.applicant.id -ne $userId -or $review.reviewSnapshot.facility.contactEmail -ne 'signup+facility@example.test' -or $review.reviewSnapshot.facility.latitude -ne 21.0281234 -or $review.reviewSnapshot.documents.Count -ne 2 -or $review.reviewSnapshot.images.Count -ne 1 -or $review.history.Count -ne 1){throw 'Signup review snapshot/account/files incorrect'}
 if((Sql facility_db "SELECT count(*) FROM courts WHERE facility_id='$facilityId'") -ne '1' -or (Sql facility_db "SELECT count(*) FROM facility_documents WHERE facility_id='$facilityId'") -ne '2' -or (Sql schedule_db "SELECT count(*) FROM operating_hours WHERE facility_id='$facilityId' AND is_active") -ne '7'){throw 'Retry duplicated initial configuration'}
 Write-Output 'PASS real signup persisted once, private first facility, protected files, Admin filters/detail, unverified approval blocked.'
 Verify $registered $email;$customer=Request POST '/auth/login' @{identifier=$email;password=$password}
 if(@($customer.user.roles).Count -ne 1 -or $customer.user.roles[0] -ne 'CUSTOMER'){throw 'Verification must grant Customer only'}
 $docs=@(Request GET "/owner/facilities/$facilityId/documents" $null $customer.accessToken);if($docs.Count -ne 2){throw 'Applicant cannot read signup documents'}
 $images=@(Request GET "/owner/facilities/$facilityId/images" $null $customer.accessToken);if($images.Count -ne 1){throw 'Retry duplicated facility image'}
 foreach($file in @($docs)+@($images)){
  $stored=Invoke-WebRequest -Uri $file.url
  if($stored.StatusCode -ne 200 -or $stored.RawContentLength -ne $png.Length){throw 'Expected signup object missing or incorrect in MinIO'}
 }
 if((Sql facility_db "SELECT count(*) FROM facility_documents WHERE facility_id='$facilityId' AND object_key='private/facility-reviews/' || facility_id || '/' || id") -ne '2' -or (Sql facility_db "SELECT count(*) FROM facility_images WHERE facility_id='$facilityId' AND object_key='facilities/' || facility_id || '/' || id || '.png'") -ne '1'){throw 'Signup storage key ownership mismatch'}
 Write-Output 'PASS two private documents and one image read from real MinIO; deterministic keys belong to first facility.'
 Request POST "/admin/owner-applications/$id/decision" @{action='SUPPLEMENT_REQUIRED';reason='Please revise the first facility contact information and legal details'} $admin.accessToken|Out-Null
 $body.ownerApplication.facility.contactEmail='revised+signup@example.test';$body.ownerApplication.legal.identityNumber='SIGNUP-SMOKE-REVISED'
 Request PUT "/owner/facilities/$facilityId" $body.ownerApplication.facility $customer.accessToken|Out-Null
 Request PUT "/owner-applications/$id/legal" $body.ownerApplication.legal $customer.accessToken|Out-Null
 Request POST "/owner-applications/$id/submit" $null $customer.accessToken|Out-Null
 $revised=Request GET "/admin/owner-applications/$id" $null $admin.accessToken
 if($revised.history.Count -ne 2 -or $revised.reviewSnapshot.facility.contactEmail -ne 'revised+signup@example.test' -or $revised.history[1].facilitySnapshot.facility.contactEmail -ne 'signup+facility@example.test'){throw 'Signup revision mutated retained snapshot'}
 if((Request POST "/admin/owner-applications/$id/decision" $approval $admin.accessToken).state -ne 'APPROVED' -or (Request POST "/admin/owner-applications/$id/decision" $approval $admin.accessToken).state -ne 'APPROVED'){throw 'Approval/replay failed'}
 Expect 401 GET '/users/me' $null $customer.accessToken
 $owner=Request POST '/auth/login' @{identifier=$email;password=$password}
 if($owner.user.roles -notcontains 'OWNER' -or (Request GET "/facilities/$facilityId").status -ne 'ACTIVE'){throw 'Approved Owner/publication failed'}
 if((Sql identity_db "SELECT count(*) FROM user_roles WHERE user_id='$userId' AND role='OWNER'") -ne '1'){throw 'Approval replay duplicated Owner grant'}
 Request GET "/owner/facilities/$facilityId" $null $owner.accessToken|Out-Null
 if((Sql identity_db "SELECT count(*) FROM audit_log WHERE entity_id='$id' AND action='OWNER_APPLICATION_APPROVED'") -ne '1'){throw 'Duplicate approval side effect'}
 $timeline=Request GET "/admin/owner-applications/$id/history?page=0&size=50" $null $admin.accessToken
 if(@($timeline.items|Where-Object action -eq 'OWNER_APPLICATION_SUBMITTED').Count -ne 2){throw 'Signup history missing submissions'}
 Write-Output 'PASS verify / Customer / supplement / resubmit V2 / approve-replay / revoked old session / re-login Owner / public first facility / audit.'
 $rejectEmail='signup-reject-'+[Guid]::NewGuid().ToString('N')+'@sporthub.local';$rejectBody=NewBody $rejectEmail;$rejected=Multipart $rejectBody ([Guid]::NewGuid().ToString());$rejectId=$rejected.ownerApplication.id
 Request POST "/admin/owner-applications/$rejectId/decision" @{action='REJECT';reason='Controlled rejection: supplied location evidence does not meet requirements'} $admin.accessToken|Out-Null
 Verify $rejected $rejectEmail;$rejectedCustomer=Request POST '/auth/login' @{identifier=$rejectEmail;password=$password}
 if($rejectedCustomer.user.roles -contains 'OWNER'){throw 'Rejected signup gained Owner'};Expect 404 GET "/facilities/$($rejected.ownerApplication.facilityId)"
 Write-Output 'PASS rejection before verification preserves account, Customer after verification, private first facility.'
 $fixture=@{email=$email;applicationId=$id;facilityId=$facilityId;rejectedApplicationId=$rejectId;normalEmail=$offEmail};$dir=Join-Path $demoRoot 'tmp/demo';[IO.Directory]::CreateDirectory($dir)|Out-Null;$fixture|ConvertTo-Json|Set-Content (Join-Path $dir 'owner-signup-fixture.json')
}finally{
 if(!$owner -and $email){try{$cleanupLogin=Request POST '/auth/login' @{identifier=$email;password=$password};if($cleanupLogin.user.roles -contains 'OWNER'){$owner=$cleanupLogin}}catch{Write-Warning 'Applicant is not an active Owner; private fixture remains nonpublic.'}}
 if($owner){foreach($court in @(Request GET "/owner/facilities/$facilityId/courts" $null $owner.accessToken)){Request PUT "/owner/courts/$($court.id)" @{code=$court.code;name=$court.name;sportCategoryId=$court.sportCategoryId;description=$court.description;enabled=$false} $owner.accessToken|Out-Null};Write-Output 'PASS controlled public court disabled in finally.'}
}
