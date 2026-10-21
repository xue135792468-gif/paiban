#!/usr/bin/env bash
# 本地闸门：与 CI 走同一条路径。默认后端测试 + 前端 lint/build；--full 追加 Playwright。
set -euo pipefail
cd "$(dirname "$0")/.."

# The repo ships its own JDK/Maven under .tools; use it when present so the gate
# works from a fresh shell and Playwright inherits JAVA_HOME.
if [[ -f .tools/env.sh ]]; then
  # shellcheck disable=SC1091
  source .tools/env.sh
fi

FULL=0
if [[ "${1:-}" == "--full" ]]; then
  FULL=1
fi

MVN_ARGS=(-B verify)
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  MVN_ARGS+=(-Dtestcontainers=true)
else
  echo "==> 未检测到 Docker：跳过 PostgresContainerIT（CI 会真跑）"
fi

echo "==> backend: ./mvnw ${MVN_ARGS[*]}"
(cd backend && ./mvnw "${MVN_ARGS[@]}")

echo "==> frontend: lint + build"
(cd frontend && npm run lint && npm run build)

echo "==> frontend: unit tests"
(cd frontend && npm run test:unit)

if [[ $FULL -eq 1 ]]; then
  echo "==> e2e: Playwright（自动拉起后端与前端）"
  (cd frontend && npx playwright install chromium && npm run test:e2e)
fi

echo "==> verify: ok"
