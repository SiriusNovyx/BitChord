function Convert-DesktopBatterySamples {
    param([object[]]$Batteries)
    foreach ($battery in $Batteries) {
        if ($null -eq $battery) { continue }
        $rateMilliwatts = $null
        $dischargeMilliwatts = $null
        $numericRate = $battery.rate -is [int] -or $battery.rate -is [long] -or $battery.rate -is [double]
        if ($battery.systemBattery -eq $true -and $battery.shortTerm -eq $false -and
            $battery.relativeUnits -eq $false -and $battery.statusValid -eq $true -and
            $numericRate -and $battery.rate -gt [int]::MinValue -and $battery.rate -le [int]::MaxValue) {
            $rateMilliwatts = [double]$battery.rate
            if (($battery.powerState -band 2) -ne 0 -and $rateMilliwatts -le 0) {
                $dischargeMilliwatts = -$rateMilliwatts
            }
        }
        [pscustomobject]@{
            raw = $battery
            batteryRateMilliwatts = $rateMilliwatts
            batteryDischargeMilliwatts = $dischargeMilliwatts
        }
    }
}

function Convert-DesktopBatteryJson {
    param([string]$Json)
    $decoded = $Json | ConvertFrom-Json -ErrorAction Stop
    Convert-DesktopBatterySamples $decoded
}

function Read-DesktopBatteryCounters {
    param([string]$ProbePath)
    $process = $null
    try {
        $resolved = (Resolve-Path -LiteralPath $ProbePath -ErrorAction Stop).Path
        $start = New-Object Diagnostics.ProcessStartInfo
        $start.FileName = $resolved
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $process = [Diagnostics.Process]::Start($start)
        $output = $process.StandardOutput.ReadToEndAsync()
        $errors = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(3000)) {
            $process.Kill()
            throw 'battery_probe_timeout'
        }
        if ($process.ExitCode -ne 0) { throw ('battery_probe_exit_' + $process.ExitCode) }
        $converted = @(Convert-DesktopBatteryJson $output.GetAwaiter().GetResult())
        if ($converted.Count -eq 0) { throw 'battery_probe_no_data' }
        [pscustomobject]@{ available=$true; error=$null; batteries=$converted }
    } catch {
        [pscustomobject]@{ available=$false; error=$_.Exception.GetType().Name; batteries=@() }
    } finally {
        if ($null -ne $process) { $process.Dispose() }
    }
}
