param(
    [Parameter(Mandatory=$true)][int]$ProcessId,
    [Parameter(Mandatory=$true)][ValidateSet('idle','static','lyrics','artwork','minimized','battery','ac')][string]$Scenario,
    [ValidateRange(2,600)][int]$Samples = 60,
    [string]$OutputDirectory = '.work/performance',
    [switch]$IncludeChildProcesses,
    [switch]$IncludeSystemGpu,
    [string]$BatteryProbe
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/DesktopGpuCounters.ps1"
. "$PSScriptRoot/DesktopBatteryCounters.ps1"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$process = Get-Process -Id $ProcessId
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace BitChordMeasurements {
    public static class Windows {
        [StructLayout(LayoutKind.Sequential)]
        private struct MachineInformation { public ushort Machine; public ushort Reserved; public uint Attributes; }
        [DllImport("kernel32.dll", SetLastError=true)]
        private static extern bool GetProcessInformation(IntPtr process, int informationClass, out MachineInformation information, uint size);
        public static string ProcessMachine(IntPtr process) {
            MachineInformation information;
            return GetProcessInformation(process, 9, out information, 8)
                ? "0x" + information.Machine.ToString("x4") : "unavailable";
        }
        [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
        [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr window);
        [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr window);
    }
}
'@
$processMachine = [BitChordMeasurements.Windows]::ProcessMachine($process.Handle)
$processNames = @{}
if ($IncludeSystemGpu) {
    Get-Process | ForEach-Object { $processNames[[int]$_.Id] = $_.ProcessName }
}
$watched = @{ $ProcessId = $process }
if ($IncludeChildProcesses) {
    $inventory = Get-CimInstance Win32_Process
    do {
        $added = $false
        foreach ($candidate in $inventory) {
            $id = [int]$candidate.ProcessId
            if ($watched.ContainsKey([int]$candidate.ParentProcessId) -and -not $watched.ContainsKey($id)) {
                $child = Get-Process -Id $id -ErrorAction SilentlyContinue
                if ($child) { $watched[$id] = $child; $added = $true }
            }
        }
    } while ($added)
}
$power = $null
try {
    $power = Get-CimInstance -Namespace root/WMI -ClassName BatteryStatus -ErrorAction Stop |
        Select-Object PowerOnline,Charging,Discharging,DischargeRate,ChargeRate,RemainingCapacity,Voltage
} catch { }
$counterAvailable = $true
$counterError = $null
try { Import-Module Microsoft.PowerShell.Diagnostics -ErrorAction Stop }
catch { $counterAvailable = $false; $counterError = $_.Exception.Message }
$rows = @()
$previousCpu = @{}
foreach ($entry in $watched.GetEnumerator()) { $previousCpu[$entry.Key] = $entry.Value.TotalProcessorTime.TotalSeconds }
$previousTime = [Diagnostics.Stopwatch]::StartNew()
for ($sample = 0; $sample -lt $Samples; $sample++) {
    Start-Sleep -Seconds 1
    $process.Refresh()
    if ($process.HasExited) { break }
    $elapsed = $previousTime.Elapsed.TotalSeconds
    $cpuDelta = 0.0
    $privateBytes = 0L
    $workingBytes = 0L
    foreach ($entry in $watched.GetEnumerator()) {
        $entry.Value.Refresh()
        if ($entry.Value.HasExited) { continue }
        $cpu = $entry.Value.TotalProcessorTime.TotalSeconds
        $cpuDelta += [Math]::Max(0, $cpu - $previousCpu[$entry.Key])
        $previousCpu[$entry.Key] = $cpu
        $privateBytes += $entry.Value.PrivateMemorySize64
        $workingBytes += $entry.Value.WorkingSet64
    }
    $cpuCores = $cpuDelta / $elapsed
    $previousTime.Restart()
    $gpu = @()
    $systemGpu = $null
    $appGpu = $null
    $sampleGpuError = $null
    if ($counterAvailable) {
        try {
            $batch = Read-DesktopGpuCounters
            $sampleGpuError = $batch.Error
            $allGpu = $batch.CounterSamples
            $appCounters = @($allGpu | Where-Object { $_.InstanceName -match '^pid_(\d+)_' -and $watched.ContainsKey([int]$Matches[1]) })
            $gpu = $appCounters |
                ForEach-Object { @{ engine = $_.InstanceName; percent = $_.CookedValue; status = $_.Status } }
            if ($IncludeSystemGpu) {
                $systemGpu = Get-DesktopGpuSummary $allGpu $processNames
                $appGpu = Get-DesktopGpuSummary $appCounters $processNames
            }
        } catch { $sampleGpuError = $_.Exception.Message }
    }
    $rows += [pscustomobject]@{
        utc = [DateTime]::UtcNow.ToString('o')
        battery = $(
            try {
                Get-CimInstance -Namespace root/WMI -ClassName BatteryStatus -ErrorAction Stop |
                    Select-Object PowerOnline,Charging,Discharging,DischargeRate,RemainingCapacity,Voltage
            } catch { $null }
        )
        scenario = $Scenario
        batteryNative = $(if ($BatteryProbe) { Read-DesktopBatteryCounters $BatteryProbe } else { $null })
        cpuCores = $cpuCores
        privateMiB = $privateBytes / 1MB
        workingSetMiB = $workingBytes / 1MB
        gpuEngines = @($gpu)
        gpuError = $sampleGpuError
        systemGpu = $systemGpu
        appGpu = $appGpu
        minimized = [BitChordMeasurements.Windows]::IsIconic($process.MainWindowHandle)
        windowVisible = [BitChordMeasurements.Windows]::IsWindowVisible($process.MainWindowHandle)
        appForeground = $(
            [uint32]$foregroundProcess = 0
            [void][BitChordMeasurements.Windows]::GetWindowThreadProcessId(
                [BitChordMeasurements.Windows]::GetForegroundWindow(), [ref]$foregroundProcess)
            $watched.ContainsKey([int]$foregroundProcess)
        )
    }
}
$report = @{
    processId = $ProcessId
    processName = $process.ProcessName
    processMachine = $processMachine
    processIds = @($watched.Keys)
    includesChildProcessSnapshot = [bool]$IncludeChildProcesses
    includesSystemGpu = [bool]$IncludeSystemGpu
    includesNativeBattery = [bool]$BatteryProbe
    scenario = $Scenario
    gpuAvailable = $counterAvailable
    gpuError = $counterError
    energyMeasured = $false
    batteryTelemetry = $power
    samples = $rows
}
$destination = Join-Path $OutputDirectory "$Scenario-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')).json"
$report | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $destination -Encoding UTF8
Write-Output $destination
