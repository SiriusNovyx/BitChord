$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/DesktopGpuCounters.ps1"
function Sample([int]$Process, [string]$Engine, [double]$Percent, [long]$Status = 0) {
    [pscustomobject]@{ InstanceName = "pid_${Process}_$Engine"; CookedValue = $Percent; Status = $Status }
}
$threeD = 'luid_0x0_0x1_phys_0_eng_0_engtype_3d'
$copy = 'luid_0x0_0x1_phys_0_eng_1_engtype_copy'
$otherAdapter = 'luid_0x0_0x2_phys_0_eng_0_engtype_3d'
$result = Get-DesktopGpuSummary @(
    (Sample 10 $threeD 40), (Sample 20 $threeD 20),
    (Sample 10 $copy 45), (Sample 30 $otherAdapter 55),
    (Sample 10 "$threeD#1" 5),
    (Sample 10 $copy ([double]::NaN)), (Sample 10 $copy 90 -1)
) @{10='BitChord';20='dwm'}
if ($result.busiestEnginePercent -ne 65 -or $result.engines.Count -ne 3) { throw 'GPU engines or adapters were incorrectly combined' }
$app = $result.processes | Where-Object processId -eq 10
if ($app.percent -ne 45 -or $app.processName -ne 'BitChord') { throw 'Process utilization summed independent engines' }
if ($result.invalidCounterSamples -ne 2) { throw 'Invalid samples were accepted' }
$overlap = Get-DesktopGpuSummary @((Sample 10 $threeD 80), (Sample 20 $threeD 50))
if ($overlap.busiestEnginePercent -ne 100 -or $overlap.engines[0].rawPercent -ne 130) { throw 'Raw counter overlap must remain visible' }
$empty = Get-DesktopGpuSummary @()
if ($null -ne $empty.busiestEnginePercent) { throw 'Missing counters were reported as idle' }
$fractional = Get-DesktopGpuSummary @((Sample 10 $threeD 0.25), (Sample 20 $threeD 0.125))
if ($fractional.busiestEnginePercent -ne 0.375 -or $fractional.processes[0].percent -ne 0.25) { throw 'Fractional utilization was rounded' }
Write-Output 'GPU summaries: shared engine, separate engines/adapters, duplicate contexts, invalid samples, overlap and missing data passed.'
function Get-Counter {
    [CmdletBinding()] param([string]$Counter)
    Write-Error 'One context expired during enumeration'
    [pscustomobject]@{ CounterSamples = @((Sample 10 $threeD 0.25), (Sample 20 $threeD 90 -1)) }
}
$batch = Read-DesktopGpuCounters
$partial = Get-DesktopGpuSummary $batch.CounterSamples
if ($partial.busiestEnginePercent -ne 0.25 -or $partial.invalidCounterSamples -ne 1 -or !$batch.Error) {
    throw 'A partial PDH error discarded valid counters or hid the error'
}
Write-Output 'GPU collection retains valid contexts and reports partial PDH errors.'
