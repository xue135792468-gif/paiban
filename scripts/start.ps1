# 排课系统一键启动（Windows）。
# 入口是仓库根目录的 start.bat；本文件承载实际逻辑。
#
# 退出码：0 成功；1 启动失败；2 环境或前置条件不满足。
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib\env.ps1')

$script:Quiet = $false
$script:NoBuild = $false
$script:EnvOnly = $false
$script:Prod = $false
$script:ShowHelp = $false
$script:AdminPassword = ''
$script:Timeout = 60
$script:ComposeV2 = $false
$script:LastComposeExit = 0
$script:ProdComposeFile = 'docker-compose.prod.yml'

function Write-Info([string]$Message) {
    if (-not $script:Quiet) { Write-Host $Message }
}

function Write-Warn([string]$Message) {
    [Console]::Error.WriteLine("[警告] $Message")
}

function Write-Err([string]$Message) {
    [Console]::Error.WriteLine("[错误] $Message")
}

function Show-Usage {
    Write-Host '排课系统一键启动'
    Write-Host ''
    Write-Host '用法: start.bat [选项]'
    Write-Host ''
    Write-Host '  --quiet, --no-prompt       非交互模式：不打印进度、不等待输入'
    Write-Host '  --admin-password=VALUE     指定初始管理员密码（默认 admin123）'
    Write-Host '  --timeout=SECONDS          等待后端就绪的最长秒数（默认 60）'
    Write-Host '  --no-build                 不重新构建镜像，直接启动已有镜像'
    Write-Host '  --env-only                 只生成/检查 .env，不启动服务'
    Write-Host '  --prod                     使用生产栈（docker-compose.prod.yml，nginx 终止 TLS）'
    Write-Host '  -h, --help                 显示本帮助'
    Write-Host ''
    Write-Host '退出码: 0 成功, 1 启动失败, 2 环境或前置条件不满足'
}

function Show-FailureHint {
    $composeHint = if ($script:Prod) { " -f $($script:ProdComposeFile)" } else { '' }
    [Console]::Error.WriteLine('')
    [Console]::Error.WriteLine('排查建议：')
    [Console]::Error.WriteLine("  1. 查看后端日志：docker compose$composeHint logs backend")
    [Console]::Error.WriteLine('  2. 运行自检：doctor.bat（Windows）或 ./doctor.sh（Linux/macOS）')
    [Console]::Error.WriteLine('  3. 首次构建较慢时可加大等待时间：--timeout=180')
}

