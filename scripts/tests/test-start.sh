#!/usr/bin/env bash
# Static and no-Docker tests for the one-click start scripts.
# Usage: bash scripts/tests/test-start.sh
# Exit code: 0 when every assertion passes, 1 otherwise.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PASS=0
FAIL=0
SKIP=0

pass() { PASS=$((PASS + 1)); printf 'PASS  %s\n' "$1"; }
fail() {
  FAIL=$((FAIL + 1))
  printf 'FAIL  %s\n' "$1"
  [ -n "${2:-}" ] && printf '      %s\n' "$2"
}
skip() { SKIP=$((SKIP + 1)); printf 'SKIP  %s\n' "$1"; }

assert_eq() { # description expected actual
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected [$2], got [$3]"; fi
}

assert_contains() { # description text fragment
  case "$2" in
    *"$3"*) pass "$1" ;;
    *) fail "$1" "output does not contain [$3]: $2" ;;
  esac
}

assert_match() { # description value regex
  if printf '%s' "$2" | grep -Eq "$3"; then
    pass "$1"
  else
    fail "$1" "value [$2] does not match /$3/"
  fi
}

TMP="$(mktemp -d)"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

# ------------------------------------------------------------- syntax check
echo '== 1. shell syntax =='
for file in "$ROOT/start.sh" "$ROOT/scripts/start.sh" "$ROOT/scripts/lib/env.sh" "${BASH_SOURCE[0]}"; do
  rel="${file#"$ROOT"/}"
  if bash -n "$file"; then
    pass "bash -n $rel"
  else
    fail "bash -n $rel"
  fi
done

# ----------------------------------------------- behaviour without Docker
echo '== 2. start without Docker =='
BIN="$TMP/bin"
mkdir -p "$BIN"
# Keep only the tools the scripts themselves need, so PATH cannot contain docker.
for tool in dirname cat sed awk grep tr od mktemp date sleep cp mv head tail cut uname; do
  src="$(command -v "$tool" 2>/dev/null || true)"
  [ -n "$src" ] || continue
  {
    printf '#!/bin/sh\n'
    printf 'exec "%s" "$@"\n' "$src"
  } > "$BIN/$tool"
  chmod +x "$BIN/$tool"
done

if env PATH="$BIN" "$BASH" -c 'command -v docker >/dev/null 2>&1'; then
  fail 'restricted PATH must not contain docker'
else
  pass 'restricted PATH does not contain docker'
fi

OUT="$(cd "$ROOT" && env PATH="$BIN" "$BASH" ./start.sh --quiet 2>&1)"
RC=$?
assert_eq 'missing Docker exits with 2' 2 "$RC"
assert_contains 'missing Docker prints the Docker Desktop URL' "$OUT" 'https://www.docker.com/products/docker-desktop'
assert_contains 'missing Docker prints a Chinese diagnosis' "$OUT" '未检测到 Docker'

OUT="$(cd "$ROOT" && env PATH="$BIN" "$BASH" ./start.sh --bogus --quiet 2>&1)"
RC=$?
assert_eq 'unknown flag exits with 2' 2 "$RC"
assert_contains 'unknown flag is reported clearly' "$OUT" '未知参数'

# --------------------------------------------------------- .env bootstrap
echo '== 3. .env bootstrap =='
# shellcheck source=../lib/env.sh
. "$ROOT/scripts/lib/env.sh"

ENVDIR="$TMP/env-default"
mkdir -p "$ENVDIR"
cp "$ROOT/.env.example" "$ENVDIR/.env.example"

paiban_ensure_env "$ENVDIR" '' 1
JWT1="$(paiban_env_get "$ENVDIR/.env" PAIBAN_JWT_SECRET)"
AI1="$(paiban_env_get "$ENVDIR/.env" PAIBAN_AI_ENCRYPTION_KEY)"
PG1="$(paiban_env_get "$ENVDIR/.env" POSTGRES_PASSWORD)"
ADMIN1="$(paiban_env_get "$ENVDIR/.env" PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD)"

assert_match 'PAIBAN_JWT_SECRET is 64 hex chars' "$JWT1" '^[0-9a-f]{64}$'
assert_match 'PAIBAN_AI_ENCRYPTION_KEY is 64 hex chars' "$AI1" '^[0-9a-f]{64}$'
assert_match 'POSTGRES_PASSWORD is 32 hex chars' "$PG1" '^[0-9a-f]{32}$'
assert_eq 'default admin password is admin123' 'admin123' "$ADMIN1"
assert_eq 'all replace-with placeholders are gone' '0' "$(grep -c 'replace-with' "$ENVDIR/.env")"

