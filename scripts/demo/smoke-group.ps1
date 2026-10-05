[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'get-demo-facility.ps1')
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$demoPassword=([IO.File]::ReadAllLines((Join-Path $demoRoot '.env'))|Where-Object {$_ -match '^DEMO_PASSWORD='}) -replace '^DEMO_PASSWORD=',''
function Request($method,$path,$body=$null,$token=$null,$key=$null){$headers=@{};if($token){$headers.Authorization="Bearer $token"};if($key){$headers['Idempotency-Key']=$key};$args=@{Method=$method;Uri="$ApiBase$path";Headers=$headers};if($null -ne $body){$args.Body=($body|ConvertTo-Json -Depth 20 -Compress);$args.ContentType='application/json'};return (Invoke-RestMethod @args).data}
function Reject($code,$method,$path,$body,$token,$key=$null){try{Request $method $path $body $token $key|Out-Null;throw "Expected $code for $path"}catch{if([int]$_.Exception.Response.StatusCode -ne $code){throw}}}
$customer=Request POST '/auth/login' @{identifier='customer@sporthub.local';password=$demoPassword}
$other=Request POST '/auth/login' @{identifier='customer2@sporthub.local';password=$demoPassword}
$staff=Request POST '/auth/login' @{identifier='staff@sporthub.local';password=$demoPassword}
try {
 $facility=Get-DemoFacility $ApiBase;$court=@(Request GET "/facilities/$($facility.id)/courts" | Where-Object name -eq 'Sân Pickleball 1')[0];$date=(Get-Date).AddDays(5).ToString('yyyy-MM-dd')
 $preview=Request GET "/bookings/availability?courtId=$($court.id)&date=$date";$slot=@($preview.slots|Where-Object state -eq 'AVAILABLE')[0]
 if(!$slot){throw 'No available group demo slot'}
 $hold=Request POST '/bookings/holds' @{courtId=$court.id;startsAt=$slot.startsAt;endsAt=$slot.endsAt;expectedAmount=$slot.amount} $customer.accessToken ([Guid]::NewGuid().ToString())
 $input=@{holdId=$hold.id;name='Runtime group '+[Guid]::NewGuid().ToString('N').Substring(0,8);maxMembers=8};$key=[Guid]::NewGuid().ToString()
 $group=Request POST '/groups' $input $customer.accessToken $key
 if((Request POST '/groups' $input $customer.accessToken $key).id -ne $group.id){throw 'Group create idempotency failed'}
 Reject 403 GET "/groups/$($group.id)" $null $other.accessToken
 $invite=Request GET "/groups/$($group.id)/invite" $null $customer.accessToken
 if(!$invite.qrSvg -or !$invite.code){throw 'Signed invitation or actual QR missing'}
 Reject 403 GET "/groups/invitations?code=forged" $null $other.accessToken
 $group=Request POST '/groups/join' @{code=$invite.code} $other.accessToken
 if(@((Request POST '/groups/join' @{code=$invite.code} $other.accessToken).members).Count -ne 2){throw 'Duplicate join created another member'}
 Reject 403 GET "/groups/$($group.id)/invite" $null $other.accessToken
 Reject 409 GET "/bookings/$($group.id)/payable" $null $customer.accessToken
 $group=Request PUT "/groups/$($group.id)/allocations" @{mode='EQUAL'} $customer.accessToken
 if(($group.members|Measure-Object amountDue -Sum).Sum -ne $group.booking.amount){throw 'Group allocations do not conserve booking total'}
 foreach($session in @($customer,$other)) {
  $member=$group.members|Where-Object userId -eq $session.user.id
  $payKey=[Guid]::NewGuid().ToString();$payment=Request POST '/payments' @{bookingId=$group.id;memberId=$member.id} $session.accessToken $payKey
  if($payment.amount -ne $member.amountDue -or $payment.purpose -ne 'GROUP_CONTRIBUTION'){throw 'Contribution payment reference mismatch'}
  Request POST "/payments/$($payment.id)/demo/complete" @{outcome='SUCCESS'} $session.accessToken|Out-Null
  for($attempt=0;$attempt -lt 30;$attempt++){$current=Request GET "/groups/$($group.id)" $null $session.accessToken;if(($current.members|Where-Object id -eq $member.id).paymentState -eq 'PAID'){break};Start-Sleep -Milliseconds 300}
  if(($current.members|Where-Object id -eq $member.id).paymentState -ne 'PAID'){throw 'Payment event did not update group member'}
  Request POST "/payments/$($payment.id)/demo/complete" @{outcome='SUCCESS'} $session.accessToken|Out-Null
 }
 $final=Request GET "/groups/$($group.id)" $null $customer.accessToken
 if($final.state -ne 'CONFIRMED' -or $final.totalPaid -ne $final.booking.amount -or $final.booking.status -ne 'CONFIRMED'){throw 'Group did not confirm at exactly 100%'}
 $booking=Request GET "/bookings/$($group.id)" $null $customer.accessToken
 if(!$booking.checkinQrSvg -or @($booking.history|Where-Object action -eq 'GROUP_CONFIRMED').Count -ne 1){throw 'Group confirmation QR or idempotent audit failed'}
 Reject 409 PUT "/groups/$($group.id)/allocations" @{mode='EQUAL'} $customer.accessToken
 $hours=Request GET "/schedules/facilities/$($facility.id)/hours" $null $staff.accessToken
 if(!$hours){throw 'Assigned Staff schedule read failed'}
 Reject 403 PUT "/schedules/facilities/$($facility.id)/hours" @{intervals=@()} $staff.accessToken
 Reject 403 GET "/schedules/facilities/$([Guid]::NewGuid())/hours" $null $staff.accessToken
 Write-Output "PASS: signed group invitation, deduplicated membership, immutable allocation total, two real Payment callbacks -> RabbitMQ -> PAID -> CONFIRMED/QR; Staff schedule read/write scope. Group $($group.id)"
} finally {foreach($session in @($customer,$other,$staff)){Request POST '/auth/logout' $null $session.accessToken|Out-Null}}
