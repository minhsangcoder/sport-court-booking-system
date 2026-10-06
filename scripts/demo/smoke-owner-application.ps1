[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1',[string]$MailpitBase='http://localhost:18025',[switch]$LeaveDraft,[switch]$LeavePending,[switch]$RegisterOnly)
$ErrorActionPreference='Stop'
if(@($LeaveDraft,$LeavePending,$RegisterOnly|Where-Object {$_}).Count -gt 1){throw 'Choose at most one preparation mode'}
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$values=@{};foreach($line in [IO.File]::ReadAllLines((Join-Path $demoRoot '.env'))){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
$email='owner-application-'+[Guid]::NewGuid().ToString('N')+'@sporthub.local'
$password=$values.DEMO_PASSWORD
$customerSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
function Request($method,$path,$body=$null,$token=$null,$session=$null){
 $args=@{Method=$method;Uri="$ApiBase$path";ContentType='application/json'}
 if($null -ne $body){$args.Body=$body|ConvertTo-Json -Depth 25};if($token){$args.Headers=@{Authorization="Bearer $token"}}
 if($session){$args.WebSession=$session};(Invoke-RestMethod @args).data
}
function Reject($code,$method,$path,$body=$null,$token=$null){
 $args=@{Method=$method;Uri="$ApiBase$path";ContentType='application/json';SkipHttpErrorCheck=$true;Headers=@{'X-User-Role'='OWNER';'X-User-Roles'='OWNER,ADMIN'}}
 if($token){$args.Headers.Authorization="Bearer $token"};if($null -ne $body){$args.Body=$body|ConvertTo-Json -Depth 20}
 $result=Invoke-WebRequest @args;if($result.StatusCode -ne $code){throw "$method $path expected $code, received $($result.StatusCode)"}
}
function Notice($subject){
 for($attempt=0;$attempt -lt 60;$attempt++){$message=(Invoke-RestMethod "$MailpitBase/api/v1/messages").messages|Where-Object {$_.Subject -like "*$subject*" -and (@($_.To|ForEach-Object {$_.Address}) -contains $email)}|Select-Object -First 1;if($message){return (Invoke-RestMethod "$MailpitBase/api/v1/message/$($message.ID)").Text};Start-Sleep -Milliseconds 200}
 throw "Expected $subject notice for controlled applicant"
}
function Sql($database,$query){Push-Location $demoRoot;try{$result=& docker compose exec -T postgres psql -U $values.POSTGRES_USER -d $database -tAc $query;if($LASTEXITCODE -ne 0){throw 'Fixture database verification failed'};($result|Out-String).Trim()}finally{Pop-Location}}
$registered=Request POST '/auth/register' @{fullName='Controlled Owner Applicant';email=$email;password=$password}
$text=Notice 'Verify';if($text -notmatch '(?m)code is: ([0-9]{6})'){throw 'Verification code missing'}
Request POST '/auth/verify' @{challengeId=$registered.verificationChallengeId;code=$Matches[1]}|Out-Null
$customer=Request POST '/auth/login' @{identifier=$email;password=$password} $null $customerSession
if($customer.user.roles -contains 'OWNER'){throw 'Applicant already has Owner role'}
if($RegisterOnly){Write-Output "PASS verified Customer registered: applicant=$email";return}
$admin=Request POST '/auth/login' @{identifier='admin@sporthub.local';password=$values.DEMO_PASSWORD}
$other=Request POST '/auth/login' @{identifier='customer2@sporthub.local';password=$values.DEMO_PASSWORD}
$input=@{legal=@{representativeName='Controlled representative';identityNumber='OWNER-SMOKE-IDENTITY';businessName='Controlled Owner '+[Guid]::NewGuid().ToString('N').Substring(0,8);bankName='Demo bank';bankAccountHolder='Controlled representative';bankAccountNumber='OWNER-SMOKE-BANK'};facility=@{name='Controlled first facility';phone='+84901111222';addressLine='Demo-only 420 Owner Application Street';province='Hà Nội';district='Cầu Giấy';ward='Dịch Vọng';description='Controlled Owner onboarding fixture';timezone='Asia/Ho_Chi_Minh';latitude=21.028;longitude=105.78;amenities=@('Bãi đỗ xe')}}
Reject 403 POST '/owner/facilities' $input.facility $customer.accessToken
$application=Request POST '/owner-applications' $input $customer.accessToken
$id=[Guid]$application.id;$facilityId=[Guid]$application.facilityId;$userId=[Guid]$customer.user.id
Write-Output "FIXTURE applicant=$email application=$id facility=$facilityId"
Reject 409 POST '/owner-applications' $input $customer.accessToken
Request POST "/owner-applications/$id/initialize" $null $customer.accessToken|Out-Null
Request POST "/owner-applications/$id/initialize" $null $customer.accessToken|Out-Null
Reject 403 GET "/owner-applications/$id" $null $other.accessToken
Reject 403 GET "/admin/owner-applications/$id" $null $customer.accessToken
Reject 404 GET "/facilities/$facilityId"
$own=Request GET "/owner-applications/$id" $null $customer.accessToken
if($own.legal.identityNumber -ne $input.legal.identityNumber){throw 'Own protected detail mismatch'}
if((Sql identity_db "SELECT private_payload NOT LIKE '%OWNER-SMOKE-IDENTITY%' AND private_payload NOT LIKE '%OWNER-SMOKE-BANK%' FROM owner_applications WHERE id='$id'") -ne 't'){throw 'Legal data is not encrypted'}
$summary=Request GET '/owner-applications' $null $customer.accessToken|ConvertTo-Json -Depth 10
if($summary -match 'OWNER-SMOKE-IDENTITY|OWNER-SMOKE-BANK'){throw 'List response leaks legal data'}
$category=@(Request GET '/sport-categories'|Where-Object active)[0]
$court=Request POST "/owner/facilities/$facilityId/courts" @{code='OWNER-APP-1';name='Controlled application court';sportCategoryId=$category.id;enabled=$true} $customer.accessToken
$hours=@();foreach($day in 1..7){$hours+=@{dayOfWeek=$day;opensAt='06:00';closesAt='22:00';slotMinutes=60}}
Request PUT "/schedules/facilities/$facilityId/hours" @{intervals=$hours} $customer.accessToken|Out-Null
foreach($day in 1..7){Request POST "/schedules/facilities/$facilityId/pricing" @{dayOfWeek=$day;startsAt='06:00';endsAt='22:00';pricePerSlot=100000;priority=0;label='Controlled application price';effectiveFrom=(Get-Date).ToString('yyyy-MM-dd');currency='VND'} $customer.accessToken|Out-Null}
$fixture=Join-Path $demoRoot 'tmp/demo/owner-application-document.png';[IO.Directory]::CreateDirectory((Split-Path $fixture))|Out-Null
[IO.File]::WriteAllBytes($fixture,[Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jWZkAAAAASUVORK5CYII='))
foreach($n in 1..2){$doc=(Invoke-RestMethod "$ApiBase/owner/facilities/$facilityId/documents" -Method POST -Headers @{Authorization="Bearer $($customer.accessToken)"} -Form @{file=Get-Item -LiteralPath $fixture}).data;if((Invoke-WebRequest $doc.url).StatusCode -ne 200){throw 'Protected document unavailable'}}
Invoke-RestMethod "$ApiBase/owner/facilities/$facilityId/images" -Method POST -Headers @{Authorization="Bearer $($customer.accessToken)"} -Form @{file=Get-Item -LiteralPath $fixture}|Out-Null
if($LeaveDraft){Write-Output "PASS draft prepared: applicant=$email application=$id court=$($court.id)";return}
$submitted=Request POST "/owner-applications/$id/submit" $null $customer.accessToken
if($submitted.state -ne 'PENDING_APPROVAL'){throw "Submission did not finish: $($submitted.state); $($submitted.lastError)"}
Request POST "/owner-applications/$id/submit" $null $customer.accessToken|Out-Null
# Current access context removes APPLICATION_EDIT once submitted; stale contexts are
# additionally stopped by each service's frozen-state 409 guard (integration tests).
Reject 403 PUT "/schedules/facilities/$facilityId/hours" @{intervals=$hours} $customer.accessToken
Reject 403 PUT "/owner/facilities/$facilityId" $input.facility $customer.accessToken
Reject 409 POST "/admin/facilities/$facilityId/decision" @{action='APPROVE';reason='Use the linked application decision'} $admin.accessToken
Reject 403 POST "/admin/owner-applications/$id/decision" @{action='APPROVE';reason='Forged caller';commissionPercent=5} $customer.accessToken
$queue=@(Request GET "/admin/owner-applications?state=PENDING_APPROVAL&q=$([uri]::EscapeDataString($input.legal.businessName))" $null $admin.accessToken)
if(@($queue|Where-Object id -eq $id).Count -ne 1){throw 'Admin review queue missing application'}
$review=Request GET "/admin/owner-applications/$id" $null $admin.accessToken
if($review.history.Count -ne 1 -or $review.legal.identityNumber -ne $input.legal.identityNumber){throw 'Admin full detail/history missing'}
if($LeavePending){Write-Output "PASS pending prepared: applicant=$email application=$id court=$($court.id)";return}
Reject 400 POST "/admin/owner-applications/$id/decision" @{action='REJECT';reason='short'} $admin.accessToken
$supplement=@{action='SUPPLEMENT_REQUIRED';reason='Please provide complete and legible location and identity documents'}
if((Request POST "/admin/owner-applications/$id/decision" $supplement $admin.accessToken).state -ne 'SUPPLEMENT_REQUIRED'){throw 'Supplement did not reopen application'}
$input.legal.identityNumber='OWNER-SMOKE-IDENTITY-REVISED'
Request PUT "/owner-applications/$id/legal" $input.legal $customer.accessToken|Out-Null
Request PUT "/schedules/facilities/$facilityId/hours" @{intervals=$hours} $customer.accessToken|Out-Null
if((Request POST "/owner-applications/$id/submit" $null $customer.accessToken).state -ne 'PENDING_APPROVAL'){throw 'Resubmission failed'}
Request POST "/admin/accounts/$userId/lock" @{reason='Controlled locked applicant check';duration='SEVEN_DAYS'} $admin.accessToken|Out-Null
$approval=@{action='APPROVE';reason='Controlled application meets the reviewed legal and facility requirements';commissionPercent=5}
Reject 409 POST "/admin/owner-applications/$id/decision" $approval $admin.accessToken
Reject 404 GET "/facilities/$facilityId"
Request POST "/admin/accounts/$userId/unlock" @{reason='Controlled applicant check complete'} $admin.accessToken|Out-Null
$customer=Request POST '/auth/login' @{identifier=$email;password=$password} $null $customerSession
if((Request POST "/admin/owner-applications/$id/decision" $approval $admin.accessToken).state -ne 'APPROVED'){throw 'Approval did not commit'}
if((Request POST "/admin/owner-applications/$id/decision" $approval $admin.accessToken).state -ne 'APPROVED'){throw 'Approval replay failed'}
Reject 401 GET '/users/me' $null $customer.accessToken
$owner=Request POST '/auth/login' @{identifier=$email;password=$password}
if($owner.user.roles -notcontains 'OWNER'){throw 'New session lacks Owner capability'}
if((Request GET "/facilities/$facilityId").status -ne 'ACTIVE'){throw 'Approved facility is not public'}
if(@(Request GET '/owner/facilities' $null $owner.accessToken|Where-Object id -eq $facilityId).Count -ne 1){throw 'Owner portal does not list the first facility'}
Request PUT "/schedules/facilities/$facilityId/hours" @{intervals=$hours} $owner.accessToken|Out-Null
if((Sql identity_db "SELECT count(*) FROM user_roles WHERE user_id='$userId' AND role='OWNER'") -ne '1'){throw 'Owner role duplicated'}
if((Sql identity_db "SELECT count(*) FROM audit_log WHERE entity_id='$id' AND action='OWNER_APPLICATION_APPROVED'") -ne '1'){throw 'Approval audit duplicated'}
if((Sql payment_db "SELECT count(*) FROM owner_wallets WHERE owner_id='$userId' AND application_id='$id' AND available_balance=0 AND commission_percent=5") -ne '1'){throw 'Wallet preparation invalid'}
$final=Request GET "/admin/owner-applications/$id" $null $admin.accessToken
if($final.history.Count -ne 2 -or @($final.audit|Where-Object action -eq 'ADMIN_OWNER_APPLICATION_VIEWED').Count -lt 1){throw 'Submission history or sensitive access audit missing'}
if($final.applicant.id -ne $userId -or $final.applicant.email -ne $email){throw 'Admin applicant account summary missing'}
$timeline=Request GET "/admin/owner-applications/$id/history?page=0&size=100" $null $admin.accessToken
if($timeline.totalElements -ne 8 -or @($timeline.items|Where-Object action -eq 'OWNER_APPLICATION_APPROVED').Count -ne 1){throw 'Revision history or approval replay history invalid'}
$revision=@($timeline.items|Where-Object action -eq 'OWNER_APPLICATION_SUPPLEMENT_REQUIRED')[0]
if($revision.reason -ne $supplement.reason -or $revision.actorId -ne $admin.user.id -or $revision.toState -ne 'SUPPLEMENT_REQUIRED'){throw 'Reviewer/reason/state missing from history'}
if(@($timeline.items|Where-Object {$_.submissionOrigin -eq 'SUPPLEMENT_REQUIRED' -and $_.submissionId}).Count -ne 1){throw 'Resubmission event missing'}
if(@($timeline.items|Where-Object {$_.action -eq 'OWNER_APPLICATION_UPDATED' -and $_.changedFields -contains 'identityNumber'}).Count -ne 1){throw 'Edit metadata missing'}
if(($timeline|ConvertTo-Json -Depth 12) -match 'OWNER-SMOKE-IDENTITY|OWNER-SMOKE-BANK|private_snapshot|private_payload'){throw 'History exposes sensitive values'}
Reject 403 GET "/admin/owner-applications/$id/history" $null $owner.accessToken
Reject 400 GET "/admin/owner-applications/$id/history?size=101" $null $admin.accessToken
$page=Request GET "/admin/owner-applications/$id/history?page=1&size=3" $null $admin.accessToken
if($page.page -ne 1 -or $page.items.Count -ne 3 -or $page.totalElements -ne 8){throw 'History pagination invalid'}
if((Sql identity_db "SELECT count(*) FROM audit_log WHERE entity_id='$id' AND action='OWNER_APPLICATION_APPROVED' AND old_value->>'state'='APPROVING' AND new_value->>'state'='APPROVED'") -ne '1'){throw 'Safe before/after approval audit missing'}
@{applicationId=$id;facilityId=$facilityId;applicantEmail=$email}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $demoRoot 'tmp/demo/owner-history-fixture.json') -Encoding utf8
$notice=Notice 'Owner application APPROVED';if($notice -notmatch 'Sign in again'){throw 'Approval guide missing'}
Request PUT "/owner/courts/$($court.id)" @{code='OWNER-APP-1';name='Controlled application court';sportCategoryId=$category.id;enabled=$false} $owner.accessToken|Out-Null
Write-Output 'PASS: real Owner draft/encrypted DB/scoped applicant workspace/frozen submission/revision/edit/resubmit/locked applicant/admin review/idempotent approval/zero wallet/new Owner session/publication/safe audit/paged history/account/Mailpit guide. Fixture court disabled.'
