[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1',[string]$MailpitBase='http://localhost:18025',[switch]$LeavePending,[switch]$PrepareOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'get-demo-facility.ps1')
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$values=@{};[IO.File]::ReadAllLines((Join-Path $demoRoot '.env'))|ForEach-Object {if($_ -match '^([A-Z0-9_]+)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
function Request($method,$path,$body=$null,$token=$null,$key=$null){
 $args=@{Method=$method;Uri="$ApiBase$path";Headers=@{}};if($token){$args.Headers.Authorization="Bearer $token"};if($key){$args.Headers['Idempotency-Key']=$key}
 if($null -ne $body){$args.ContentType='application/json';$args.Body=$body|ConvertTo-Json -Depth 25};(Invoke-RestMethod @args).data
}
function Reject($code,$path,$body,$token=$null){$args=@{Method='POST';Uri="$ApiBase$path";ContentType='application/json';Body=($body|ConvertTo-Json -Depth 10);SkipHttpErrorCheck=$true};if($token){$args.Headers=@{Authorization="Bearer $token"}};$response=Invoke-WebRequest @args;if($response.StatusCode -ne $code){throw "Expected $code, got $($response.StatusCode) for $path"}}
function Sql($db,$query){Push-Location $demoRoot;try{$result=& docker compose exec -T postgres psql -U $values.POSTGRES_USER -d $db -tAc $query;if($LASTEXITCODE -ne 0){throw 'Database verification failed'};($result|Out-String).Trim()}finally{Pop-Location}}
function Paid($session,$member){
 $payment=Request POST '/payments' @{bookingId=$group.id;memberId=$member.id} $session.accessToken ([Guid]::NewGuid().ToString())
 Request POST "/payments/$($payment.id)/demo/complete" @{outcome='SUCCESS'} $session.accessToken|Out-Null
 for($i=0;$i -lt 40;$i++){$current=Request GET "/groups/$($group.id)" $null $customer.accessToken;if(($current.members|Where-Object id -eq $member.id).amountPaid -eq $member.amountDue){return};Start-Sleep -Milliseconds 250};throw 'Contribution was not applied'
}
function Notice($recipient,$groupId){
 for($i=0;$i -lt 60;$i++){
  $mail=@((Invoke-RestMethod "$MailpitBase/api/v1/search?query=to%3A$([Uri]::EscapeDataString($recipient))").messages|Where-Object Subject -eq 'SportHub — Nhắc thanh toán nhóm')
  foreach($m in $mail){$text=(Invoke-RestMethod "$MailpitBase/api/v1/message/$($m.ID)").Text;if($text.Contains($groupId)){return $text}}
  Start-Sleep -Milliseconds 250
 };throw 'Reminder not delivered to Mailpit'
}
$sessions=@();$group=$null
try{
 foreach($identifier in @('customer','customer2','owner','staff')){$sessions+=Request POST '/auth/login' @{identifier="$identifier@sporthub.local";password=$values.DEMO_PASSWORD}}
 $customer,$a,$b,$c=$sessions
 $facility=Get-DemoFacility $ApiBase;$court=@(Request GET "/facilities/$($facility.id)/courts"|Where-Object name -eq 'Sân Pickleball 1')[0]
 $date=(Get-Date).AddDays(7).ToString('yyyy-MM-dd');$slot=@((Request GET "/bookings/availability?courtId=$($court.id)&date=$date").slots|Where-Object state -eq 'AVAILABLE')[0];if(!$slot){throw 'No available reminder fixture slot'}
 $hold=Request POST '/bookings/holds' @{courtId=$court.id;startsAt=$slot.startsAt;endsAt=$slot.endsAt;expectedAmount=$slot.amount} $customer.accessToken ([Guid]::NewGuid().ToString())
 $group=Request POST '/groups' @{holdId=$hold.id;name='Reminder demo '+[Guid]::NewGuid().ToString('N').Substring(0,8);maxMembers=4} $customer.accessToken ([Guid]::NewGuid().ToString())
 $invite=Request GET "/groups/$($group.id)/invite" $null $customer.accessToken
 foreach($session in @($a,$b,$c)){Request POST '/groups/join' @{code=$invite.code} $session.accessToken|Out-Null}
 $group=Request PUT "/groups/$($group.id)/allocations" @{mode='EQUAL'} $customer.accessToken
 $ma=$group.members|Where-Object userId -eq $a.user.id;$mb=$group.members|Where-Object userId -eq $b.user.id;$mc=$group.members|Where-Object userId -eq $c.user.id
 Paid $c $mc
 $path="/groups/$($group.id)/payment-reminders"
 if(!$PrepareOnly){
  Reject 401 $path @{remindAll=$true};foreach($session in @($a,$b,$c)){Reject 403 $path @{remindAll=$true} $session.accessToken}
  Reject 400 $path @{remindAll=$true;memberIds=@($ma.id)} $customer.accessToken
  $before=Request GET "/groups/$($group.id)" $null $customer.accessToken
  $one=Request POST $path @{memberIds=@($ma.id)} $customer.accessToken;if($one.acceptedCount -ne 1){throw 'Single reminder not accepted'}
  $repeat=Request POST $path @{memberIds=@($ma.id)} $customer.accessToken;if($repeat.acceptedCount -ne 0 -or $repeat.members[0].reason -ne 'COOLDOWN' -or !$repeat.members[0].nextReminderAt){throw 'Configured cooldown missing'}
  $all=Request POST $path @{remindAll=$true} $customer.accessToken;if($all.acceptedCount -ne 1 -or @($all.members|Where-Object reason -eq 'PAID').Count -ne 1 -or @($all.members|Where-Object reason -eq 'COOLDOWN').Count -ne 1){throw 'All did not skip paid/cooldown correctly'}
  $after=Request GET "/groups/$($group.id)" $null $customer.accessToken
  if($after.totalPaid -ne $before.totalPaid -or $after.booking.amount -ne $before.booking.amount -or ($after.members|Select-Object id,amountDue,amountPaid,paymentState,paymentRequestedAt|ConvertTo-Json -Compress) -ne ($before.members|Select-Object id,amountDue,amountPaid,paymentState,paymentRequestedAt|ConvertTo-Json -Compress)){throw 'Reminder changed financial state'}
  if(@($after.members|Where-Object lastReminderAt).Count -ne 2){throw 'Last reminder timestamp count wrong'}
  $expectedDeadline=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]$group.deadline,$group.booking.priceSnapshot.timezone).ToString('dd/MM/yyyy HH:mm')
  foreach($session in @($a,$b)){$member=$group.members|Where-Object userId -eq $session.user.id;$expectedAmount=([decimal]($member.amountDue-$member.amountPaid)).ToString('N0',[Globalization.CultureInfo]::GetCultureInfo('vi-VN'))+' VND';$text=Notice $session.user.email $group.id;if(!$text.Contains($expectedAmount) -or !$text.Contains('/customer/groups/'+$group.id) -or !$text.Contains($expectedDeadline)){throw 'Server amount/deadline/group link missing'}}
  if((Sql 'booking_db' "SELECT count(*) FROM event_outbox WHERE routing_key='booking.group.payment.reminder' AND body->>'aggregateId'='$($group.id)';") -ne '2'){throw 'Duplicate reminder outbox events'}
  if((Sql 'identity_db' "SELECT count(*) FROM identity_notifications WHERE subject='SportHub — Nhắc thanh toán nhóm' AND body LIKE '%$($group.id)%';") -ne '2'){throw 'Notification count/dedup incorrect'}
  $paidSkip=Request POST $path @{memberIds=@($mc.id)} $customer.accessToken;if($paidSkip.members[0].reason -ne 'PAID'){throw 'Paid member not skipped'}
 }
 $fixture=@{groupId=$group.id;memberA=$ma.id;memberB=$mb.id;paidMember=$mc.id;ownerEmail=$customer.user.email;deadline=$group.deadline;date=$date};$fixture|ConvertTo-Json|Set-Content (Join-Path $demoRoot 'tmp/demo/group-reminder-fixture.json')
 if(!$LeavePending -and !$PrepareOnly){foreach($session in @($customer,$a,$b)){$member=$group.members|Where-Object userId -eq $session.user.id;Paid $session $member};Reject 409 $path @{remindAll=$true} $customer.accessToken}
 if($PrepareOnly){Write-Output "PASS: real Group/payment fixture $($group.id) prepared for browser"}
 else{Write-Output "PASS: single/all, paid skip, server cooldown, authorization, timestamp/outbox/Identity/Mailpit, unchanged finances. Group $($group.id)"}
}finally{foreach($session in $sessions){Request POST '/auth/logout' $null $session.accessToken|Out-Null}}
