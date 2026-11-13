#!/usr/bin/env bash
# scripts/verify.sh 阶段编排的回归测试（retro 第 2 项：快检查优先）。
#
# 用法：bash scripts/tests/test-verify-order.sh
# 退出：0 = 全部断言通过；1 = 至少一条断言失败
#
# 覆盖范围（只验证「编排」，不验证业务）：
#   * default / --quick / --full / --deploy 四种模式与无效参数
#   * 仓库检查与前端 lint/build/unit 先于后端 compile/verify，E2E 最后
#   * 快阶段失败时后端与 E2E 不启动
#   * --deploy 缺 Docker 作为已知前置尽早失败；--full 缺 Docker 明确跳过真实 PG
#   * PAIBAN_MVN_EXTRA_ARGS、-Dtestcontainers=true、E2E 隔离端口覆盖
#   * 八项仓库检查和原有前后端/E2E 阶段每项在各模式下各执行一次
#
# 这里全部使用假命令（假 npm/npx/docker、假 mvnw、假 check-*.sh），只观察调用顺序、
# 调用次数、参数与退出码；它们不构成后端/前端/浏览器测试证据。
#
# 夹具与输出一律落在本工作树 .runtime/retro-order/ 下（不使用 /tmp 默认目录），
# 清理前校验真实路径仍在该专用根内且没有符号链接越界。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
VERIFY="$ROOT_DIR/scripts/verify.sh"

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); printf 'ok   - %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf 'FAIL - %s\n' "$1"; }

RUNTIME_ROOT="$ROOT_DIR/.runtime/retro-order"
CASES="$RUNTIME_ROOT/cases"
FAKEBIN="$RUNTIME_ROOT/fakebin"

REAL_ROOT="$(cd "$ROOT_DIR" && pwd -P)"
mkdir -p "$CASES" "$FAKEBIN"
REAL_RUNTIME="$(cd "$RUNTIME_ROOT" && pwd -P)"
case "$REAL_RUNTIME" in
  "$REAL_ROOT"/.runtime/retro-order) ;;
  *)
    printf 'refusing: fixture root %s is not %s/.runtime/retro-order\n' \
      "$REAL_RUNTIME" "$REAL_ROOT" >&2
    exit 1
    ;;
esac

