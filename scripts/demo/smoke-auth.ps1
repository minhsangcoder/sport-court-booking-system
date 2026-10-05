[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1',[string]$MailpitBase='http://localhost:18025',
 [string]$TestEmail,[string]$TestPassword)
$ErrorActionPreference='Stop'
$smokeEmail=if($TestEmail){$TestEmail}else{'smoke-'+[Guid]::NewGuid().ToString('N')+'@sporthub.local'}
$smokePassword=if($TestPassword){$TestPassword}else{[Guid]::NewGuid().ToString('N')+'Aa!'}
$smokeSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
function Send-AuthRequest([string]$Path,$Body) {
  Invoke-RestMethod -Uri "$ApiBase$Path" -Method Post -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 6) -WebSession $smokeSession
}
function Find-SmokeCode([string]$Subject) {
  $smokeMessage=$null
  for($smokeMailAttempt=0;$smokeMailAttempt -lt 20;$smokeMailAttempt++) {
    $smokeMessages=(Invoke-RestMethod "$MailpitBase/api/v1/messages").messages
    $smokeMessage=$smokeMessages | Where-Object { $_.Subject -like "*$Subject*" -and (@($_.To | ForEach-Object { $_.Address }) -contains $smokeEmail) } | Select-Object -First 1
    if($smokeMessage){break}
    Start-Sleep -Milliseconds 200
  }
  if(!$smokeMessage){throw 'Expected verification email was not delivered'}
  $smokeText=(Invoke-RestMethod "$MailpitBase/api/v1/message/$($smokeMessage.ID)").Text
  if($smokeText -notmatch '(?m)code is: ([0-9]{6})'){throw 'Verification code missing from Mailpit'}
  return $Matches[1]
}
function Assert-Status([string]$Path,[string]$Token,[int]$Expected) {
  $smokeResponse=Invoke-WebRequest "$ApiBase$Path" -Headers @{Authorization="Bearer $Token";'X-User-Role'='ADMIN';'X-User-Forged'='spoof'} -SkipHttpErrorCheck
  if($smokeResponse.StatusCode -ne $Expected){throw "Expected $Expected for $Path; received $($smokeResponse.StatusCode)"}
}
$smokeRegistration=(Send-AuthRequest '/auth/register' @{fullName='Auth Smoke';email=$smokeEmail;password=$smokePassword}).data
$smokeCode=Find-SmokeCode 'Verify'
Send-AuthRequest '/auth/verify' @{challengeId=$smokeRegistration.verificationChallengeId;code=$smokeCode} | Out-Null
$smokeLogin=(Send-AuthRequest '/auth/login' @{identifier=$smokeEmail;password=$smokePassword}).data
Assert-Status '/users/me' $smokeLogin.accessToken 200
$smokeRefresh=(Send-AuthRequest '/auth/refresh' @{}).data
Assert-Status '/users/me' $smokeLogin.accessToken 401
Assert-Status '/users/me' $smokeRefresh.accessToken 200
Send-AuthRequest '/auth/logout' @{} | Out-Null
Assert-Status '/users/me' $smokeRefresh.accessToken 401
Send-AuthRequest '/auth/forgot-password' @{identifier=$smokeEmail} | Out-Null
$smokeResetCode=Find-SmokeCode 'Reset'
$smokeResetMessages=(Invoke-RestMethod "$MailpitBase/api/v1/messages").messages
$smokeResetMessage=$smokeResetMessages | Where-Object { $_.Subject -like '*Reset*' -and (@($_.To | ForEach-Object { $_.Address }) -contains $smokeEmail) } | Select-Object -First 1
$smokeResetText=(Invoke-RestMethod "$MailpitBase/api/v1/message/$($smokeResetMessage.ID)").Text
if($smokeResetText -notmatch 'challengeId=([0-9a-f-]{36})'){throw 'Reset challenge missing'}
Send-AuthRequest '/auth/reset-password' @{challengeId=$Matches[1];code=$smokeResetCode;newPassword=$smokePassword+'B'} | Out-Null
$smokeRelogin=(Send-AuthRequest '/auth/login' @{identifier=$smokeEmail;password=$smokePassword+'B'}).data
Assert-Status '/users/me' $smokeRelogin.accessToken 200
Assert-Status '/users/me' 'invalid.jwt.token' 401
Send-AuthRequest '/auth/logout' @{} | Out-Null
Write-Output 'PASS: Gateway register/email verify/login/profile/refresh rotation/logout/reset/relogin/invalid token; real PostgreSQL + Mailpit.'
