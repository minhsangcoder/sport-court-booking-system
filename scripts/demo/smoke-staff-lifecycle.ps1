[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'get-demo-facility.ps1')
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$demoPassword=([IO.File]::ReadAllLines((Join-Path $demoRoot '.env')) | Where-Object {$_ -match '^DEMO_PASSWORD='}) -replace '^DEMO_PASSWORD=',''
function Request($method,$path,$body=$null,$token=$null,$key=$null){$headers=@{};if($token){$headers.Authorization="Bearer $token"};if($key){$headers['Idempotency-Key']=$key};$args=@{Method=$method;Uri="$ApiBase$path";Headers=$headers};if($null -ne $body){$args.Body=($body|ConvertTo-Json -Depth 20 -Compress);$args.ContentType='application/json'};return (Invoke-RestMethod @args).data}
function Reject($code,$path,$body,$token){try{Request POST $path $body $token|Out-Null;throw "Expected $code"}catch{if([int]$_.Exception.Response.StatusCode -ne $code){throw}}}
$owner=Request POST '/auth/login' @{identifier='owner@sporthub.local';password=$demoPassword}
$staff=Request POST '/auth/login' @{identifier='staff@sporthub.local';password=$demoPassword}
$customer=Request POST '/auth/login' @{identifier='customer@sporthub.local';password=$demoPassword}
try {
 $facility=Get-DemoFacility $ApiBase;$category=@(Request GET '/sport-categories' | Where-Object active)[0]
 $tag=[Guid]::NewGuid().ToString('N').Substring(0,8)
 $court=Request POST "/owner/facilities/$($facility.id)/courts" @{code="QA-$tag";name="Staff lifecycle QA $tag";sportCategoryId=$category.id;enabled=$true} $owner.accessToken
 $zone=[TimeZoneInfo]::FindSystemTimeZoneById('SE Asia Standard Time')
 $local=[TimeZoneInfo]::ConvertTime([DateTimeOffset]::UtcNow,$zone)
 $start=[DateTimeOffset]::new($local.Year,$local.Month,$local.Day,$local.Hour,$local.Minute,0,$local.Offset).AddMinutes(2);$end=$start.AddMinutes(5)
 $day=([int]$start.DayOfWeek+6)%7+1
 Request PUT "/schedules/facilities/$($facility.id)/hours" @{courtId=$court.id;intervals=@(@{dayOfWeek=$day;opensAt=$start.ToString('HH:mm:ss');closesAt=$end.ToString('HH:mm:ss');slotMinutes=5})} $owner.accessToken|Out-Null
 Request POST "/schedules/facilities/$($facility.id)/pricing" @{courtId=$court.id;dayOfWeek=$day;startsAt=$start.ToString('HH:mm:ss');endsAt=$end.ToString('HH:mm:ss');pricePerSlot=15000;priority=100;label='Controlled Staff lifecycle QA';effectiveFrom=$start.ToString('yyyy-MM-dd');effectiveTo=$start.ToString('yyyy-MM-dd');currency='VND'} $owner.accessToken|Out-Null
 $hold=Request POST '/bookings/holds' @{courtId=$court.id;startsAt=$start.ToUniversalTime().ToString('o');endsAt=$end.ToUniversalTime().ToString('o');expectedAmount=15000} $customer.accessToken ([Guid]::NewGuid().ToString())
 $booking=Request POST '/bookings' @{holdId=$hold.id} $customer.accessToken ([Guid]::NewGuid().ToString())
 $payment=Request POST '/payments' @{bookingId=$booking.id} $customer.accessToken ([Guid]::NewGuid().ToString())
 Request POST "/payments/$($payment.id)/demo/complete" @{outcome='SUCCESS'} $customer.accessToken|Out-Null
 for($attempt=0;$attempt -lt 30;$attempt++){$detail=Request GET "/bookings/$($booking.id)" $null $customer.accessToken;if($detail.booking.status -eq 'CONFIRMED'){break};Start-Sleep -Milliseconds 300}
 if($detail.booking.status -ne 'CONFIRMED'){throw 'Confirmation did not arrive'}
 Reject 403 "/bookings/$($booking.id)/check-in" @{token=$detail.checkinToken} $customer.accessToken
 $checked=Request POST "/bookings/$($booking.id)/check-in" @{token=$detail.checkinToken} $staff.accessToken
 if($checked.status -ne 'CHECKED_IN'){throw 'Staff check-in failed'}
 Reject 409 "/bookings/$($booking.id)/check-in" @{token=$detail.checkinToken} $staff.accessToken
 Reject 409 "/bookings/$($booking.id)/complete" $null $staff.accessToken
 Write-Output "CHECKED_IN: $($booking.id). Waiting for actual scheduled end $($end.ToString('o')); no clock override."
 while([DateTimeOffset]::UtcNow -lt $end.AddSeconds(1)){Start-Sleep -Seconds 1}
 $completed=Request POST "/bookings/$($booking.id)/complete" $null $staff.accessToken
 if($completed.status -ne 'COMPLETED'){throw 'Staff completion failed'}
 $final=Request GET "/bookings/$($booking.id)" $null $customer.accessToken
 if(@($final.history|Where-Object action -eq 'CHECKED_IN').Count -ne 1 -or @($final.history|Where-Object action -eq 'COMPLETED').Count -ne 1){throw 'Lifecycle audit mismatch'}
 Request PUT "/owner/courts/$($court.id)" @{code=$court.code;name=$court.name;sportCategoryId=$court.sportCategoryId;enabled=$false} $owner.accessToken|Out-Null
 Write-Output 'PASS: real Gateway, assigned Staff check-in, duplicate/role/time guards, completion after actual end and customer audit history.'
} finally {foreach($session in @($owner,$staff,$customer)){Request POST '/auth/logout' $null $session.accessToken|Out-Null}}
