$os = Get-CimInstance Win32_OperatingSystem
$totalGB = [math]::Round($os.TotalVisibleMemorySize/1MB, 2)
$freeGB = [math]::Round($os.FreePhysicalMemory/1MB, 2)
$usedGB = [math]::Round($totalGB - $freeGB, 2)
$usedPct = [math]::Round(($usedGB / $totalGB) * 100, 1)

Write-Host "=== Memory Overview ==="
Write-Host ("Total:     {0} GB" -f $totalGB)
Write-Host ("Used:      {0} GB ({1}%)" -f $usedGB, $usedPct)
Write-Host ("Available: {0} GB" -f $freeGB)
Write-Host ""

Write-Host "=== Top 30 Processes by Memory ==="
Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 30 |
    ForEach-Object {
        $memMB = [math]::Round($_.WorkingSet64 / 1MB, 1)
        "{0,-40} {1,10} MB  (PID {2})" -f $_.Name, $memMB, $_.Id
    }

Write-Host ""
Write-Host "=== Top 20 by Grouped Process Name ==="
Get-Process | Group-Object Name | ForEach-Object {
    $sumMB = [math]::Round((($_.Group | Measure-Object WorkingSet64 -Sum).Sum) / 1MB, 1)
    [PSCustomObject]@{ Name = $_.Name; Count = $_.Count; TotalMB = $sumMB }
} | Sort-Object TotalMB -Descending | Select-Object -First 20 |
    Format-Table Name, Count, TotalMB -AutoSize
