#!/usr/bin/env bash
# 提交前校验：后端测试 + 前端 lint/build
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> backend: ./mvnw verify"
(cd backend && ./mvnw -B verify)

echo "==> frontend: lint + build"
(cd frontend && npm run lint && npm run build)
