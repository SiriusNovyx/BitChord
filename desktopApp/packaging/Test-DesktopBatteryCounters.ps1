$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/DesktopBatteryCounters.ps1"
function Battery([object]$Relative = $false, [long]$Rate = -12000, [int]$PowerState = 2) {
    [pscustomobject]@{ systemBattery=$true; shortTerm=$false; relativeUnits=$Relative;
        statusValid=$true; rate=$Rate; powerState=$PowerState }
}
$absolute = @(Convert-DesktopBatterySamples @((Battery)))
if ($absolute.Count -ne 1 -or $absolute[0].batteryRateMilliwatts -ne -12000 -or
    $absolute[0].batteryDischargeMilliwatts -ne 12000) { throw 'Absolute discharge rate was lost' }
$relative = @(Convert-DesktopBatterySamples @((Battery $true)))
if ($null -ne $relative[0].batteryRateMilliwatts) { throw 'Relative units were converted to milliwatts' }
$unknown = @(Convert-DesktopBatterySamples @((Battery $false -2147483648)))
if ($null -ne $unknown[0].batteryRateMilliwatts) { throw 'Unknown-rate sentinel was reported as power' }
$charging = @(Convert-DesktopBatterySamples @((Battery $false 5000 5)))
if ($charging[0].batteryRateMilliwatts -ne 5000 -or
    $null -ne $charging[0].batteryDischargeMilliwatts) { throw 'Charging was reported as consumption' }
$noCapability = Battery $null
$noStatus = Battery
$noStatus.statusValid = $false
$ups = Battery
$ups.shortTerm = $true
$notSystem = Battery
$notSystem.systemBattery = $false
$invalid = @(Convert-DesktopBatterySamples @($noCapability, $noStatus, $ups, $notSystem))
if (@($invalid | Where-Object { $null -ne $_.batteryRateMilliwatts }).Count -ne 0) {
    throw 'Unavailable capability/status or non-system/UPS battery was accepted'
}
if (@(Convert-DesktopBatterySamples @()).Count -ne 0) { throw 'Missing data was reported as idle' }
if (@(Convert-DesktopBatterySamples @($null)).Count -ne 0) { throw 'Null data was reported as a battery' }
$missing = Read-DesktopBatteryCounters (Join-Path $PSScriptRoot 'missing-battery-probe-for-test.exe')
if ($missing.available -or $missing.batteries.Count -ne 0 -or $null -eq $missing.error) {
    throw 'Unavailable probe did not preserve the missing-data result'
}
$json = '[{"systemBattery":true,"shortTerm":false,"relativeUnits":false,"statusValid":true,"rate":-1000,"powerState":2},{"systemBattery":true,"shortTerm":false,"relativeUnits":false,"statusValid":true,"rate":-2000,"powerState":2}]'
$multiple = @(Convert-DesktopBatteryJson $json)
if ($multiple.Count -ne 2 -or $multiple[0].raw.rate -ne -1000 -or $multiple[1].batteryRateMilliwatts -ne -2000) {
    throw 'JSON batteries were collapsed into a nested array'
}
Write-Output 'Battery summaries: absolute/relative units, unknown rate, charging, unavailable status and non-system batteries passed.'