# Native commands can emit stderr with $ErrorActionPreference = 'Stop' and turn
# a harmless probe into a terminating error, so probes run with 'Continue'.
function Test-DockerDaemon {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker info *> $null
        return ($LASTEXITCODE -eq 0)
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Test-ComposeV2 {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker compose version *> $null
        return ($LASTEXITCODE -eq 0)
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Get-AvailableMemoryKb {
    try {
        $os = Get-CimInstance -ClassName Win32_OperatingSystem -ErrorAction Stop
        return [long]$os.FreePhysicalMemory
    } catch {
        return $null
    }
}

function Test-PortInUse([int]$Port) {
    try {
        $listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop
        return (@($listeners).Count -gt 0)
    } catch {
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
}

function Get-PortOwner([int]$Port) {
    try {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop |
            Select-Object -First 1
        if ($conn) {
            $proc = Get-Process -Id $conn.OwningProcess -ErrorAction Stop
            return $proc.ProcessName
        }
    } catch {
        return ''
    }
    return ''
}

function Invoke-ProdHealthRequest {
    if ($PSVersionTable.PSVersion.Major -ge 6) {
        return Invoke-WebRequest -Uri 'https://127.0.0.1/api/health' `
            -UseBasicParsing -TimeoutSec 3 -SkipCertificateCheck
    }
    # Windows PowerShell 5.1 has no -SkipCertificateCheck: accept the self-signed
    # certificate only for this request, then restore the default validator.
    [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
    try {
        return Invoke-WebRequest -Uri 'https://127.0.0.1/api/health' `
            -UseBasicParsing -TimeoutSec 3
    } finally {
        [System.Net.ServicePointManager]::ServerCertificateValidationCallback = $null
    }
}

function Test-Health {
    try {
        if ($script:Prod) {
            $response = Invoke-ProdHealthRequest
        } else {
            $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/health' `
                -UseBasicParsing -TimeoutSec 2
        }
        return ($response.StatusCode -eq 200 -and $response.Content -match '"UP"')
    } catch {
        return $false
    }
}

function Wait-Health([int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        if (Test-Health) { return $true }
        if ((Get-Date) -ge $deadline) { return $false }
        Start-Sleep -Seconds 2
    }
}

function Invoke-ComposeStream([string[]]$ComposeArgs) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($script:ComposeV2) {
            & docker compose @ComposeArgs
        } else {
            & docker-compose @ComposeArgs
        }
        $script:LastComposeExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Invoke-ComposeCapture([string[]]$ComposeArgs) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($script:ComposeV2) {
            $output = & docker compose @ComposeArgs 2>&1
        } else {
            $output = & docker-compose @ComposeArgs 2>&1
        }
        $script:LastComposeExit = $LASTEXITCODE
        return $output
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Initialize-ProdCerts {
    $certsDir = Join-Path $root 'deploy\certs'
    $cert = Join-Path $certsDir 'fullchain.pem'
    $key = Join-Path $certsDir 'privkey.pem'
    if ((Test-Path -LiteralPath $cert) -and (Test-Path -LiteralPath $key)) {
        return
    }

    New-Item -ItemType Directory -Force -Path $certsDir | Out-Null
    Write-Warn '未找到 deploy/certs/fullchain.pem 或 privkey.pem，正在生成自签证书（仅用于测试）。'

    $generated = $false
    $openssl = Get-Command openssl -ErrorAction SilentlyContinue
    if ($openssl) {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & $openssl.Source req -x509 -nodes -newkey rsa:2048 -days 365 `
                -keyout $key -out $cert -subj '/CN=localhost' `
                -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' *> $null
            $generated = ($LASTEXITCODE -eq 0)
        } finally {
            $ErrorActionPreference = $previous
        }
    }

    if (-not $generated) {
        # A failed openssl attempt can leave a half-written pair behind.
        Remove-Item -LiteralPath $key, $cert -Force -ErrorAction SilentlyContinue
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & docker run --rm -v "${certsDir}:/certs" alpine/openssl req -x509 -nodes `
                -newkey rsa:2048 -days 365 `
                -keyout /certs/privkey.pem -out /certs/fullchain.pem `
                -subj '/CN=localhost' `
                -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1'
            $generated = ($LASTEXITCODE -eq 0)
        } finally {
            $ErrorActionPreference = $previous
        }
    }

    if (-not $generated) {
        Write-Err '生成自签证书失败。请把 fullchain.pem 与 privkey.pem 放到 deploy/certs/ 后重试。'
        exit 2
    }

    [Console]::Error.WriteLine('')
    [Console]::Error.WriteLine('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!')
    [Console]::Error.WriteLine('!! 警告：已生成自签证书，仅用于本机测试！')
    [Console]::Error.WriteLine("!! 正式使用前必须替换为受信任证书（Let's Encrypt / Caddy / 商业证书）。")
    [Console]::Error.WriteLine('!! 浏览器会提示不受信任；飞书等客户端会直接拒绝该证书。')
    [Console]::Error.WriteLine('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!')
    [Console]::Error.WriteLine('')
}

# ------------------------------------------------------------- parse flags
foreach ($arg in $args) {
    switch -Regex ($arg) {
        '^--quiet$' { $script:Quiet = $true; break }
        '^--no-prompt$' { $script:Quiet = $true; break }
        '^--no-build$' { $script:NoBuild = $true; break }
        '^--env-only$' { $script:EnvOnly = $true; break }
        '^--prod$' { $script:Prod = $true; break }
        '^-h$|^--help$' { $script:ShowHelp = $true; break }
        '^--admin-password=(.*)$' {
            if ([string]::IsNullOrWhiteSpace($Matches[1])) {
                Write-Err '--admin-password 不能为空'
                exit 2
            }
            $script:AdminPassword = $Matches[1]
            break
        }
        '^--timeout=(\d+)$' {
            $script:Timeout = [int]$Matches[1]
            if ($script:Timeout -le 0) {
                Write-Err '--timeout 必须大于 0'
                exit 2
            }
            break
        }
        default {
            Write-Err "未知参数：$arg（运行 start.bat --help 查看用法）"
            exit 2
        }
    }
}

if ($script:ShowHelp) {
    Show-Usage
    exit 0
}

# ------------------------------------------------------- env-only mode
if ($script:EnvOnly) {
    try {
        $envResult = Initialize-PaibanEnv -Root $root -AdminPassword $script:AdminPassword -Quiet $script:Quiet
        Write-Info ("[完成] .env 已就绪：{0}" -f $envResult.File)
        exit 0
    } catch {
        Write-Err $_.Exception.Message
        exit 2
    }
}

# ------------------------------------------------------------ 1. Docker
Write-Info '[1/5] 检查 Docker ...'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Err '未检测到 Docker。'
    [Console]::Error.WriteLine('请先安装 Docker Desktop：https://www.docker.com/products/docker-desktop')
    [Console]::Error.WriteLine('安装完成后重新运行本脚本。')
    exit 2
}

if (-not (Test-DockerDaemon)) {
    Write-Err 'Docker 未启动，请打开 Docker Desktop，等待状态显示为运行中后重试。'
    exit 2
}

$script:ComposeV2 = Test-ComposeV2
if (-not $script:ComposeV2 -and -not (Get-Command docker-compose -ErrorAction SilentlyContinue)) {
    Write-Err '未找到 docker compose。请安装 Docker Desktop（自带 Compose）后重试。'
    exit 2
}

