[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1')
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$demoPassword=([IO.File]::ReadAllLines((Join-Path $demoRoot '.env'))|Where-Object {$_ -match '^DEMO_PASSWORD='}) -replace '^DEMO_PASSWORD=',''
function Request($method,$path,$body=$null,$token=$null,$key=$null){$headers=@{};if($token){$headers.Authorization="Bearer $token"};if($key){$headers['Idempotency-Key']=$key};$args=@{Method=$method;Uri="$ApiBase$path";Headers=$headers};if($null -ne $body){$args.Body=($body|ConvertTo-Json -Depth 20 -Compress);$args.ContentType='application/json'};return (Invoke-RestMethod @args).data}
function Reject($code,$method,$path,$body,$token,$key=$null){try{Request $method $path $body $token $key|Out-Null;throw "Expected $code for $path"}catch{if([int]$_.Exception.Response.StatusCode -ne $code){throw}}}
$seller=Request POST '/auth/login' @{identifier='customer@sporthub.local';password=$demoPassword}
$buyer=Request POST '/auth/login' @{identifier='customer2@sporthub.local';password=$demoPassword}
$owner=Request POST '/auth/login' @{identifier='owner@sporthub.local';password=$demoPassword}
try {
 $facility=@(Request GET '/facilities')[0];$court=@(Request GET "/facilities/$($facility.id)/courts"|Where-Object name -eq 'Sân Pickleball 1')[0]
 Request PUT "/owner/facilities/$($facility.id)/transfer-policy" @{enabled=$true;minLeadSeconds=3600} $owner.accessToken|Out-Null
 $date=(Get-Date).AddDays(7).ToString('yyyy-MM-dd');$preview=Request GET "/bookings/availability?courtId=$($court.id)&date=$date";$slot=@($preview.slots|Where-Object state -eq 'AVAILABLE')[0];if(!$slot){throw 'No available transfer demo slot'}
 $hold=Request POST '/bookings/holds' @{courtId=$court.id;startsAt=$slot.startsAt;endsAt=$slot.endsAt;expectedAmount=$slot.amount} $seller.accessToken ([Guid]::NewGuid().ToString())
 $booking=Request POST '/bookings' @{holdId=$hold.id} $seller.accessToken ([Guid]::NewGuid().ToString());$payment=Request POST '/payments' @{bookingId=$booking.id} $seller.accessToken ([Guid]::NewGuid().ToString())
 Request POST "/payments/$($payment.id)/demo/complete" @{outcome='SUCCESS'} $seller.accessToken|Out-Null
 for($attempt=0;$attempt -lt 30;$attempt++){$before=Request GET "/bookings/$($booking.id)" $null $seller.accessToken;if($before.booking.status -eq 'CONFIRMED'){break};Start-Sleep -Milliseconds 300}
 if(!$before.checkinToken){throw 'Original booking not confirmed'}
 $listingInput=@{bookingId=$booking.id;price=[math]::Floor([decimal]$booking.amount*0.8);deadline=([DateTimeOffset]$booking.startsAt).AddHours(-1).ToString('o')};$key=[Guid]::NewGuid().ToString()
 $listing=Request POST '/transfers' $listingInput $seller.accessToken $key
 if(!(Request POST '/transfers' $listingInput $seller.accessToken $key).id.Equals($listing.id)){throw 'Listing idempotency failed'}
 $market=@(Request GET '/transfers' $null $buyer.accessToken);$marketJson=$market|ConvertTo-Json -Depth 20
 if($listing.id -notin $market.id -or $marketJson.Contains($seller.user.id) -or $marketJson.Contains($booking.id)){throw 'Marketplace visibility or seller anonymity failed'}
 Reject 403 POST "/transfers/$($listing.id)/acquisitions" $null $seller.accessToken ([Guid]::NewGuid().ToString())
 $acquisition=Request POST "/transfers/$($listing.id)/acquisitions" $null $buyer.accessToken ([Guid]::NewGuid().ToString())
 Reject 409 PUT "/transfers/$($listing.id)" @{price=$listing.price;deadline=$listing.deadline} $seller.accessToken
 if((Request GET "/bookings/$($booking.id)" $null $seller.accessToken).booking.currentHolderId -ne $seller.user.id){throw 'Usage rights moved before payment'}
 $payKey=[Guid]::NewGuid().ToString();$transferPayment=Request POST '/payments' @{bookingId=$booking.id;acquisitionId=$acquisition.id} $buyer.accessToken $payKey
 if($transferPayment.purpose -ne 'TRANSFER' -or $transferPayment.amount -ne $listing.price){throw 'Transfer payment snapshot mismatch'}
 Request POST "/payments/$($transferPayment.id)/demo/complete" @{outcome='SUCCESS'} $buyer.accessToken|Out-Null
 for($attempt=0;$attempt -lt 40;$attempt++){$final=Request GET "/transfers/acquisitions/$($acquisition.id)" $null $buyer.accessToken;if($final.state -eq 'SUCCESS'){break};Start-Sleep -Milliseconds 300}
 if($final.state -ne 'SUCCESS'){throw "Transfer handoff failed: $($final.state)"}
 $after=Request GET "/bookings/$($booking.id)" $null $buyer.accessToken;$old=Request GET "/bookings/$($booking.id)" $null $seller.accessToken
 if($after.booking.currentHolderId -ne $buyer.user.id -or $after.booking.amount -ne $booking.amount -or !$after.checkinToken -or $old.checkinToken){throw 'Holder, immutable price or QR invalidation failed'}
 Request POST "/payments/$($transferPayment.id)/demo/complete" @{outcome='SUCCESS'} $buyer.accessToken|Out-Null
 if(@((Request GET "/bookings/$($booking.id)" $null $buyer.accessToken).history|Where-Object action -eq 'TRANSFER_COMPLETED').Count -ne 1){throw 'Duplicate transfer handoff'}
 Reject 409 POST "/transfers/$($listing.id)/withdraw" $null $seller.accessToken
 if($listing.id -in @(Request GET '/transfers' $null $buyer.accessToken).id){throw 'Completed listing stayed in marketplace'}
 Write-Output "PASS: facility policy, anonymous listing/idempotency, acquisition lock, verified Transfer payment -> RabbitMQ -> durable Booking handoff, unchanged price snapshot, previous-holder QR revoked, one handoff audit. Booking $($booking.id), acquisition $($acquisition.id)."
} finally {foreach($session in @($seller,$buyer,$owner)){Request POST '/auth/logout' $null $session.accessToken|Out-Null}}
