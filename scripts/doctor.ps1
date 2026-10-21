# 排课系统自检（doctor，PowerShell 版）：与 scripts/doctor.sh 输出完全相同的 JSON 结构。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/doctor.ps1 [--json|--quiet] [--help]
#   或直接运行仓库根目录的 doctor.bat
#
# 退出码：0=健康，1=警告，2=严重。

$ErrorActionPreference = 'Continue'

try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

$Root = Split-Path -Parent $PSScriptRoot

function Show-Usage {
    @'
排课系统自检（doctor）

用法：doctor.bat [--json|--quiet] [--help]

  --json    输出 JSON 诊断报告，stdout 只有 JSON
  --quiet   同 --json（本工具不打印进度信息）
  --help    显示本帮助

退出码：0=健康，1=有警告，2=存在严重问题
'@
}

# 运行外部命令并强制超时，避免 Docker 守护进程失联时脚本挂住。
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [int]$TimeoutSeconds = 15
    )

    $result = @{ ExitCode = -1; Output = ''; TimedOut = $false }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $quoted = @()
    foreach ($argument in $ArgumentList) {
        if ($argument -match '[\s"]') {
            $quoted += '"' + ($argument -replace '"', '\"') + '"'
        } else {
            $quoted += $argument
        }
    }
    $psi.Arguments = ($quoted -join ' ')

    try {
        $process = [System.Diagnostics.Process]::Start($psi)
    } catch {
        return $result
    }

    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $null = $process.StandardError.ReadToEndAsync()

    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        try { $process.Kill() } catch { }
        $result.TimedOut = $true
        return $result
    }

    $process.WaitForExit()
    $result.ExitCode = $process.ExitCode
    $result.Output = $stdoutTask.Result
    return $result
}

function Test-PortOpen {
    param([int]$Port, [int]$TimeoutMs = 1500)

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs)) {
            return $false
        }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        try { $client.Close() } catch { }
    }
}

function Get-EnvValue {
    param([string]$Key)

    $file = Join-Path $Root '.env'
    if (-not (Test-Path -LiteralPath $file)) { return $null }

    $value = $null
    $pattern = '^(?:export\s+)?' + [regex]::Escape($Key) + '\s*=\s*(.*)$'
    foreach ($line in (Get-Content -LiteralPath $file -ErrorAction SilentlyContinue)) {
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith('#')) { continue }
        if ($trimmed -match $pattern) {
            $value = $Matches[1].Trim()
            if ($value.Length -ge 2) {
                $quoted = ($value.StartsWith('"') -and $value.EndsWith('"')) -or
                          ($value.StartsWith("'") -and $value.EndsWith("'"))
                if ($quoted) { $value = $value.Substring(1, $value.Length - 2) }
            }
        }
    }
    return $value
}

function Format-PortState {
    param([int]$Port)
    if (Test-PortOpen -Port $Port) { return 'occupied' }
    return 'free'
}

# --- 参数 -----------------------------------------------------------------

$Mode = 'human'
foreach ($argument in $args) {
    $token = [string]$argument
    if ($token -eq '--json' -or $token -eq '--quiet') {
        $Mode = 'json'
    } elseif ($token -eq '--help' -or $token -eq '-h' -or $token -eq '/?') {
        Show-Usage
        exit 0
    } else {
        [Console]::Error.WriteLine("未知参数：$token")
        Show-Usage
        exit 2
    }
}

# --- Docker ---------------------------------------------------------------

$dockerInstalled = $false
$dockerRunning = $false
$dockerVersion = $null
$dockerExe = $null

$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($dockerCommand) {
    $dockerInstalled = $true
    $dockerExe = $dockerCommand.Source
    if (-not $dockerExe) { $dockerExe = 'docker' }

    $versionResult = Invoke-Native -FilePath $dockerExe -ArgumentList @('--version') -TimeoutSeconds 10
    $rawVersion = ($versionResult.Output -split "`r?`n")[0]
    if ($rawVersion) {
        $match = [regex]::Match($rawVersion, 'version\s+([^,\s]+)', 'IgnoreCase')
        if ($match.Success) {
            $dockerVersion = $match.Groups[1].Value
        } else {
            $dockerVersion = $rawVersion.Trim()
        }
    }

    $infoResult = Invoke-Native -FilePath $dockerExe -ArgumentList @('info') -TimeoutSeconds 20
    $dockerRunning = ($infoResult.ExitCode -eq 0)
}

