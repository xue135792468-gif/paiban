#!/usr/bin/env bash
# 本地闸门。阶段顺序固定为「快检查 → 前端 → 后端 → E2E」：仓库级 shell 检查与前端
# lint/build/unit 先跑，几分钟的后端 compile/verify 随后，Playwright 永远最后。
# 默认：快检查 + 后端全量测试；--full 追加 Playwright；--quick 把后端换成编译，迭代期用；
# --deploy = --full + 要求 Docker（真跑 PostgresContainerIT），发布前用。
set -euo pipefail
cd "$(dirname "$0")/.."

# The repo ships its own JDK/Maven under .tools; use it when present so the gate
# works from a fresh shell and Playwright inherits JAVA_HOME.
if [[ -f .tools/env.sh ]]; then
  # shellcheck disable=SC1091
  source .tools/env.sh
fi

MODE=default
REQUIRE_DOCKER=0
case "${1:-}" in
  --full) MODE=full ;;
  --deploy) MODE=full; REQUIRE_DOCKER=1 ;;
  --quick) MODE=quick ;;
  "") ;;
  *) echo "usage: verify.sh [--full|--deploy|--quick]" >&2; exit 2 ;;
esac

if [[ $MODE == quick ]]; then
  DOCKER_READY=0
elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  DOCKER_READY=1
else
  DOCKER_READY=0
fi

# --deploy 的 Docker 是已知前置：在跑任何阶段之前判定，缺了立刻失败，不用等快检查跑完。
if [[ $REQUIRE_DOCKER -eq 1 && $DOCKER_READY -eq 0 ]]; then
  echo "==> Docker is required for --deploy: PostgresContainerIT must run against real PostgreSQL" >&2
  echo "    Start Docker, or use --full to skip that test deliberately." >&2
  exit 1
fi

echo "==> frontend: SFC size"
bash scripts/check-sfc-size.sh

echo "==> frontend: local dates"
bash scripts/check-local-dates.sh

echo "==> e2e: helper duplicates"
bash scripts/check-e2e-helpers.sh

echo "==> backend: isBlank ratchet"
bash scripts/check-blank-checks.sh

echo "==> backend: module boundaries"
bash scripts/check-module-boundaries.sh

echo "==> backend: migration versions"
bash scripts/check-migrations.sh

echo "==> repo: stale ticket statuses"
bash scripts/check-ticket-status.sh

echo "==> backend: AI HTTP timeouts"
bash scripts/check-ai-http-timeouts.sh

echo "==> frontend: lint + build"
(cd frontend && npm run lint && npm run build)

echo "==> frontend: unit tests"
(cd frontend && npm run test:unit)

if [[ $MODE == quick ]]; then
  echo "==> backend: ./mvnw -B -DskipTests compile"
  (cd backend && ./mvnw -B -DskipTests compile)
else
  MVN_ARGS=(-B verify)
  # 本机环境逃生口：surefire 分叉偶发被系统杀掉时，用
  # PAIBAN_MVN_EXTRA_ARGS=-DforkCount=0 跑全量门禁；CI 不设置该变量，仍是权威。
  if [[ -n "${PAIBAN_MVN_EXTRA_ARGS:-}" ]]; then
    read -r -a mvn_extra <<< "$PAIBAN_MVN_EXTRA_ARGS"
    MVN_ARGS+=("${mvn_extra[@]}")
  fi
  if [[ $DOCKER_READY -eq 1 ]]; then
    MVN_ARGS+=(-Dtestcontainers=true)
  else
    echo "==> Docker not found: skipping PostgresContainerIT (CI runs it; --deploy requires Docker)"
  fi

  echo "==> backend: ./mvnw ${MVN_ARGS[*]}"
  (cd backend && ./mvnw "${MVN_ARGS[@]}")
fi

if [[ $MODE == full ]]; then
  echo "==> e2e: Playwright（隔离端口 28080/25173，不复用本地 dev 服务）"
  export PAIBAN_E2E_ISOLATED=1
  if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then
    # mvnw.cmd (spawned by Playwright) does not understand MSYS /e/ paths.
    export MAVEN_ARGS="-s $(cygpath -w "$PWD/.tools/maven-settings.xml") -Dmaven.repo.local=$(cygpath -w "$PWD/.tools/m2repo")"
  fi
  # A stale Vite cache can make the esbuild service crash mid-run; drop it first.
  rm -rf frontend/node_modules/.vite
  (cd frontend && npx playwright install chromium && npm run test:e2e)
fi

echo "==> verify: ok"
