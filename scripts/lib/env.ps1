# .env bootstrap helper, used only by scripts/start.ps1.
#
# Responsibilities:
#   1. copy .env.example to .env when .env is missing;
#   2. generate random secrets only when the value is empty or still a placeholder;
#   3. make sure every ${VAR:?err} in docker-compose.yml exists before startup.

function New-PaibanHex {
    param([int]$ByteCount = 32)
    $bytes = New-Object byte[] $ByteCount
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Test-PaibanPlaceholder {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $true }
    return ($Value -match '^(?i)(replace[-_]with|change[-_]?me|your[-_]|todo)')
}

function Get-PaibanEnvValue {
    param($Lines, [string]$Key)
    $pattern = '^\s*' + [regex]::Escape($Key) + '=(.*)$'
    foreach ($line in $Lines) {
        if ($line -match $pattern) {
            return $Matches[1].Trim()
        }
    }
    return $null
}

function Set-PaibanEnvValue {
    param($Lines, [string]$Key, [string]$Value)
    $pattern = '^\s*' + [regex]::Escape($Key) + '='
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match $pattern) {
            $Lines[$i] = "$Key=$Value"
            return
        }
    }
    [void]$Lines.Add("$Key=$Value")
}

function Hide-PaibanValue {
    param([string]$Value)
    if ($Value.Length -le 8) { return '****' }
    return $Value.Substring(0, 4) + '****'
}

function Initialize-PaibanEnv {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$AdminPassword = '',
        [bool]$Quiet = $false
    )

    $envFile = Join-Path $Root '.env'
    $exampleFile = Join-Path $Root '.env.example'
    $created = $false

    if (-not (Test-Path $envFile)) {
        if (-not (Test-Path $exampleFile)) {
            throw "缺少 .env.example：$exampleFile"
        }
        Copy-Item -Path $exampleFile -Destination $envFile
        $created = $true
        if (-not $Quiet) { Write-Host '[信息] 未找到 .env，已从 .env.example 创建。' }
    }

    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($line in [System.IO.File]::ReadAllLines($envFile)) {
        $lines.Add($line)
    }

    $generated = @()
    $secretSpecs = @(
        @{ Key = 'PAIBAN_JWT_SECRET'; Bytes = 32; Label = 'JWT 签名密钥' },
        @{ Key = 'PAIBAN_AI_ENCRYPTION_KEY'; Bytes = 32; Label = 'AI 加密密钥' },
        @{ Key = 'POSTGRES_PASSWORD'; Bytes = 16; Label = '数据库密码' }
    )

    foreach ($spec in $secretSpecs) {
        $current = Get-PaibanEnvValue -Lines $lines -Key $spec.Key
        if (Test-PaibanPlaceholder -Value $current) {
            $value = New-PaibanHex -ByteCount $spec.Bytes
            Set-PaibanEnvValue -Lines $lines -Key $spec.Key -Value $value
            $generated += $spec.Key
            if (-not $Quiet) {
                Write-Host ("[信息] 已生成 {0}：{1}" -f $spec.Label, (Hide-PaibanValue -Value $value))
            }
        }
    }

    $adminKey = 'PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD'
    $currentAdmin = Get-PaibanEnvValue -Lines $lines -Key $adminKey
    if (-not [string]::IsNullOrWhiteSpace($AdminPassword)) {
        $effectiveAdmin = $AdminPassword
        if ($AdminPassword -ne $currentAdmin) {
            Set-PaibanEnvValue -Lines $lines -Key $adminKey -Value $AdminPassword
            $generated += $adminKey
            if (-not $Quiet) { Write-Host '[信息] 已按 --admin-password 设置初始管理员密码。' }
        }
    } elseif (-not (Test-PaibanPlaceholder -Value $currentAdmin)) {
        $effectiveAdmin = $currentAdmin
    } else {
        $effectiveAdmin = 'admin123'
        Set-PaibanEnvValue -Lines $lines -Key $adminKey -Value $effectiveAdmin
        $generated += $adminKey
        if (-not $Quiet) { Write-Host "[信息] 已设置初始管理员密码：admin / $effectiveAdmin" }
    }

    [System.IO.File]::WriteAllLines($envFile, $lines.ToArray())

    return @{
        File          = $envFile
        AdminPassword = $effectiveAdmin
        Created       = $created
        Generated     = $generated
    }
}
