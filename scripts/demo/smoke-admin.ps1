[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1',[string]$MailpitBase='http://localhost:18025')
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$demoVariables=@{}
Get-Content (Join-Path $demoRoot '.env') | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){$demoVariables[$Matches[1]]=$Matches[2]} }
$smokeEmail='admin-smoke-'+[Guid]::NewGuid().ToString('N')+'@sporthub.local'
$smokePassword=[Guid]::NewGuid().ToString('N')+'Aa!'
$adminSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$customerSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
function Request([string]$Path,[string]$Method='GET',$Body=$null,[string]$Token='', $Session=$null) {
 $args=@{Uri="$ApiBase$Path";Method=$Method;ContentType='application/json'}
 if($Body){$args.Body=$Body|ConvertTo-Json -Depth 8}
 if($Token){$args.Headers=@{Authorization="Bearer $Token"}}
 if($Session){$args.WebSession=$Session}
 (Invoke-RestMethod @args).data
}
function Status([string]$Path,[int]$Expected,[string]$Token,[string]$Method='GET',$Body=$null,$Session=$null) {
 $args=@{Uri="$ApiBase$Path";Method=$Method;SkipHttpErrorCheck=$true;ContentType='application/json'}
 if($Token){$args.Headers=@{Authorization="Bearer $Token"}}
 if($Body){$args.Body=$Body|ConvertTo-Json -Depth 8}
 if($Session){$args.WebSession=$Session}
 $result=Invoke-WebRequest @args
 if($result.StatusCode -ne $Expected){throw "$Method $Path expected $Expected, received $($result.StatusCode): $($result.Content)"}
}
function Notice([string]$Subject) {
 for($attempt=0;$attempt -lt 40;$attempt++) {
  $message=(Invoke-RestMethod "$MailpitBase/api/v1/messages").messages | Where-Object { $_.Subject -like "*$Subject*" -and (@($_.To|ForEach-Object {$_.Address}) -contains $smokeEmail) } | Select-Object -First 1
  if($message){return (Invoke-RestMethod "$MailpitBase/api/v1/message/$($message.ID)").Text}
  Start-Sleep -Milliseconds 200
 }
 throw "Notification $Subject was not delivered to the controlled account"
}
$registration=Request '/auth/register' 'POST' @{fullName='Admin Controlled Smoke';email=$smokeEmail;password=$smokePassword}
$text=Notice 'Verify'
if($text -notmatch '(?m)code is: ([0-9]{6})'){throw 'Verification code missing'}
Request '/auth/verify' 'POST' @{challengeId=$registration.verificationChallengeId;code=$Matches[1]} | Out-Null
$customer=Request '/auth/login' 'POST' @{identifier=$smokeEmail;password=$smokePassword} '' $customerSession
$me=Request '/users/me' 'GET' $null $customer.accessToken
$admin=Request '/auth/login' 'POST' @{identifier='admin@sporthub.local';password=$demoVariables.DEMO_PASSWORD} '' $adminSession
$token=$admin.accessToken
Status '/admin/accounts' 403 $customer.accessToken
Status '/admin/bookings' 403 $customer.accessToken
Status '/admin/payments' 403 $customer.accessToken
$accounts=@(Request "/admin/accounts?q=$smokeEmail" 'GET' $null $token)
if($accounts.Count -ne 1 -or $accounts[0].id -ne $me.id){throw 'Account search failed'}
Request "/admin/accounts/$($me.id)/roles" 'PUT' @{roles=@('CUSTOMER','STAFF');reason='Controlled smoke capability update'} $token | Out-Null
Status '/users/me' 401 $customer.accessToken
Status '/auth/refresh' 401 '' 'POST' @{} $customerSession
Notice 'roles updated' | Out-Null
$customer=Request '/auth/login' 'POST' @{identifier=$smokeEmail;password=$smokePassword} '' $customerSession
if($customer.user.roles -notcontains 'STAFF'){throw 'Fresh login did not expose the updated roles'}
Request "/admin/accounts/$($me.id)/lock" 'POST' @{reason='Controlled smoke lock';duration='SEVEN_DAYS'} $token | Out-Null
Status '/users/me' 401 $customer.accessToken
Status '/auth/login' 403 '' 'POST' @{identifier=$smokeEmail;password=$smokePassword}
Notice 'account locked' | Out-Null
Request "/admin/accounts/$($me.id)/unlock" 'POST' @{reason='Controlled smoke unlock'} $token | Out-Null
Status '/users/me' 401 $customer.accessToken
$customer=Request '/auth/login' 'POST' @{identifier=$smokeEmail;password=$smokePassword} '' $customerSession
Status '/users/me' 200 $customer.accessToken
$detail=Request "/admin/accounts/$($me.id)" 'GET' $null $token
if(@($detail.audit | Where-Object {$_.action -like 'ADMIN_*'}).Count -ne 3){throw 'Control audit is incomplete'}
if(@($detail.sessions | Where-Object {$_.revoked}).Count -lt 2){throw 'Old sessions were not revoked'}
$bookings=@(Request '/admin/bookings' 'GET' $null $token)
if($bookings.Count){$booking=Request "/admin/bookings/$($bookings[0].id)" 'GET' $null $token;if($booking.checkinToken -or $booking.checkinQrSvg){throw 'Admin must not receive a holder QR'}}
$payments=@(Request '/admin/payments?status=SUCCESS' 'GET' $null $token)
if($payments.Count){$payment=Request "/admin/payments/$($payments[0].id)" 'GET' $null $token;if(!$payment.payment){throw 'Payment monitoring missing order'}}
foreach($path in '/admin/accounts/statistics','/admin/bookings/statistics','/admin/bookings/reconciliation','/admin/payments/statistics'){Request $path 'GET' $null $token | Out-Null}
$owner=Request '/auth/login' 'POST' @{identifier='owner@sporthub.local';password=$demoVariables.DEMO_PASSWORD}
$facilities=@(Request '/owner/facilities' 'GET' $null $owner.accessToken)
if($facilities.Count){Request "/bookings/reports/facility/$($facilities[0].id)" 'GET' $null $owner.accessToken | Out-Null}
Request '/auth/logout' 'POST' @{} $customer.accessToken $customerSession | Out-Null
Request '/auth/logout' 'POST' @{} $token $adminSession | Out-Null
Write-Output 'PASS: real Gateway Admin account search/roles/lock/unlock/all-session revocation/immutable audit notices; Booking and Payment monitoring/aggregates; Owner operational report.'
