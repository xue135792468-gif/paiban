#!/usr/bin/env bash
# 本地一键启动：数据库（可选）+ 后端 + 前端
set -euo pipefail
cd "$(dirname "$0")/.."

if command -v docker >/dev/null 2>&1; then
  echo "[1/3] 启动 PostgreSQL ..."
  docker compose up -d postgres
else
  echo "[1/3] 未检测到 docker，跳过 PostgreSQL（后端使用内嵌 H2）"
fi

echo "[2/3] 启动后端 http://localhost:8080 ..."
(cd backend && ./mvnw -q spring-boot:run) &
BACKEND_PID=$!

echo "[3/3] 启动前端 http://localhost:5173 ..."
(cd frontend && npm install --no-audit --no-fund && npm run dev) &
FRONTEND_PID=$!

trap 'kill $BACKEND_PID $FRONTEND_PID 2>/dev/null || true' EXIT INT TERM
wait
