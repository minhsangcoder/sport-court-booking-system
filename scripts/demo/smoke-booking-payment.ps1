[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1')
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$demoPassword=([IO.File]::ReadAllLines((Join-Path $demoRoot '.env')) | Where-Object { $_ -match '^DEMO_PASSWORD=' }) -replace '^DEMO_PASSWORD=',''
function Request($method,$path,$body=$null,$token=$null,$key=$null){$headers=@{};if($token){$headers.Authorization="Bearer $token"};if($key){$headers['Idempotency-Key']=$key};$args=@{Method=$method;Uri="$ApiBase$path";Headers=$headers};if($null -ne $body){$args.Body=($body|ConvertTo-Json -Depth 20 -Compress);$args.ContentType='application/json'};return (Invoke-RestMethod @args).data}
function Reject($code,$method,$path,$body,$token,$key=$null){try{Request $method $path $body $token $key|Out-Null;throw "Expected $code for $path"}catch{if([int]$_.Exception.Response.StatusCode -ne $code){throw}}}
$customer=Request POST '/auth/login' @{identifier='customer@sporthub.local';password=$demoPassword}
$other=Request POST '/auth/login' @{identifier='customer2@sporthub.local';password=$demoPassword}
$owner=Request POST '/auth/login' @{identifier='owner@sporthub.local';password=$demoPassword}
$staff=Request POST '/auth/login' @{identifier='staff@sporthub.local';password=$demoPassword}
try {
 $facility=@(Request GET '/facilities')[0];$court=@(Request GET "/facilities/$($facility.id)/courts" | Where-Object name -eq 'Sân Pickleball 1')[0]
 $date=(Get-Date).AddDays(3).ToString('yyyy-MM-dd');$preview=Request GET "/bookings/availability?courtId=$($court.id)&date=$date"
 $slot=@($preview.slots|Where-Object state -eq 'AVAILABLE')[0];if(!$slot){throw 'No available demo slot'}
 $input=@{courtId=$court.id;startsAt=$slot.startsAt;endsAt=$slot.endsAt;expectedAmount=$slot.amount}
 $holdKey=[Guid]::NewGuid().ToString();$hold=Request POST '/bookings/holds' $input $customer.accessToken $holdKey
 if((Request POST '/bookings/holds' $input $customer.accessToken $holdKey).id -ne $hold.id){throw 'Hold idempotency failed'}
 Reject 409 POST '/bookings/holds' $input $other.accessToken ([Guid]::NewGuid().ToString())
 $bookingKey=[Guid]::NewGuid().ToString();$booking=Request POST '/bookings' @{holdId=$hold.id} $customer.accessToken $bookingKey
 if((Request POST '/bookings' @{holdId=$hold.id} $customer.accessToken $bookingKey).id -ne $booking.id){throw 'Booking idempotency failed'}
 Reject 403 GET "/bookings/$($booking.id)" $null $other.accessToken
 $paymentKey=[Guid]::NewGuid().ToString();$payment=Request POST '/payments' @{bookingId=$booking.id} $customer.accessToken $paymentKey
 if((Request POST '/payments' @{bookingId=$booking.id} $customer.accessToken $paymentKey).id -ne $payment.id){throw 'Payment idempotency failed'}
 Reject 403 GET "/payments/$($payment.id)" $null $other.accessToken
 $result=Request POST "/payments/$($payment.id)/demo/complete" @{outcome='SUCCESS'} $customer.accessToken
 if($result.status -ne 'SUCCESS'){throw 'Provider callback did not persist SUCCESS'}
 $confirmed=$null;for($attempt=0;$attempt -lt 30;$attempt++){$detail=Request GET "/bookings/$($booking.id)" $null $customer.accessToken;if($detail.booking.status -eq 'CONFIRMED'){$confirmed=$detail;break};Start-Sleep -Milliseconds 300}
 if(!$confirmed -or !$confirmed.checkinToken -or !$confirmed.checkinQrSvg){throw 'RabbitMQ payment outcome or signed QR confirmation failed'}
 Request POST "/payments/$($payment.id)/demo/complete" @{outcome='SUCCESS'} $customer.accessToken|Out-Null
 $detail=Request GET "/bookings/$($booking.id)" $null $customer.accessToken
 if(@($detail.history|Where-Object action -eq 'CONFIRMED').Count -ne 1){throw 'Duplicate confirmation'}
 Request POST "/staff-bindings/facilities/$($facility.id)" @{identifier='staff@sporthub.local';permissions=@('BOOKING_READ','BOOKING_CREATE_COUNTER','BOOKING_CHECK_IN','BOOKING_COMPLETE','SCHEDULE_READ')} $owner.accessToken|Out-Null
 $staffList=@(Request GET "/bookings/facility/$($facility.id)" $null $staff.accessToken)
 if($booking.id -notin $staffList.id){throw 'Staff scoped booking list failed'}
 Reject 403 GET "/bookings/facility/$([Guid]::NewGuid())" $null $staff.accessToken
 Reject 409 POST "/bookings/$($booking.id)/check-in" @{token=$confirmed.checkinToken} $staff.accessToken
 Write-Output "PASS: real availability, hold/conflict/idempotency, Booking -> signed provider callback -> RabbitMQ -> CONFIRMED, signed QR, Staff assignment/scope and check-in time guard. Booking $($booking.id)"
} finally {foreach($session in @($customer,$other,$owner,$staff)){Request POST '/auth/logout' $null $session.accessToken|Out-Null}}
