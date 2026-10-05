function Get-DemoFacility([string]$ApiBase) {
 $demoFacilities=@((Invoke-RestMethod "$ApiBase/facilities?q=SportHub%20Demo%20Center").data | Where-Object name -eq 'SportHub Demo Center')
 if($demoFacilities.Count -ne 1){throw 'Expected one controlled SportHub Demo Center; start the local/demo seed first.'}
 return $demoFacilities[0]
}
