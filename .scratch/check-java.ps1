Get-Process java -ErrorAction SilentlyContinue | ForEach-Object {
    $proc = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $_.Id)
    [PSCustomObject]@{
        PID = $_.Id
        MemMB = [math]::Round($_.WorkingSet64/1MB, 1)
        Started = $_.StartTime
        CommandLine = if ($proc.CommandLine) { $proc.CommandLine.Substring(0, [Math]::Min(200, $proc.CommandLine.Length)) } else { "N/A" }
    }
} | Format-List
