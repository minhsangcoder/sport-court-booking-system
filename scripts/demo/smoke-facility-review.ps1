[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1',[string]$MailpitBase='http://localhost:18025',[switch]$LeavePending,[string]$DisableCourtId)
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$demoPassword=([IO.File]::ReadAllLines((Join-Path $demoRoot '.env'))|Where-Object {$_ -match '^DEMO_PASSWORD='}) -replace '^DEMO_PASSWORD=',''
function Request($method,$path,$body=$null,$token=$null){$args=@{Method=$method;Uri="$ApiBase$path"};if($token){$args.Headers=@{Authorization="Bearer $token"}};if($null -ne $body){$args.Body=$body|ConvertTo-Json -Depth 20;$args.ContentType='application/json'};(Invoke-RestMethod @args).data}
function Reject($code,$method,$path,$body,$token){try{Request $method $path $body $token|Out-Null;throw "Expected $code for $path"}catch{if([int]$_.Exception.Response.StatusCode -ne $code){throw}}}
$owner=Request POST '/auth/login' @{identifier='owner@sporthub.local';password=$demoPassword}
if($DisableCourtId){$fixtureCourt=Request GET "/owner/courts/$DisableCourtId" $null $owner.accessToken;if($fixtureCourt.code -ne 'REVIEW-1' -or $fixtureCourt.name -ne 'Controlled review court'){throw 'Only the explicitly named review fixture can be disabled'};Request PUT "/owner/courts/$DisableCourtId" @{code=$fixtureCourt.code;name=$fixtureCourt.name;sportCategoryId=$fixtureCourt.sportCategoryId;enabled=$false} $owner.accessToken|Out-Null;Write-Output 'PASS: controlled browser review court disabled';return}
$admin=Request POST '/auth/login' @{identifier='admin@sporthub.local';password=$demoPassword}
$customer=Request POST '/auth/login' @{identifier='customer@sporthub.local';password=$demoPassword}
$input=@{name='Controlled review '+[Guid]::NewGuid().ToString('N').Substring(0,8);phone='+84901111222';addressLine='Demo-only 280 Review Street';province='Hà Nội';district='Cầu Giấy';ward='Dịch Vọng';description='Controlled additional-facility runtime review';timezone='Asia/Ho_Chi_Minh';amenities=@('Bãi đỗ xe');latitude=21.028;longitude=105.78}
$facility=Request POST '/owner/facilities' $input $owner.accessToken
$id=$facility.id
$category=@(Request GET '/sport-categories'|Where-Object active)[0]
$court=Request POST "/owner/facilities/$id/courts" @{code='REVIEW-1';name='Controlled review court';sportCategoryId=$category.id;enabled=$true} $owner.accessToken
$intervals=@();foreach($day in 1..7){$intervals+=@{dayOfWeek=$day;opensAt='06:00';closesAt='22:00';slotMinutes=60}}
Request PUT "/schedules/facilities/$id/hours" @{intervals=$intervals} $owner.accessToken|Out-Null
foreach($day in 1..7){Request POST "/schedules/facilities/$id/pricing" @{dayOfWeek=$day;startsAt='06:00';endsAt='22:00';pricePerSlot=100000;priority=0;label='Controlled review price';effectiveFrom=(Get-Date).ToString('yyyy-MM-dd');currency='VND'} $owner.accessToken|Out-Null}
$fixture=Join-Path $demoRoot 'tmp/demo/review-document.png'
[IO.File]::WriteAllBytes($fixture,[Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jWZkAAAAASUVORK5CYII='))
$doc=(Invoke-RestMethod "$ApiBase/owner/facilities/$id/documents" -Method POST -Headers @{Authorization="Bearer $($owner.accessToken)"} -Form @{file=Get-Item -LiteralPath $fixture}).data
if((Invoke-WebRequest $doc.url).StatusCode -ne 200){throw 'Private signed document not retrievable'}
Reject 403 GET "/owner/facilities/$id/documents" $null $customer.accessToken
Reject 401 GET "/admin/facilities/$id/documents" $null $null
$review=Request POST "/owner/facilities/$id/submit" $null $owner.accessToken
Reject 409 POST "/owner/facilities/$id/submit" $null $owner.accessToken
Reject 409 PUT "/owner/facilities/$id" $input $owner.accessToken
Reject 404 GET "/facilities/$id" $null $null
Reject 403 POST "/admin/facilities/$id/decision" @{action='APPROVE';reason='Controlled approval'} $owner.accessToken
Reject 400 POST "/admin/facilities/$id/decision" @{action='REJECT';reason='short'} $admin.accessToken
Request POST "/admin/facilities/$id/decision" @{action='SUPPLEMENT_REQUIRED';reason='Please provide a clear and complete location document for review'} $admin.accessToken|Out-Null
if((Request GET "/owner/facilities/$id" $null $owner.accessToken).status -ne 'DRAFT'){throw 'Supplement decision did not reopen the draft'}
Request DELETE "/owner/facilities/$id/documents/$($doc.id)" $null $owner.accessToken|Out-Null
if(@(Request GET "/owner/facilities/$id/documents" $null $owner.accessToken).Count -ne 0){throw 'Archived document still appears in the editable owner document list'}
$replacement=(Invoke-RestMethod "$ApiBase/owner/facilities/$id/documents" -Method POST -Headers @{Authorization="Bearer $($owner.accessToken)"} -Form @{file=Get-Item -LiteralPath $fixture}).data
$input.description='Supplemented controlled location information'
Request PUT "/owner/facilities/$id" $input $owner.accessToken|Out-Null
$second=Request POST "/owner/facilities/$id/submit" $null $owner.accessToken
$detail=Request GET "/admin/facilities/$id" $null $admin.accessToken
if($detail.reviews.Count -ne 2 -or @($detail.audit|Where-Object action -eq 'ADMIN_FACILITY_VIEWED').Count -lt 1){throw 'Review history or audited admin access missing'}
$adminDocs=@(Request GET "/admin/facilities/$id/documents" $null $admin.accessToken)
if($adminDocs.Count -ne 2 -or @($adminDocs|Where-Object archived).Count -ne 1){throw 'Admin cannot inspect both original and supplemented private documents'}
$retained=@($adminDocs|Where-Object id -eq $doc.id)[0]
if(!$retained.archived -or (Invoke-WebRequest $retained.url).StatusCode -ne 200){throw 'Submitted original was physically deleted instead of retained'}
$originalReview=@($detail.reviews|Where-Object id -eq $review.id)[0]
$latestReview=@($detail.reviews|Where-Object id -eq $second.id)[0]
if(@($originalReview.snapshot.documents|Where-Object id -eq $doc.id).Count -ne 1 -or @($latestReview.snapshot.documents|Where-Object id -eq $replacement.id).Count -ne 1 -or @($latestReview.snapshot.documents|Where-Object id -eq $doc.id).Count -ne 0){throw 'Submitted document snapshots changed or resubmission references the archived document'}
if($LeavePending){Write-Output "PASS pending review prepared: facility=$id court=$($court.id) review=$($second.id)";return}
Request POST "/admin/facilities/$id/decision" @{action='APPROVE';reason='The controlled additional facility meets the review criteria'} $admin.accessToken|Out-Null
Reject 409 POST "/admin/facilities/$id/decision" @{action='APPROVE';reason='Duplicate approval should not publish a second decision'} $admin.accessToken
if((Request GET "/facilities/$id").status -ne 'ACTIVE'){throw 'Approved facility is not discoverable'}
$date=(Get-Date).AddDays(2).ToString('yyyy-MM-dd');$availability=Request GET "/bookings/availability?courtId=$($court.id)&date=$date"
if(@($availability.slots|Where-Object state -eq 'AVAILABLE').Count -eq 0){throw 'Approved facility is not ready to book'}
$notice=$null
for($attempt=0;$attempt -lt 60;$attempt++){$notice=(Invoke-RestMethod "$MailpitBase/api/v1/messages").messages|Where-Object { $_.Subject -eq 'SportHub facility review: APPROVED' -and (@($_.To|ForEach-Object {$_.Address}) -contains 'owner@sporthub.local') }|Select-Object -First 1;if($notice){$text=(Invoke-RestMethod "$MailpitBase/api/v1/message/$($notice.ID)").Text;if($text.Contains($input.name)){break};$notice=$null};Start-Sleep -Milliseconds 200}
if(!$notice){throw 'Facility→RabbitMQ→Identity→Mailpit approval notice missing'}
# Keep repeatable smoke records while removing the test-only court from new booking choices.
Request PUT "/owner/courts/$($court.id)" @{code='REVIEW-1';name='Controlled review court';sportCategoryId=$category.id;enabled=$false} $owner.accessToken|Out-Null
Write-Output "PASS: facility=$id submit/supplement/resubmit/approve/history/retained private MinIO documents/actor scope/discovery/availability/durable RabbitMQ notice; test court disabled."