# --- 端口 -----------------------------------------------------------------

$port8080 = Format-PortState -Port 8080
$port5173 = Format-PortState -Port 5173
$port5432 = Format-PortState -Port 5432

# --- 内存 -----------------------------------------------------------------

$totalKb = 0
$availKb = 0

if ($env:OS -eq 'Windows_NT') {
    try {
        $osInfo = Get-CimInstance -ClassName Win32_OperatingSystem -ErrorAction Stop
        $totalKb = [double]$osInfo.TotalVisibleMemorySize
        $availKb = [double]$osInfo.FreePhysicalMemory
    } catch {
        $totalKb = 0
        $availKb = 0
    }
} elseif (Test-Path -LiteralPath '/proc/meminfo') {
    foreach ($line in (Get-Content -LiteralPath '/proc/meminfo' -ErrorAction SilentlyContinue)) {
        if ($line -match '^MemTotal:\s+(\d+)') { $totalKb = [double]$Matches[1] }
        if ($line -match '^MemAvailable:\s+(\d+)') { $availKb = [double]$Matches[1] }
    }
} else {
    try {
        $bytes = [double](& sysctl -n hw.memsize 2>$null)
        $totalKb = $bytes / 1024
        $availKb = 0
    } catch { }
}

$totalGb = [math]::Round($totalKb / 1048576, 1)
$availGb = [math]::Round($availKb / 1048576, 1)
$memSufficient = ($availKb -ge 2097152)

# --- 数据库 ---------------------------------------------------------------

$dbReachable = $false
$dbMigrated = $false
$dbVersion = $null

$composePath = Join-Path $Root 'docker-compose.yml'
if ($dockerRunning -and $dockerExe -and (Test-Path -LiteralPath $composePath)) {
    $dbUser = Get-EnvValue -Key 'POSTGRES_USER'
    if (-not $dbUser) { $dbUser = 'paiban' }
    $dbName = Get-EnvValue -Key 'POSTGRES_DB'
    if (-not $dbName) { $dbName = 'paiban' }

    $composeArgs = @('compose', '-f', $composePath, 'exec', '-T', 'postgres')

    $readyArgs = $composeArgs + @('pg_isready', '-U', $dbUser, '-d', $dbName)
    $readyResult = Invoke-Native -FilePath $dockerExe -ArgumentList $readyArgs -TimeoutSeconds 15
    if ($readyResult.ExitCode -eq 0) {
        $dbReachable = $true

        $queryArgs = $composeArgs + @(
            'psql', '-U', $dbUser, '-d', $dbName, '-tAc',
            'select version from flyway_schema_history order by installed_rank desc limit 1'
        )
        $queryResult = Invoke-Native -FilePath $dockerExe -ArgumentList $queryArgs -TimeoutSeconds 15
        if ($queryResult.ExitCode -eq 0) {
            $value = ($queryResult.Output -replace '\s', '')
            if ($value) {
                $dbVersion = $value
                $dbMigrated = $true
            }
        }
    }
}

# --- 磁盘 -----------------------------------------------------------------

$freeGb = $null
try {
    $driveRoot = [System.IO.Path]::GetPathRoot((Get-Item -LiteralPath $Root).FullName)
    $driveInfo = New-Object -TypeName System.IO.DriveInfo -ArgumentList $driveRoot
    $freeGb = [math]::Round($driveInfo.AvailableFreeSpace / 1GB, 1)
} catch {
    $freeGb = $null
}

$eDriveFreeGb = $null
if ($driveRoot -match '^[Ee]:') { $eDriveFreeGb = $freeGb }

$diskSufficient = ($null -ne $freeGb -and $freeGb -ge 1)

# --- 退出码 ---------------------------------------------------------------

$exitCode = 0
if (-not $dockerInstalled -or -not $dockerRunning -or -not $dbReachable -or -not $diskSufficient) {
    $exitCode = 2
} elseif ($port8080 -eq 'occupied' -or $port5173 -eq 'occupied' -or $port5432 -eq 'occupied' -or
          -not $memSufficient -or -not $dbMigrated) {
    $exitCode = 1
}

