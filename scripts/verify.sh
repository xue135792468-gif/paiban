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

echo "==> frontend: SFC size"
bash scripts/check-sfc-size.sh

if [[ $FULL -eq 1 ]]; then
  echo "==> e2e: Playwright（隔离端口 18080/15173，不复用本地 dev 服务）"
  export PAIBAN_E2E_ISOLATED=1
  if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then
    # mvnw.cmd (spawned by Playwright) does not understand MSYS /e/ paths.
    export MAVEN_ARGS="-s $(cygpath -w "$PWD/.tools/maven-settings.xml") -Dmaven.repo.local=$(cygpath -w "$PWD/.tools/m2repo")"
  fi
  (cd frontend && npx playwright install chromium && npm run test:e2e)
fi

echo "==> verify: ok"
