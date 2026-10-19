function Read-DesktopGpuCounters {
    $readErrors = @()
    $batch = Get-Counter '\GPU Engine(*)\Utilization Percentage' -ErrorAction SilentlyContinue -ErrorVariable readErrors
    [pscustomobject]@{
        CounterSamples = @($batch.CounterSamples)
        Error = $(if ($readErrors.Count) { ($readErrors | ForEach-Object { $_.Exception.Message }) -join '; ' } else { $null })
    }
}

function Get-DesktopGpuSummary {
    param([object[]]$CounterSamples, [hashtable]$ProcessNames = @{})
    $engines = @{}
    $processes = @{}
    $matched = 0
    $invalid = 0
    foreach ($counter in $CounterSamples) {
        if ($counter.InstanceName -notmatch '^pid_(\d+)_(luid_.+?_phys_\d+_eng_\d+_engtype_.+?)(?:#\d+)?$') { continue }
        $counterProcess = [int]$Matches[1]
        $engine = $Matches[2]
        $percent = [double]$counter.CookedValue
        if (($null -ne $counter.Status -and $counter.Status -notin 0,1) -or
            [double]::IsNaN($percent) -or [double]::IsInfinity($percent) -or $percent -lt 0) {
            $invalid++
            continue
        }
        $matched++
        if (-not $engines.ContainsKey($engine)) { $engines[$engine] = @{} }
        if (-not $processes.ContainsKey($counterProcess)) { $processes[$counterProcess] = @{} }
        $engines[$engine][$counterProcess] += $percent
        $processes[$counterProcess][$engine] += $percent
    }
    $engineRows = @(foreach ($entry in $engines.GetEnumerator()) {
        $raw = [double](($entry.Value.Values | Measure-Object -Sum).Sum)
        [pscustomobject]@{
            engine = $entry.Key
            percent = [Math]::Min(100.0, $raw)
            rawPercent = $raw
            contributors = @(foreach ($contributor in $entry.Value.GetEnumerator()) {
                if ($contributor.Value -gt 0) {
                    [pscustomobject]@{ processId = $contributor.Key; percent = $contributor.Value }
                }
            })
        }
    })
    $processRows = @(foreach ($entry in $processes.GetEnumerator()) {
        $busiest = $entry.Value.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 1
        if ($busiest.Value -gt 0) {
            [pscustomobject]@{
                processId = $entry.Key
                processName = $(if ($ProcessNames.ContainsKey($entry.Key)) { $ProcessNames[$entry.Key] } else { 'unavailable' })
                busiestEngine = $busiest.Key
                percent = [Math]::Min(100.0, [double]$busiest.Value)
                rawPercent = $busiest.Value
            }
        }
    })
    $busiestEngine = $engineRows | Sort-Object percent -Descending | Select-Object -First 1
    [pscustomobject]@{
        matchedCounterSamples = $matched
        invalidCounterSamples = $invalid
        busiestEnginePercent = $(if ($matched -gt 0) { $busiestEngine.percent } else { $null })
        busiestEngine = $(if ($matched -gt 0) { $busiestEngine.engine } else { $null })
        engines = $engineRows
        processes = @($processRows | Sort-Object percent -Descending)
    }
}
