[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1')
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$demoPassword=([IO.File]::ReadAllLines((Join-Path $demoRoot '.env')) | Where-Object { $_ -match '^DEMO_PASSWORD=' }) -replace '^DEMO_PASSWORD=',''
function Request($method,$path,$body=$null,$token=$null){$headers=@{};if($token){$headers.Authorization="Bearer $token"};$args=@{Method=$method;Uri="$ApiBase$path";Headers=$headers};if($null -ne $body){$args.Body=($body|ConvertTo-Json -Depth 10 -Compress);$args.ContentType='application/json'};return (Invoke-RestMethod @args).data}
$session=Request POST '/auth/login' @{identifier='owner@sporthub.local';password=$demoPassword}
try {
 $facility=@(Request GET '/owner/facilities' $null $session.accessToken | Where-Object status -eq 'ACTIVE')[0]
 $court=@(Request GET "/owner/facilities/$($facility.id)/courts" $null $session.accessToken)[0]
 $date=(Get-Date).AddDays(2).ToString('yyyy-MM-dd')
 $preview=Request GET "/schedules/facilities/$($facility.id)/preview?courtId=$($court.id)&date=$date" $null $session.accessToken
 $slot=@($preview.slots | Where-Object state -eq 'ELIGIBLE')[0]
 if(!$slot){throw 'No eligible demo slot'}
 $quote=Request POST '/schedules/public/quote' @{courtId=$court.id;startsAt=$slot.startsAt;endsAt=$slot.endsAt}
 if($quote.amount -ne 150000 -or !$quote.segments[0].ruleId){throw 'Quote or rule trace mismatch'}
 $foreign=[Guid]::NewGuid().ToString()
 try {Request GET "/schedules/facilities/$foreign/hours" $null $session.accessToken;throw 'Foreign facility access was accepted'} catch {if([int]$_.Exception.Response.StatusCode -notin 403,404){throw}}
 Write-Output 'PASS: Gateway owner preview, public quote, stored rule trace and foreign-scope rejection.'
} finally {Request POST '/auth/logout' $null $session.accessToken | Out-Null}
