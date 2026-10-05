[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1',[string]$MailpitBase='http://localhost:18025')
$ErrorActionPreference='Stop'
$demoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$values=@{};foreach($line in [IO.File]::ReadAllLines((Join-Path $demoRoot '.env'))){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
function Request($method,$path,$body=$null,$token=$null){
 $args=@{Method=$method;Uri="$ApiBase$path";ContentType='application/json'}
 if($null -ne $body){$args.Body=$body|ConvertTo-Json -Depth 25};if($token){$args.Headers=@{Authorization="Bearer $token"}}
 (Invoke-RestMethod @args).data
}
function Search($filters){
 $parts=foreach($key in $filters.Keys){[uri]::EscapeDataString($key)+'='+[uri]::EscapeDataString([string]$filters[$key])}
 Request GET ('/admin/owner-applications/page?'+($parts -join '&')) $null $admin.accessToken
}
function ExpectIds($result,$ids){
 $actual=@($result.items|ForEach-Object id|Sort-Object);$expected=@($ids|Sort-Object)
 if(($actual -join ',') -ne ($expected -join ',')){throw 'Unexpected filtered application ids'}
}
function Reject($code,$path,$token=$null){
 $args=@{Uri="$ApiBase$path";SkipHttpErrorCheck=$true;Headers=@{'X-User-Roles'='ADMIN'}}
 if($token){$args.Headers.Authorization="Bearer $token"};$result=Invoke-WebRequest @args
 if($result.StatusCode -ne $code){throw "$path expected $code; received $($result.StatusCode)"}
 if($code -eq 400 -and ($result.Content|ConvertFrom-Json).code -ne 'IDENTITY-OWNER-APPLICATION'){throw 'Validation error envelope mismatch'}
}
$admin=Request POST '/auth/login' @{identifier='admin@sporthub.local';password=$values.DEMO_PASSWORD}
$customer=Request POST '/auth/login' @{identifier='customer@sporthub.local';password=$values.DEMO_PASSWORD}
Reject 401 '/admin/owner-applications/page'
Reject 403 '/admin/owner-applications/page' $customer.accessToken
foreach($query in @('state=INVALID','submittedFrom=2026-02-30','submittedFrom=2026-10-07&submittedTo=2026-10-06','sort=private_payload','size=101','applicationId=1-1-1-1-1')){Reject 400 "/admin/owner-applications/page?$query" $admin.accessToken}

# Reuse the existing real draft/courts/hours/pricing/documents fixture; all facilities remain private.
$suite='Admin filters '+[Guid]::NewGuid().ToString('N').Substring(0,10);$fixtures=@()
foreach($number in 1..3){
 $prepared=@(& (Join-Path $PSScriptRoot 'smoke-owner-application.ps1') -ApiBase $ApiBase -MailpitBase $MailpitBase -LeaveDraft)
 $fixtureLine=@($prepared|Where-Object {$_ -match '^FIXTURE applicant=(\S+) application=(\S+) facility=(\S+)$'})
 if($fixtureLine.Count -ne 1 -or $fixtureLine[0] -notmatch '^FIXTURE applicant=(\S+) application=(\S+) facility=(\S+)$'){throw 'Draft fixture metadata missing'}
 $email=$Matches[1];$id=$Matches[2];$facilityId=$Matches[3]
 $applicant=Request POST '/auth/login' @{identifier=$email;password=$values.DEMO_PASSWORD}
 $detail=Request GET "/owner-applications/$id" $null $applicant.accessToken
 $detail.legal.businessName="$suite $number"
 Request PUT "/owner-applications/$id/legal" $detail.legal $applicant.accessToken|Out-Null
 $submitted=Request POST "/owner-applications/$id/submit" $null $applicant.accessToken
 if($submitted.state -ne 'PENDING_APPROVAL'){throw 'Filter fixture submission failed'}
 $fixtures+=@{id=$id;email=$email;facilityId=$facilityId;businessName=$submitted.businessName;submittedAt=$submitted.submittedAt}
}
$ids=@($fixtures|ForEach-Object id)
$dates=@($fixtures|ForEach-Object {
 $instant=if($_.submittedAt -is [DateTime]){[DateTimeOffset]::new($_.submittedAt)}else{[DateTimeOffset]::Parse($_.submittedAt)}
 $instant.ToOffset([TimeSpan]::FromHours(7)).ToString('yyyy-MM-dd')
}|Sort-Object)
$from=$dates[0];$to=$dates[-1]
$noFilters=Search @{};if($noFilters.totalElements -lt 3){throw 'Default queue missing records'}
$legacy=@(Request GET ('/admin/owner-applications?q='+[uri]::EscapeDataString($suite)) $null $admin.accessToken)
if($legacy.Count -ne 3){throw 'Legacy array contract regression'}
ExpectIds (Search @{q=$suite}) $ids
ExpectIds (Search @{q=$suite;state='PENDING_APPROVAL'}) $ids
ExpectIds (Search @{q=$suite;facility='Controlled first facility'}) $ids
ExpectIds (Search @{q=$suite;applicant=$fixtures[0].email}) @($fixtures[0].id)
ExpectIds (Search @{applicationId=$fixtures[0].id}) @($fixtures[0].id)
ExpectIds (Search @{q=$suite;submittedFrom=$from}) $ids
ExpectIds (Search @{q=$suite;submittedTo=$to}) $ids
ExpectIds (Search @{q=$suite;submittedFrom=$from;submittedTo=$to;state='PENDING_APPROVAL';facility='Controlled first facility'}) $ids
ExpectIds (Search @{q=$suite;state='REJECTED'}) @()
ExpectIds (Search @{q=$suite;reviewedFrom=$from}) @()
$all=Search @{q=$suite;state='PENDING_APPROVAL';submittedFrom=$from;submittedTo=$to}
$first=Search @{q=$suite;state='PENDING_APPROVAL';submittedFrom=$from;submittedTo=$to;size='2';page='0'}
$second=Search @{q=$suite;state='PENDING_APPROVAL';submittedFrom=$from;submittedTo=$to;size='2';page='1'}
if($first.totalElements -ne 3 -or $first.totalPages -ne 2 -or $first.items.Count -ne 2 -or $second.items.Count -ne 1){throw 'Pagination metadata mismatch'}
if((@($first.items+$second.items|ForEach-Object id) -join ',') -ne (@($all.items|ForEach-Object id) -join ',')){throw 'Filtered page ordering mismatch'}
$reverse=Search @{q=$suite;sort='SUBMITTED_DESC'}
if($reverse.items[0].id -ne $all.items[-1].id){throw 'Descending sort mismatch'}
$projection=$all|ConvertTo-Json -Depth 10
if($projection -match 'identityNumber|bankAccountNumber|representativeName|private_payload|OWNER-SMOKE-IDENTITY'){throw 'Filter response leaks legal data'}
foreach($fixture in $fixtures){
 $detail=Request GET "/admin/owner-applications/$($fixture.id)" $null $admin.accessToken
 if($detail.application.id -ne $fixture.id -or $detail.history.Count -ne 1){throw 'Filtered application detail regression'}
 Reject 404 "/facilities/$($fixture.facilityId)"
}
$evidence=Join-Path $demoRoot 'tmp/demo/admin-owner-filter-fixtures.json'
[IO.Directory]::CreateDirectory((Split-Path $evidence))|Out-Null
@{keyword=$suite;submittedFrom=$from;submittedTo=$to;fixtures=$fixtures}|ConvertTo-Json -Depth 10|Set-Content -LiteralPath $evidence -Encoding utf8
Write-Output "PASS Owner application filters: legacy/page contracts, AND, account/facility/id, Vietnam dates, paging/sort, validation, authorization, safe projections and real detail. Private fixtures: $suite"