# ---------------------------------------------------- 2. memory and ports
Write-Info '[2/5] 检查内存与端口 ...'
$freeKb = Get-AvailableMemoryKb
if ($null -ne $freeKb -and $freeKb -lt (2 * 1MB)) {
    Write-Warn ("可用内存约 {0:N1} GB，低于建议的 2 GB，构建和排课可能较慢。" -f ($freeKb / 1MB))
}

$portSpecs = if ($script:Prod) {
    @(
        @{ Port = 80; Name = 'HTTP（自动跳转 HTTPS）' },
        @{ Port = 443; Name = 'HTTPS' }
    )
} else {
    @(
        @{ Port = 8080; Name = '后端服务' },
        @{ Port = 5173; Name = '前端页面' },
        @{ Port = 5432; Name = 'PostgreSQL 数据库' }
    )
}
foreach ($spec in $portSpecs) {
    if (Test-PortInUse -Port $spec.Port) {
        $owner = Get-PortOwner -Port $spec.Port
        $ownerText = if ($owner) { "：$owner" } else { '' }
        Write-Warn ("端口 {0}（{1}）已被占用{2}。如果本系统已经在运行，可以忽略；否则请先释放该端口。" -f $spec.Port, $spec.Name, $ownerText)
    }
}

# ------------------------------------------------------------ 3. .env
Write-Info '[3/5] 准备 .env 密钥 ...'
try {
    $envResult = Initialize-PaibanEnv -Root $root -AdminPassword $script:AdminPassword -Quiet $script:Quiet
} catch {
    Write-Err $_.Exception.Message
    exit 2
}
$effectiveAdmin = $envResult.AdminPassword

# ------------------------------------------------------------ 4. compose
Write-Info '[4/5] 构建并启动容器（首次可能需要几分钟）...'
$composeArgs = @()
if ($script:Prod) {
    Initialize-ProdCerts
    $composeArgs += @('-f', $script:ProdComposeFile)
}
$composeArgs += @('up', '-d')
if (-not $script:NoBuild) {
    $composeArgs += '--build'
}

Push-Location $root
try {
    if ($script:Quiet) {
        $logDir = Join-Path $root '.tools\logs'
        New-Item -ItemType Directory -Force -Path $logDir | Out-Null
        $logPath = Join-Path $logDir 'start-compose.log'
        $composeOutput = Invoke-ComposeCapture $composeArgs | ForEach-Object { $_.ToString() }
        $composeCode = $script:LastComposeExit
        $composeOutput | Set-Content -Path $logPath -Encoding UTF8
        if ($composeCode -ne 0) {
            Write-Err '容器启动失败（构建或启动阶段出错）。'
            [Console]::Error.WriteLine('最近日志：')
            $composeOutput | Select-Object -Last 40 | ForEach-Object { [Console]::Error.WriteLine($_) }
            Show-FailureHint
            exit 1
        }
    } else {
        Invoke-ComposeStream $composeArgs
        if ($script:LastComposeExit -ne 0) {
            Write-Err '容器启动失败（构建或启动阶段出错）。'
            Show-FailureHint
            exit 1
        }
    }
} finally {
    Pop-Location
}

# ------------------------------------------------------------ 5. health
Write-Info ("[5/5] 等待后端就绪（最多 {0} 秒）..." -f $script:Timeout)
if (-not (Wait-Health -TimeoutSeconds $script:Timeout)) {
    Write-Err ("后端在 {0} 秒内没有就绪。" -f $script:Timeout)
    [Console]::Error.WriteLine('可能原因：首次构建尚未完成、内存不足、或后端启动报错。')
    Show-FailureHint
    exit 1
}

# ------------------------------------------------------------ done
Write-Host ''
Write-Host '[完成] 排课系统已启动'
if ($script:Prod) {
    Write-Host '  前端地址: https://localhost'
    Write-Host '  健康检查: https://localhost/api/health'
    Write-Host '  （HTTP 会自动跳转到 HTTPS；自签证书需在浏览器中确认一次）'
} else {
    Write-Host '  前端地址: http://localhost:5173'
    Write-Host '  后端地址: http://localhost:8080'
}
if ($envResult.Created) {
    Write-Host ("  默认账号: admin / {0}" -f $effectiveAdmin)
} else {
    Write-Host '  默认账号: admin（密码为首次启动时设置的密码；未修改过则为 admin123）'
    if (-not [string]::IsNullOrWhiteSpace($script:AdminPassword)) {
        Write-Warn '--admin-password 只在首次创建 admin 账号时生效；本次没有修改已有账号的密码。'
    }
}
Write-Host ''
Write-Host '首次使用请访问前端地址，按初始化向导操作。'
if ($script:Prod) {
    Write-Host ("停止服务: docker compose -f {0} down" -f $script:ProdComposeFile)
} else {
    Write-Host '停止服务: docker compose down'
}
exit 0