# 只允许删除专用夹具根之下的路径；出现符号链接时拒绝，避免顺着链接删到工作区外。
safe_rm_tree() {
  local target="$1" real_target
  [[ -e "$target" ]] || return 0
  real_target="$(cd "$target" && pwd -P)"
  case "$real_target" in
    "$REAL_RUNTIME"/*) ;;
    *)
      printf 'refusing to remove %s (outside %s)\n' "$real_target" "$REAL_RUNTIME" >&2
      return 1
      ;;
  esac
  if [[ -n "$(find "$target" -type l -print -quit 2>/dev/null)" ]]; then
    printf 'refusing to remove %s: contains symlinks\n' "$target" >&2
    return 1
  fi
  rm -rf -- "$target"
}

cleanup() {
  local entry
  for entry in "$CASES"/*; do
    [[ -e "$entry" ]] && safe_rm_tree "$entry"
  done
  rmdir "$CASES" 2>/dev/null || true
  safe_rm_tree "$FAKEBIN"
}
trap cleanup EXIT

# ---------------------------------------------------------------- 假命令

write_fake() {
  local path="$1"
  cat > "$path"
  chmod +x "$path"
}

write_fake "$FAKEBIN/npm" <<'FAKE'
#!/usr/bin/env bash
printf 'frontend npm %s\n' "$*" >> "$ORDER_LOG"
if [[ "$*" == "run test:e2e" ]]; then
  printf 'e2e-env ISOLATED=%s BACKEND_PORT=%s FRONTEND_PORT=%s\n' \
    "${PAIBAN_E2E_ISOLATED:-unset}" \
    "${PAIBAN_E2E_BACKEND_PORT:-unset}" \
    "${PAIBAN_E2E_FRONTEND_PORT:-unset}" >> "$ORDER_LOG"
fi
if [[ -n "${FAKE_NPM_FAIL:-}" && "$*" == "${FAKE_NPM_FAIL}" ]]; then
  printf 'fake npm failure: %s\n' "$*" >&2
  exit 3
fi
exit 0
FAKE

write_fake "$FAKEBIN/npx" <<'FAKE'
#!/usr/bin/env bash
printf 'e2e npx %s\n' "$*" >> "$ORDER_LOG"
exit 0
FAKE

write_fake "$FAKEBIN/docker" <<'FAKE'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >> "$ORDER_LOG"
if [[ "${1:-}" == "info" ]]; then
  [[ "${FAKE_DOCKER_READY:-0}" == "1" ]] && exit 0
  exit 1
fi
exit 0
FAKE

# 与 scripts/verify.sh 调用的站点检查一一对应；顺序断言依赖这些名字。
REPO_CHECKS=(
  check-sfc-size
  check-local-dates
  check-e2e-helpers
  check-blank-checks
  check-module-boundaries
  check-migrations
  check-ticket-status
  check-ai-http-timeouts
)

make_case() {
  local name="$1"
  CASE="$CASES/$name"
  mkdir -p "$CASE/scripts" "$CASE/backend" "$CASE/frontend"
  # 测试的就是公有脚本本身：拷进隔离小仓库，随后校验字节一致。
  cp "$VERIFY" "$CASE/scripts/verify.sh"

  local check
  for check in "${REPO_CHECKS[@]}"; do
    {
      printf '#!/usr/bin/env bash\n'
      printf 'printf %s %s >> "$ORDER_LOG"\n' "'repo $check\n'" ""
      printf 'if [[ "${FAKE_FAIL_CHECK:-}" == "%s" ]]; then\n' "$check"
      printf '  printf "fake %s failure\n" >&2\n' "$check"
      printf '  exit 1\n'
      printf 'fi\n'
      printf 'exit 0\n'
    } > "$CASE/scripts/$check.sh"
    chmod +x "$CASE/scripts/$check.sh"
  done

  write_fake "$CASE/backend/mvnw" <<'FAKE'
#!/usr/bin/env bash
printf 'backend mvnw %s\n' "$*" >> "$ORDER_LOG"
exit 0
FAKE

  ORDER_LOG="$CASE/order.log"
  : > "$ORDER_LOG"
  export ORDER_LOG

  FAKE_FAIL_CHECK=""
  FAKE_NPM_FAIL=""
  FAKE_DOCKER_READY=0
  PAIBAN_MVN_EXTRA_ARGS=""
  PAIBAN_E2E_BACKEND_PORT=""
  PAIBAN_E2E_FRONTEND_PORT=""
  export FAKE_FAIL_CHECK FAKE_NPM_FAIL FAKE_DOCKER_READY \
    PAIBAN_MVN_EXTRA_ARGS PAIBAN_E2E_BACKEND_PORT PAIBAN_E2E_FRONTEND_PORT
  unset PAIBAN_E2E_ISOLATED 2>/dev/null || true
}

run_verify() {
  local rc=0
  PATH="$FAKEBIN:/usr/bin:/bin" \
    bash "$CASE/scripts/verify.sh" "$@" \
    > "$CASE/stdout.log" 2> "$CASE/stderr.log" || rc=$?
  STATUS=$rc
}

# ---------------------------------------------------------------- 断言工具

assert_exit() {
  local expected="$1" label="$2"
  if [[ "$STATUS" -eq "$expected" ]]; then
    pass "$label: exit $expected"
  else
    fail "$label: exit $expected (got $STATUS)"
    sed 's/^/       out| /' "$CASE/stdout.log" 2>/dev/null | head -n 20
    sed 's/^/       err| /' "$CASE/stderr.log" 2>/dev/null | head -n 20
  fi
}

assert_log_has() {
  local needle="$1" label="$2"
  if grep -qF -- "$needle" "$ORDER_LOG"; then
    pass "$label"
  else
    fail "$label (missing: $needle)"
    sed 's/^/       log| /' "$ORDER_LOG" | head -n 30
  fi
}

assert_log_lacks() {
  local needle="$1" label="$2"
  if grep -qF -- "$needle" "$ORDER_LOG"; then
    fail "$label (unexpected: $needle)"
    sed 's/^/       log| /' "$ORDER_LOG" | head -n 30
  else
    pass "$label"
  fi
}

assert_log_empty() {
  local label="$1"
  if [[ -s "$ORDER_LOG" ]]; then
    fail "$label (log not empty)"
    sed 's/^/       log| /' "$ORDER_LOG" | head -n 30
  else
    pass "$label"
  fi
}

assert_log_lines() {
  local expected="$1" label="$2" actual
  actual="$(grep -cv '^$' "$ORDER_LOG" 2>/dev/null || true)"
  actual="${actual:-0}"
  if [[ "$actual" -eq "$expected" ]]; then
    pass "$label: $expected stage line(s)"
  else
    fail "$label: expected $expected stage line(s), got $actual"
    sed 's/^/       log| /' "$ORDER_LOG" | head -n 30
  fi
}

assert_count() {
  local needle="$1" expected="$2" label="$3" actual
  actual="$(grep -cF -- "$needle" "$ORDER_LOG" 2>/dev/null || true)"
  actual="${actual:-0}"
  if [[ "$actual" -eq "$expected" ]]; then
    pass "$label: ${expected}x"
  else
    fail "$label: expected ${expected}x, got $actual"
    sed 's/^/       log| /' "$ORDER_LOG" | head -n 30
  fi
}

line_no() {
  local needle="$1" n
  n="$(grep -nF -- "$needle" "$ORDER_LOG" | head -n1 | cut -d: -f1)"
  printf '%s' "${n:-0}"
}

assert_before() {
  local first="$1" second="$2" label="$3" a b
  a="$(line_no "$first")"
  b="$(line_no "$second")"
  if [[ "$a" -gt 0 && "$b" -gt 0 && "$a" -lt "$b" ]]; then
    pass "$label"
  else
    fail "$label ($first@${a} not before $second@${b})"
    sed 's/^/       log| /' "$ORDER_LOG" | head -n 30
  fi
}

last_log_line() { grep -v '^$' "$ORDER_LOG" | tail -n1; }

assert_last_line_is_e2e() {
  local label="$1" line
  line="$(last_log_line)"
  if [[ "$line" == e2e* ]]; then
    pass "$label"
  else
    fail "$label (last stage: ${line:-<empty>})"
  fi
}

assert_stdout_has() {
  local needle="$1" label="$2"
  if grep -qF -- "$needle" "$CASE/stdout.log"; then
    pass "$label"
  else
    fail "$label (stdout missing: $needle)"
    sed 's/^/       out| /' "$CASE/stdout.log" | head -n 30
  fi
}

assert_stdout_lacks() {
  local needle="$1" label="$2"
  if grep -qF -- "$needle" "$CASE/stdout.log"; then
    fail "$label (stdout has: $needle)"
    sed 's/^/       out| /' "$CASE/stdout.log" | head -n 30
  else
    pass "$label"
  fi
}

assert_stderr_has() {
  local needle="$1" label="$2"
  if grep -qF -- "$needle" "$CASE/stderr.log"; then
    pass "$label"
  else
    fail "$label (stderr missing: $needle)"
    sed 's/^/       err| /' "$CASE/stderr.log" | head -n 30
  fi
}

sha_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# ---------------------------------------------------------------- 用例

printf '# verify.sh order regression (fixtures: %s)\n\n' "$RUNTIME_ROOT"

# 0. 被测文件必须就是公有脚本（拷进夹具前/后字节一致）。
make_case identity
if [[ "$(sha_of "$VERIFY")" == "$(sha_of "$CASE/scripts/verify.sh")" ]]; then
  pass "fixture copy is byte-identical to scripts/verify.sh"
else
  fail "fixture copy differs from scripts/verify.sh"
fi

# 1. 无效参数：退 2，什么都不跑。
make_case invalid-arg
run_verify --bogus
assert_exit 2 "invalid argument"
assert_stderr_has "usage: verify.sh" "invalid argument prints usage"
assert_log_empty "invalid argument starts no stage"

# 2. --quick：仓库检查 + 前端 lint/build/unit + 后端编译；不跑后端测试、不跑 E2E。
make_case quick
run_verify --quick
assert_exit 0 "--quick"
assert_count "backend mvnw -B -DskipTests compile" 1 "--quick runs backend compile once"
assert_log_lacks "backend mvnw -B verify" "--quick does not run backend verify"
assert_log_lacks "e2e " "--quick runs no e2e"
assert_log_lacks "docker info" "--quick does not probe docker"
assert_count "frontend npm run lint" 1 "--quick runs frontend lint once"
assert_count "frontend npm run build" 1 "--quick runs frontend build once"
assert_count "frontend npm run test:unit" 1 "--quick runs frontend unit once"
for check in "${REPO_CHECKS[@]}"; do
  assert_count "repo $check" 1 "--quick runs $check once"
done
assert_before "repo check-ai-http-timeouts" "frontend npm run lint" \
  "--quick: repo checks precede frontend lint"
assert_before "frontend npm run test:unit" "backend mvnw -B -DskipTests compile" \
  "--quick: frontend unit precedes backend compile"

# 3. default：全部快检查先跑，然后后端 verify；没有 E2E；缺 Docker 明确跳过真实 PG。
make_case default-all
run_verify
assert_exit 0 "default"
assert_count "backend mvnw -B verify" 1 "default runs backend verify once"
assert_log_lacks "backend mvnw -B verify -Dtestcontainers" \
  "default without docker does not enable testcontainers"
assert_log_lacks "e2e " "default runs no e2e"
for check in "${REPO_CHECKS[@]}"; do
  assert_count "repo $check" 1 "default runs $check once"
done
assert_count "frontend npm run lint" 1 "default runs frontend lint once"
assert_count "frontend npm run build" 1 "default runs frontend build once"
assert_count "frontend npm run test:unit" 1 "default runs frontend unit once"
assert_before "repo check-ai-http-timeouts" "frontend npm run lint" \
  "default: repo checks precede frontend"
assert_before "frontend npm run test:unit" "backend mvnw -B verify" \
  "default: frontend precedes backend verify"
assert_stdout_has "Docker not found: skipping PostgresContainerIT" \
  "default prints the deliberate PG skip"
assert_count "docker info" 1 "default probes docker once"

# 4. 快阶段失败（仓库检查）：后端与 E2E 都不启动。
make_case fail-repo-check
FAKE_FAIL_CHECK=check-module-boundaries
export FAKE_FAIL_CHECK
run_verify
assert_exit 1 "repo check failure"
assert_log_has "repo check-module-boundaries" "failing repo check did run"
assert_log_lacks "frontend npm" "repo check failure stops frontend"
assert_log_lacks "backend mvnw" "repo check failure stops backend"
assert_log_lacks "e2e " "repo check failure stops e2e"

# 5. 快阶段失败（前端 lint）：后端与 E2E 都不启动，失败码原样透出。
make_case fail-frontend
FAKE_NPM_FAIL="run lint"
export FAKE_NPM_FAIL
run_verify
assert_exit 3 "frontend lint failure"
assert_log_lacks "frontend npm run build" "lint failure stops frontend build"
assert_log_lacks "frontend npm run test:unit" "lint failure stops frontend unit"
assert_log_lacks "backend mvnw" "lint failure stops backend"
assert_log_lacks "e2e " "lint failure stops e2e"

# 6. --full：包含 E2E，且 E2E 在后端之后、作为最后阶段。
make_case full
run_verify --full
assert_exit 0 "--full"
assert_count "backend mvnw -B verify" 1 "--full runs backend verify once"
assert_log_has "e2e npx playwright install chromium" "--full installs playwright"
assert_count "frontend npm run test:e2e" 1 "--full runs e2e once"
assert_before "frontend npm run test:unit" "backend mvnw -B verify" \
  "--full: frontend precedes backend"
assert_before "backend mvnw -B verify" "frontend npm run test:e2e" \
  "--full: backend precedes e2e"
assert_last_line_is_e2e "--full: e2e is the last stage"
assert_stdout_has "Docker not found: skipping PostgresContainerIT" \
  "--full without docker skips real PG explicitly"

# 7. --full 的隔离端口覆盖：ISOLATED=1 与用户覆盖值都到达 E2E。
make_case full-ports
PAIBAN_E2E_BACKEND_PORT=28123
PAIBAN_E2E_FRONTEND_PORT=25234
export PAIBAN_E2E_BACKEND_PORT PAIBAN_E2E_FRONTEND_PORT
run_verify --full
assert_exit 0 "--full with overridden ports"
assert_log_has "e2e-env ISOLATED=1 BACKEND_PORT=28123 FRONTEND_PORT=25234" \
  "e2e receives isolated flag and port overrides"

# 8. --deploy 缺 Docker：已知前置，非零且整条链都不启动。
make_case deploy-no-docker
run_verify --deploy
assert_exit 1 "--deploy without docker"
assert_stderr_has "Docker is required for --deploy" "--deploy explains docker requirement"
assert_count "docker info" 1 "--deploy probes docker once"
assert_log_lacks "repo " "--deploy without docker stops repo checks"
assert_log_lacks "frontend npm" "--deploy without docker stops frontend"
assert_log_lacks "backend mvnw" "--deploy without docker stops backend"
assert_log_lacks "e2e " "--deploy without docker stops e2e"
assert_log_lines 1 "--deploy without docker runs nothing but the docker probe"

# 9. --deploy 有 Docker：传 -Dtestcontainers=true，E2E 仍最后。
make_case deploy-with-docker
FAKE_DOCKER_READY=1
export FAKE_DOCKER_READY
run_verify --deploy
assert_exit 0 "--deploy with docker"
assert_count "docker info" 1 "--deploy probes docker once"
assert_count "backend mvnw -B verify -Dtestcontainers=true" 1 \
  "--deploy passes -Dtestcontainers=true"
assert_stdout_lacks "Docker not found" "--deploy with docker prints no skip message"
assert_last_line_is_e2e "--deploy: e2e is the last stage"

# 10. PAIBAN_MVN_EXTRA_ARGS 仍按空白拆成多个参数并追加在后端命令上。
make_case mvn-extra-args
PAIBAN_MVN_EXTRA_ARGS="-DforkCount=0 -Dfoo=bar"
export PAIBAN_MVN_EXTRA_ARGS
run_verify
assert_exit 0 "extra maven args"
assert_log_has "backend mvnw -B verify -DforkCount=0 -Dfoo=bar" \
  "extra maven args are split and appended"
assert_count "backend mvnw" 1 "extra maven args run backend once"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ $FAIL -eq 0 ]]
