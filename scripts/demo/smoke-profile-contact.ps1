[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1',[string]$MailpitBase='http://localhost:18025',[switch]$LeavePending)
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$suffix=[Guid]::NewGuid().ToString('N')
$original="contact-$suffix@sporthub.local";$target="contact-new-$suffix@sporthub.local"
$password=[Guid]::NewGuid().ToString('N')+'Aa!'
function Request($method,$path,$body=$null,$token=$null){$args=@{Method=$method;Uri="$ApiBase$path"};if($token){$args.Headers=@{Authorization="Bearer $token"}};if($null -ne $body){$args.Body=$body|ConvertTo-Json -Depth 12;$args.ContentType='application/json'};(Invoke-RestMethod @args).data}
function Reject($code,$method,$path,$body,$token){try{Request $method $path $body $token|Out-Null;throw "Expected $code for $path"}catch{if([int]$_.Exception.Response.StatusCode -ne $code){throw}}}
function Code($recipient){$destination=if($recipient.StartsWith('+')){$recipient.Substring(1)+'@sms.sporthub.local'}else{$recipient};for($attempt=0;$attempt -lt 25;$attempt++){$message=(Invoke-RestMethod "$MailpitBase/api/v1/messages").messages|Where-Object { @($_.To|ForEach-Object {$_.Address}) -contains $destination }|Select-Object -First 1;if($message){$body=(Invoke-RestMethod "$MailpitBase/api/v1/message/$($message.ID)").Text;if($body -match 'code is: ([0-9]{6})'){return $Matches[1]}};Start-Sleep -Milliseconds 200};throw 'Expected contact code was not delivered'}
$registration=Request POST '/auth/register' @{fullName='Controlled contact demo';email=$original;password=$password}
Request POST '/auth/verify' @{challengeId=$registration.verificationChallengeId;code=(Code $original)}|Out-Null
$session=Request POST '/auth/login' @{identifier=$original;password=$password}
Reject 401 GET '/users/me/contact-challenges' $null $null
Request PATCH '/users/me' @{email=$target} $session.accessToken|Out-Null
$challenge=@(Request GET '/users/me/contact-challenges' $null $session.accessToken)[0]
if(!$challenge.usable -or $challenge.channel -ne 'EMAIL' -or $challenge.recipient -ne $target -or $challenge.codeHash -or $challenge.tokenHash){throw 'Unsafe or incorrect contact metadata'}
if((Request GET '/users/me' $null $session.accessToken).email -ne $original){throw 'Login contact changed before verification'}
Reject 429 POST '/users/me/contact-challenges/EMAIL/resend' $null $session.accessToken
if($LeavePending){$fixture=@{identifier=$original;password=$password;target=$target;challengeId=$challenge.id;code=(Code $target)};$path=Join-Path $demoRoot 'tmp/demo/contact-browser.json';[IO.File]::WriteAllText($path,($fixture|ConvertTo-Json));Write-Output 'PASS: controlled pending contact fixture prepared in ignored tmp/demo/contact-browser.json';return}
Request POST '/auth/verify' @{challengeId=$challenge.id;code=(Code $target)}|Out-Null
Reject 422 POST '/auth/verify' @{challengeId=$challenge.id;code=(Code $target)} $null
Reject 401 POST '/auth/login' @{identifier=$original;password=$password} $null
$changed=Request POST '/auth/login' @{identifier=$target;password=$password}
if(!$changed.user.emailVerified -or @(Request GET '/users/me/contact-challenges' $null $changed.accessToken).Count -ne 0){throw 'Verified email did not become the current login'}
$phone='+84'+(Get-Random -Minimum 300000000 -Maximum 999999999)
Request PATCH '/users/me' @{phone=$phone} $changed.accessToken|Out-Null
$phoneChallenge=@(Request GET '/users/me/contact-challenges' $null $changed.accessToken)[0]
if($phoneChallenge.channel -ne 'PHONE' -or (Request GET '/users/me' $null $changed.accessToken).phone){throw 'Phone changed before verification'}
Request POST '/auth/verify' @{challengeId=$phoneChallenge.id;code=(Code $phone)}|Out-Null
$phoneLogin=Request POST '/auth/login' @{identifier=$phone;password=$password}
if(!$phoneLogin.user.phoneVerified -or $phoneLogin.user.email -ne $target -or $phoneLogin.user.roles.Count -ne 1 -or $phoneLogin.user.roles[0] -ne 'CUSTOMER'){throw 'Contact update changed identity roles or did not verify the phone'}
Write-Output 'PASS: Gateway own challenge metadata/authentication/60s resend guard/email and demo-SMS OTP/current contact unchanged until verify/old email login rejected/replay rejected/new phone login/roles unchanged.'