# --- 输出 -----------------------------------------------------------------

if ($Mode -eq 'json') {
    $report = [ordered]@{
        docker   = [ordered]@{
            installed = $dockerInstalled
            version   = $dockerVersion
            running   = $dockerRunning
        }
        ports    = [ordered]@{
            '8080' = $port8080
            '5173' = $port5173
            '5432' = $port5432
        }
        memory   = [ordered]@{
            total_gb     = $totalGb
            available_gb = $availGb
            sufficient   = $memSufficient
        }
        database = [ordered]@{
            reachable      = $dbReachable
            migrated       = $dbMigrated
            flyway_version = $dbVersion
        }
        disk     = [ordered]@{
            e_drive_free_gb = $eDriveFreeGb
            project_free_gb = $freeGb
            sufficient      = $diskSufficient
        }
        exit_code = $exitCode
    }

    $report | ConvertTo-Json -Depth 5
    exit $exitCode
}

$useColor = $false
try { $useColor = -not [Console]::IsOutputRedirected } catch { }
if ($env:NO_COLOR) { $useColor = $false }

function Write-Ok([string]$Message) {
    if ($useColor) { Write-Host "✅ $Message" -ForegroundColor Green } else { Write-Host "✅ $Message" }
}
function Write-Warn([string]$Message) {
    if ($useColor) { Write-Host "⚠️ $Message" -ForegroundColor Yellow } else { Write-Host "⚠️ $Message" }
}
function Write-Bad([string]$Message) {
    if ($useColor) { Write-Host "❌ $Message" -ForegroundColor Red } else { Write-Host "❌ $Message" }
}

Write-Host "=== 排课系统自检 (doctor) ==="

if (-not $dockerInstalled) {
    Write-Bad "Docker：未安装。请安装 Docker Desktop：https://www.docker.com/products/docker-desktop"
} elseif (-not $dockerRunning) {
    Write-Warn "Docker：已安装 $dockerVersion，但守护进程未运行（请启动 Docker Desktop）"
} else {
    Write-Ok "Docker：已安装 $dockerVersion，守护进程运行中"
}

if ($port8080 -eq 'free' -and $port5173 -eq 'free' -and $port5432 -eq 'free') {
    Write-Ok "端口：8080 / 5173 / 5432 均空闲"
} else {
    $detail = ''
    if ($port8080 -eq 'occupied') { $detail += '8080（后端） ' }
    if ($port5173 -eq 'occupied') { $detail += '5173（前端） ' }
    if ($port5432 -eq 'occupied') { $detail += '5432（数据库） ' }
    Write-Warn "端口：$detail已被占用；如非本系统进程，请先释放"
}

if ($memSufficient) {
    Write-Ok "内存：总计 $totalGb GB，可用 $availGb GB（要求 ≥ 2 GB）"
} else {
    Write-Warn "内存：总计 $totalGb GB，可用 $availGb GB，低于 2 GB，求解可能失败"
}

if (-not $dockerRunning) {
    Write-Bad "数据库：不可连接（Docker 未运行；启动后执行 docker compose up -d）"
} elseif (-not $dbReachable) {
    Write-Bad "数据库：不可连接（请执行 docker compose up -d 并等待就绪）"
} elseif (-not $dbMigrated) {
    Write-Warn "数据库：可连接，但尚未执行 Flyway 迁移（后端首次启动会自动建表）"
} else {
    Write-Ok "数据库：可连接，Flyway 版本 $dbVersion"
}

if ($diskSufficient) {
    Write-Ok "磁盘：项目盘剩余 $freeGb GB（要求 ≥ 1 GB）"
} else {
    Write-Bad "磁盘：项目盘剩余 $freeGb GB，低于 1 GB"
}

switch ($exitCode) {
    0 { Write-Host ""; Write-Host "结论：环境健康 (exit_code=0)" }
    1 { Write-Host ""; Write-Host "结论：可用但有警告 (exit_code=1)" }
    default { Write-Host ""; Write-Host "结论：存在严重问题，请先修复 (exit_code=2)" }
}

exit $exitCode
