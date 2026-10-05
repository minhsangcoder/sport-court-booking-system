[CmdletBinding()]
param([string]$ApiBase='http://localhost:18080/api/v1')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'get-demo-facility.ps1')
function Search($filters){$query=($filters.GetEnumerator()|ForEach-Object {[uri]::EscapeDataString($_.Key)+'='+[uri]::EscapeDataString([string]$_.Value)}) -join '&';(Invoke-RestMethod "$ApiBase/bookings/search?$query").data}
function Reject($filters){try{Search $filters|Out-Null;throw 'Expected invalid search to be rejected'}catch{if([int]$_.Exception.Response.StatusCode -ne 400){throw}}}
$facility=Get-DemoFacility $ApiBase
$date=(Get-Date).AddDays(4).ToString('yyyy-MM-dd')
$all=Search @{q='SportHub Demo Center';date=$date;sort='PRICE_ASC'}
if($all.items.Count -lt 1 -or @($all.items|Where-Object {$_.availableSlots -lt 1 -or $_.fromPrice -le 0 -or $_.facility.id -ne $facility.id}).Count){throw 'Public search did not return real available demo courts'}
$record=$all.items[0]
$filtered=Search @{q='SportHub Demo Center';province=$facility.province;district=$facility.district;sportCategoryId=$record.sportCategoryId;date=$date;minPrice=$record.fromPrice;maxPrice=$record.fromPrice;sort='PRICE_ASC'}
if($filtered.items.Count -lt 1 -or @($filtered.items|Where-Object {$_.sportCategoryId -ne $record.sportCategoryId -or $_.fromPrice -ne $record.fromPrice}).Count){throw 'AND location/category/per-slot price filters failed'}
if((Search @{q='SportHub Demo Center';date=$date;maxPrice=1}).items.Count -ne 0){throw 'Per-slot price exclusion failed'}
if((Search @{q='SportHub Demo Center';date=$date;district='No-such-controlled-district'}).items.Count -ne 0){throw 'Location exclusion failed'}
if($null -ne $facility.latitude -and $null -ne $facility.longitude){$near=Search @{q='SportHub Demo Center';date=$date;latitude=$facility.latitude;longitude=$facility.longitude;radiusKm=1;sort='DISTANCE'};if($near.items.Count -lt 1 -or $near.items[0].distanceKm -gt 0.1){throw 'Distance filter/sort failed'};if((Search @{q='SportHub Demo Center';date=$date;latitude=0;longitude=0;radiusKm=1;sort='DISTANCE'}).items.Count -ne 0){throw 'Radius exclusion failed'}}
Reject @{date='invalid'};Reject @{date=$date;opensAfter='20:00';closesBefore='06:00'};Reject @{date=$date;minPrice=200000;maxPrice=100000};Reject @{date=$date;latitude=21;radiusKm=1;sort='DISTANCE'}
$json=$all|ConvertTo-Json -Depth 20
if($json -match '"(ownerId|customerId|phone|email|checkinToken|payerId)"'){throw 'Private identity fields leaked into discovery summary'}
Write-Output "PASS: anonymous Gateway discovery/real slot prices/AND province+district+sport+price filters/sorting/radius/empty states/invalid criteria/public-only summaries; $($all.items.Count) courts for $date."
