$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root '.tools\launch.pids'

if (-not (Test-Path $pidFile)) {
    Write-Host '没有记录到由快捷方式启动的服务。'
    Write-Host '如果是用 scripts/dev.sh 启动的，请在它的窗口按 Ctrl+C。'
    exit 0
}

foreach ($processId in Get-Content $pidFile) {
    if ($processId -match '^\d+$') {
        taskkill /PID $processId /T /F 2>$null | Out-Null
    }
}
Remove-Item $pidFile -Force
Write-Host '服务已停止。'
