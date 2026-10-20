$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $root '.tools\logs'
$pidFile = Join-Path $root '.tools\launch.pids'
$url = 'http://127.0.0.1:5173'

function Test-Port([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $Port)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-Port([int]$Port, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Wait-Health([int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 2
            if ($health.status -eq 'UP') { return $true }
        } catch {
        }
        Start-Sleep -Milliseconds 800
    }
    return $false
}

$backendUp = Test-Port 8080
$frontendUp = Test-Port 5173

if ($backendUp -and $frontendUp) {
    Write-Host '服务已经在运行，直接打开页面。'
    Start-Process $url
    exit 0
}

New-Item -ItemType Directory -Force -Path $logs | Out-Null
$started = @()

if (-not $backendUp) {
    $env:JAVA_HOME = Join-Path $root '.tools\jdk-21.0.12.1+1'
    if (-not (Test-Path $env:JAVA_HOME)) {
        throw "找不到 JDK：$env:JAVA_HOME"
    }
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
    $env:MAVEN_USER_HOME = Join-Path $root '.tools\m2home'

    $mavenConfig = Join-Path $root 'backend\.mvn\maven.config'
    if (-not (Test-Path $mavenConfig)) {
        $repoPath = ($root -replace '\\', '/') + '/.tools/m2repo'
        $env:MAVEN_ARGS = "-s `"$root\.tools\maven-settings.xml`" -Dmaven.repo.local=$repoPath"
    }

    Write-Host '正在启动后端（首次启动需要几十秒）...'
    $backend = Start-Process -FilePath (Join-Path $root 'backend\mvnw.cmd') `
        -ArgumentList '-q', 'spring-boot:run' `
        -WorkingDirectory (Join-Path $root 'backend') `
        -RedirectStandardOutput (Join-Path $logs 'backend.out.log') `
        -RedirectStandardError (Join-Path $logs 'backend.err.log') `
        -WindowStyle Hidden -PassThru
    $started += $backend.Id
}

if (-not $frontendUp) {
    Write-Host '正在启动前端 ...'
    $frontend = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList '/c', 'npm run dev -- --host 127.0.0.1' `
        -WorkingDirectory (Join-Path $root 'frontend') `
        -RedirectStandardOutput (Join-Path $logs 'frontend.out.log') `
        -RedirectStandardError (Join-Path $logs 'frontend.err.log') `
        -WindowStyle Hidden -PassThru
    $started += $frontend.Id
}

if ($started.Count -gt 0) {
    $started | Set-Content -Path $pidFile -Encoding ascii
}

if (-not $backendUp) {
    Write-Host '等待后端就绪 ...'
    if (-not (Wait-Health 180)) {
        throw "后端启动超时，请查看 $logs\backend.err.log"
    }
}
if (-not $frontendUp) {
    if (-not (Wait-Port 5173 60)) {
        throw "前端启动超时，请查看 $logs\frontend.err.log"
    }
}

Start-Process $url
Write-Host "已打开 $url"
Write-Host "停止服务：双击 停止排课系统.cmd"