# A second run must be idempotent.
paiban_ensure_env "$ENVDIR" '' 1
assert_eq 'second run keeps the JWT secret' "$JWT1" "$(paiban_env_get "$ENVDIR/.env" PAIBAN_JWT_SECRET)"
assert_eq 'second run keeps the AI key' "$AI1" "$(paiban_env_get "$ENVDIR/.env" PAIBAN_AI_ENCRYPTION_KEY)"
assert_eq 'second run keeps the database password' "$PG1" "$(paiban_env_get "$ENVDIR/.env" POSTGRES_PASSWORD)"

# Existing real values must never be overwritten.
KNOWN_JWT="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
paiban_env_set "$ENVDIR/.env" PAIBAN_JWT_SECRET "$KNOWN_JWT"
paiban_ensure_env "$ENVDIR" '' 1
assert_eq 'existing real JWT value is preserved' "$KNOWN_JWT" "$(paiban_env_get "$ENVDIR/.env" PAIBAN_JWT_SECRET)"

# Explicit --admin-password must win.
ENVDIR2="$TMP/env-custom"
mkdir -p "$ENVDIR2"
cp "$ROOT/.env.example" "$ENVDIR2/.env.example"
paiban_ensure_env "$ENVDIR2" 'CustomPass1' 1
assert_eq '--admin-password overrides the bootstrap password' 'CustomPass1' \
  "$(paiban_env_get "$ENVDIR2/.env" PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD)"

# ------------------------------------------- first-run detection for output
echo '== 3b. first-run detection =='
FRESHDIR="$TMP/env-fresh"
mkdir -p "$FRESHDIR"
cp "$ROOT/.env.example" "$FRESHDIR/.env.example"
paiban_ensure_env "$FRESHDIR" '' 1
assert_eq 'a newly created .env reports PAIBAN_ENV_CREATED=1' '1' "${PAIBAN_ENV_CREATED:-unset}"
paiban_ensure_env "$FRESHDIR" '' 1
assert_eq 'an existing .env reports PAIBAN_ENV_CREATED=0' '0' "${PAIBAN_ENV_CREATED:-unset}"

if grep -q 'PAIBAN_ENV_CREATED' "$ROOT/scripts/start.sh"; then
  pass 'start.sh branches on PAIBAN_ENV_CREATED'
else
  fail 'start.sh branches on PAIBAN_ENV_CREATED'
fi
if grep -q '密码为首次启动时设置的密码' "$ROOT/scripts/start.sh"; then
  pass 'start.sh prints the non-first-run password message'
else
  fail 'start.sh prints the non-first-run password message'
fi
if grep -q '只在首次创建' "$ROOT/scripts/start.sh"; then
  pass 'start.sh warns that --admin-password only applies on first creation'
else
  fail 'start.sh warns that --admin-password only applies on first creation'
fi

if grep -q 'Created' "$ROOT/scripts/lib/env.ps1"; then
  pass 'env.ps1 returns the created flag'
else
  fail 'env.ps1 returns the created flag'
fi
if grep -q '密码为首次启动时设置的密码' "$ROOT/scripts/start.ps1"; then
  pass 'start.ps1 prints the non-first-run password message'
else
  fail 'start.ps1 prints the non-first-run password message'
fi
if grep -q '只在首次创建' "$ROOT/scripts/start.ps1"; then
  pass 'start.ps1 warns that --admin-password only applies on first creation'
else
  fail 'start.ps1 warns that --admin-password only applies on first creation'
fi

# ------------------------------------------------------------- PowerShell
echo '== 4. PowerShell scripts =='
PS_SCRIPTS=("$ROOT/scripts/start.ps1" "$ROOT/scripts/lib/env.ps1")

for file in "${PS_SCRIPTS[@]}"; do
  rel="${file#"$ROOT"/}"
  bom="$(head -c 3 "$file" 2>/dev/null | od -An -tx1 | tr -d ' \n')"
  assert_eq "$rel has a UTF-8 BOM (prevents mojibake)" 'efbbbf' "$bom"
done

if command -v powershell >/dev/null 2>&1; then
  for file in "${PS_SCRIPTS[@]}"; do
    rel="${file#"$ROOT"/}"
    win="$(cygpath -m "$file" 2>/dev/null || printf '%s' "$file")"
    ps_cmd="\$errs = \$null; [void][System.Management.Automation.Language.Parser]::ParseFile('$win', [ref]\$null, [ref]\$errs); if (\$errs -and \$errs.Count -gt 0) { \$errs | ForEach-Object { Write-Host \$_.Message }; exit 1 }"
    if powershell -NoProfile -NonInteractive -Command "$ps_cmd" >"$TMP/ps-parse.out" 2>&1; then
      pass "PowerShell parses $rel"
    else
      fail "PowerShell cannot parse $rel" "$(cat "$TMP/ps-parse.out")"
    fi
  done

  # Exercise the PowerShell .env bootstrap too, so both implementations agree.
  PSENV="$TMP/env-ps"
  mkdir -p "$PSENV"
  cp "$ROOT/.env.example" "$PSENV/.env.example"
  cat > "$TMP/env-ps-test.ps1" <<'PS1'
