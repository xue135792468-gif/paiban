# 本地启动（PowerShell）
# 用法：powershell -ExecutionPolicy Bypass -File scripts/dev.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (Get-Command docker -ErrorAction SilentlyContinue) {
    Write-Host "[1/3] 启动 PostgreSQL ..."
    docker compose up -d postgres
} else {
    Write-Host "[1/3] 未检测到 docker，跳过 PostgreSQL（后端使用内嵌 H2）"
}

Write-Host "[2/3] 启动后端 http://localhost:8080 ..."
Start-Process -FilePath "cmd.exe" -ArgumentList "/c cd /d `"$root\backend`" && mvnw.cmd spring-boot:run" -WindowStyle Minimized

Write-Host "[3/3] 启动前端 http://localhost:5173 ..."
Start-Process -FilePath "cmd.exe" -ArgumentList "/c cd /d `"$root\frontend`" && npm install --no-audit --no-fund && npm run dev" -WindowStyle Minimized