param([string]$Root, [string]$Lib)
. $Lib
$result = Initialize-PaibanEnv -Root $Root -AdminPassword '' -Quiet $true
if ($result.AdminPassword -ne 'admin123') {
    Write-Host "unexpected admin password: $($result.AdminPassword)"
    exit 1
}
exit 0
PS1
  if powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass \
    -File "$(cygpath -w "$TMP/env-ps-test.ps1")" \
    -Root "$(cygpath -w "$PSENV")" \
    -Lib "$(cygpath -w "$ROOT/scripts/lib/env.ps1")" >"$TMP/ps-env.out" 2>&1; then
    pass 'PowerShell .env bootstrap runs'
    assert_match 'PowerShell generates a 64-hex JWT secret' \
      "$(paiban_env_get "$PSENV/.env" PAIBAN_JWT_SECRET)" '^[0-9a-f]{64}$'
    assert_match 'PowerShell generates a 32-hex database password' \
      "$(paiban_env_get "$PSENV/.env" POSTGRES_PASSWORD)" '^[0-9a-f]{32}$'
    assert_eq 'PowerShell leaves no replace-with placeholders' '0' "$(grep -c 'replace-with' "$PSENV/.env")"
  else
    fail 'PowerShell .env bootstrap failed' "$(cat "$TMP/ps-env.out")"
  fi
else
  skip 'powershell not found, skipping PowerShell checks'
fi

# ------------------------------------------------------------ --prod mode
echo '== 5. --prod one-click mode =='

if grep -q -- '--prod' "$ROOT/scripts/start.sh" && grep -q 'docker-compose.prod.yml' "$ROOT/scripts/start.sh"; then
  pass 'start.sh documents and uses --prod / docker-compose.prod.yml'
else
  fail 'start.sh documents and uses --prod / docker-compose.prod.yml'
fi
if grep -q -- '--prod' "$ROOT/scripts/start.ps1" && grep -q 'docker-compose.prod.yml' "$ROOT/scripts/start.ps1"; then
  pass 'start.ps1 documents and uses --prod / docker-compose.prod.yml'
else
  fail 'start.ps1 documents and uses --prod / docker-compose.prod.yml'
fi
if grep -q 'https://127.0.0.1/api/health' "$ROOT/scripts/start.sh"; then
  pass 'start.sh waits for readiness over https in --prod'
else
  fail 'start.sh waits for readiness over https in --prod'
fi
if grep -q '自签证书' "$ROOT/scripts/start.sh" && grep -q '自签证书' "$ROOT/scripts/start.ps1"; then
  pass 'both start scripts warn about self-signed certificates'
else
  fail 'both start scripts warn about self-signed certificates'
fi
if grep -q 'https://localhost' "$ROOT/scripts/start.sh" && grep -q 'https://localhost' "$ROOT/scripts/start.ps1"; then
  pass 'both start scripts print the https frontend address in --prod'
else
  fail 'both start scripts print the https frontend address in --prod'
fi

PRODROOT="$TMP/prod-root"
mkdir -p "$PRODROOT"
cp -R "$ROOT/scripts" "$PRODROOT/scripts"
cp "$ROOT/start.sh" "$PRODROOT/start.sh"
cp "$ROOT/.env.example" "$PRODROOT/.env.example"
OUT="$(cd "$PRODROOT" && bash ./start.sh --prod --env-only --quiet 2>&1)"
RC=$?
assert_eq '--prod --env-only exits 0 without Docker or certificates' 0 "$RC"
if [ -f "$PRODROOT/.env" ]; then
  pass '--prod --env-only prepares .env'
else
  fail '--prod --env-only prepares .env'
fi
if [ -d "$PRODROOT/deploy/certs" ]; then
  fail '--prod --env-only does not touch deploy/certs'
else
  pass '--prod --env-only does not touch deploy/certs'
fi
assert_eq '--prod --env-only leaves no replace-with placeholders' '0' \
  "$(grep -c 'replace-with' "$PRODROOT/.env" || true)"

# ---------------------------------------------------------------- summary
printf '\n%d passed, %d failed, %d skipped\n' "$PASS" "$FAIL" "$SKIP"
if [ "$FAIL" -ne 0 ]; then
  exit 1
fi
exit 0
