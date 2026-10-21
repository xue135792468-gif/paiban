#!/usr/bin/env bash
# Tests for backend/secret-bootstrap.sh and the container wiring that uses it
# (deploy-friendly ticket 03: self-healing init).
#
# Docker is not required: every assertion runs the POSIX shell script against a
# temporary data directory, plus static checks on docker-compose.yml,
# backend/entrypoint.sh and backend/Dockerfile.
#
# Usage: bash scripts/tests/test-secret-bootstrap.sh
# Exit:  0 = every assertion passed, 1 = at least one failed.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/backend/secret-bootstrap.sh"
ENTRYPOINT="$ROOT/backend/entrypoint.sh"
DOCKERFILE="$ROOT/backend/Dockerfile"
COMPOSE="$ROOT/docker-compose.yml"

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

assert_match() { # description value regex
  if printf '%s' "$2" | grep -Eq "$3"; then
    pass "$1"
  else
    fail "$1" "value [$2] does not match /$3/"
  fi
}

assert_contains() { # description text fragment
  case "$2" in
    *"$3"*) pass "$1" ;;
    *) fail "$1" "output does not contain [$3]: $2" ;;
  esac
}

assert_not_contains() { # description text fragment
  case "$2" in
    *"$3"*) fail "$1" "output must not contain [$3]: $2" ;;
    *) pass "$1" ;;
  esac
}

# Last non-empty value for KEY in a KEY=VALUE file ('' when absent).
secret_get() { # file key
  [ -f "$1" ] || {
    printf ''
    return 0
  }
  awk -F= -v k="$2" '
    $1 == k {
      sub(/^[^=]*=/, "")
      sub(/\r$/, "")
      if ($0 != "") { value = $0 }
    }
    END { printf "%s", value }
  ' "$1"
}

run_bootstrap() { # data_dir [extra env assignments...]
  local data_dir="$1"
  shift
  env -u PAIBAN_JWT_SECRET -u PAIBAN_AI_ENCRYPTION_KEY \
    PAIBAN_DATA_DIR="$data_dir" "$@" sh "$SCRIPT" 2>&1
}

TMP="$(mktemp -d)"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

# ------------------------------------------------------------ 0. static wiring
echo '== 0. static wiring =='

if [ ! -f "$SCRIPT" ]; then
  fail "backend/secret-bootstrap.sh exists"
else
  pass "backend/secret-bootstrap.sh exists"

  if sh -n "$SCRIPT"; then
    pass "sh -n backend/secret-bootstrap.sh (POSIX syntax)"
  else
    fail "sh -n backend/secret-bootstrap.sh (POSIX syntax)"
  fi

  if [ -x "$SCRIPT" ]; then
    pass "backend/secret-bootstrap.sh is executable"
  else
    fail "backend/secret-bootstrap.sh is executable"
  fi
fi

if [ -f "$ENTRYPOINT" ]; then
  bootstrap_line="$(grep -n 'secret-bootstrap\.sh' "$ENTRYPOINT" | head -n 1 | cut -d: -f1)"
  gosu_line="$(grep -n 'exec gosu' "$ENTRYPOINT" | head -n 1 | cut -d: -f1)"
  if [ -n "$bootstrap_line" ] && [ -n "$gosu_line" ] && [ "$bootstrap_line" -lt "$gosu_line" ]; then
    pass "entrypoint sources secret-bootstrap.sh before exec gosu"
  else
    fail "entrypoint sources secret-bootstrap.sh before exec gosu" \
      "bootstrap line [${bootstrap_line:-missing}], gosu line [${gosu_line:-missing}]"
  fi
  assert_match "entrypoint creates /app/data before bootstrapping" \
    "$(grep -c 'mkdir -p .*/app/data' "$ENTRYPOINT")" '^[1-9]'
else
  fail "backend/entrypoint.sh exists"
fi

if [ -f "$DOCKERFILE" ]; then
  assert_contains "Dockerfile copies secret-bootstrap.sh into the image" \
    "$(tr -d '\r' < "$DOCKERFILE")" 'secret-bootstrap.sh'
else
  fail "backend/Dockerfile exists"
fi

if [ ! -f "$COMPOSE" ]; then
  fail "docker-compose.yml exists"
elif ! python - "$COMPOSE" <<'PY'
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - environment guard
    print("FAIL  PyYAML is installed (pip install pyyaml)")
    sys.exit(1)

with open(sys.argv[1], encoding="utf-8") as fh:
    text = fh.read()
compose = yaml.safe_load(text) or {}
backend = (compose.get("services") or {}).get("backend") or {}
env = backend.get("environment") or {}
volumes = backend.get("volumes") or []

failures = 0


def check(ok, label, detail=""):
    global failures
    if ok:
        print(f"PASS  {label}")
    else:
        failures += 1
        print(f"FAIL  {label}" + (f" ({detail})" if detail else ""))


strict = re.findall(r"\$\{[A-Za-z_][A-Za-z0-9_]*:\?[^}]*\}", text)
check(not strict, "compose has no ${VAR:?err} fail-fast placeholders", ", ".join(strict))

check(
    env.get("PAIBAN_JWT_SECRET") == "${PAIBAN_JWT_SECRET:-}",
    "backend PAIBAN_JWT_SECRET defaults to empty",
    repr(env.get("PAIBAN_JWT_SECRET")),
)
check(
    env.get("PAIBAN_AI_ENCRYPTION_KEY") == "${PAIBAN_AI_ENCRYPTION_KEY:-}",
    "backend PAIBAN_AI_ENCRYPTION_KEY defaults to empty",
    repr(env.get("PAIBAN_AI_ENCRYPTION_KEY")),
)
check(
    env.get("PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD")
    == "${PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD:-}",
    "backend admin password env stays optional",
    repr(env.get("PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD")),
)
check("./data/app:/app/data" in volumes, "backend mounts ./data/app at /app/data", str(volumes))
check("./data/uploads:/app/uploads" in volumes, "backend keeps the uploads mount", str(volumes))

sys.exit(min(failures, 125))
PY
then
  fail "docker-compose.yml self-healing wiring"
fi

# ------------------------------------------------- 1. first start generates
echo '== 1. first start generates secrets =='
DATA1="$TMP/data1"
OUT1="$(run_bootstrap "$DATA1")"
RC1=$?
assert_eq 'first start exits 0' 0 "$RC1"
assert_eq 'secrets.env is created' 'yes' "$([ -f "$DATA1/secrets.env" ] && echo yes || echo no)"

JWT1="$(secret_get "$DATA1/secrets.env" PAIBAN_JWT_SECRET)"
AI1="$(secret_get "$DATA1/secrets.env" PAIBAN_AI_ENCRYPTION_KEY)"
assert_match 'generated JWT secret is 64 hex chars' "$JWT1" '^[0-9a-f]{64}$'
assert_match 'generated AI key is 64 hex chars' "$AI1" '^[0-9a-f]{64}$'
if [ -n "$JWT1" ] && [ "$JWT1" != "$AI1" ]; then
  pass 'JWT secret and AI key are different'
else
  fail 'JWT secret and AI key are different' "jwt=[$JWT1] ai=[$AI1]"
fi

JWT_PREFIX="$(printf '%s' "$JWT1" | cut -c1-4)"
assert_contains 'first start logs the generated JWT secret' "$OUT1" '已生成 JWT Secret'
assert_contains 'first start names the secrets file' "$OUT1" "$DATA1/secrets.env"
assert_contains 'first start masks the JWT prefix' "$OUT1" "${JWT_PREFIX}****"
assert_not_contains 'first start never prints the full JWT secret' "$OUT1" "$JWT1"
assert_not_contains 'first start never prints the full AI key' "$OUT1" "$AI1"

if [ "${OS:-}" = "Windows_NT" ] || printf '%s' "$(uname -s)" | grep -Eq '^(MINGW|MSYS|CYGWIN)'; then
  skip 'secrets.env is mode 600 (not meaningful on Windows filesystems)'
else
  assert_eq 'secrets.env is mode 600' '600' "$(stat -c '%a' "$DATA1/secrets.env" 2>/dev/null || echo unknown)"
fi

# --------------------------------------------------------- 2. idempotency
echo '== 2. restart reuses the same secrets =='
OUT2="$(run_bootstrap "$DATA1")"
RC2=$?
assert_eq 'second start exits 0' 0 "$RC2"
assert_eq 'JWT secret is unchanged across restarts' "$JWT1" "$(secret_get "$DATA1/secrets.env" PAIBAN_JWT_SECRET)"
assert_eq 'AI key is unchanged across restarts' "$AI1" "$(secret_get "$DATA1/secrets.env" PAIBAN_AI_ENCRYPTION_KEY)"
assert_not_contains 'second start does not claim to have generated a JWT secret' "$OUT2" '已生成 JWT Secret'
assert_contains 'second start reports reuse' "$OUT2" '复用'

# --------------------------------------- 3. operator-provided value wins
echo '== 3. operator-provided secret is preserved =='
DATA2="$TMP/data2"
CUSTOM="operator-provided-jwt-secret-0123456789abcdef"
OUT3="$(run_bootstrap "$DATA2" PAIBAN_JWT_SECRET="$CUSTOM")"
RC3=$?
assert_eq 'start with PAIBAN_JWT_SECRET exits 0' 0 "$RC3"
assert_eq 'operator secret is not written to secrets.env' '' \
  "$(secret_get "$DATA2/secrets.env" PAIBAN_JWT_SECRET)"
assert_not_contains 'operator secret is never written to the file' \
  "$(cat "$DATA2/secrets.env" 2>/dev/null)" "$CUSTOM"
assert_match 'the missing AI key is still generated' \
  "$(secret_get "$DATA2/secrets.env" PAIBAN_AI_ENCRYPTION_KEY)" '^[0-9a-f]{64}$'
assert_contains 'operator secret is masked in the log' "$OUT3" "${CUSTOM:0:4}****"
assert_not_contains 'operator secret is never printed in full' "$OUT3" "$CUSTOM"

# ----------------------------- 4. explicitly empty env (compose ${VAR:-})
echo '== 4. empty env var behaves like unset =='
DATA3="$TMP/data3"
OUT4="$(run_bootstrap "$DATA3" PAIBAN_JWT_SECRET= PAIBAN_AI_ENCRYPTION_KEY=)"
RC4=$?
assert_eq 'empty env vars still exit 0' 0 "$RC4"
assert_match 'empty env still generates a JWT secret' \
  "$(secret_get "$DATA3/secrets.env" PAIBAN_JWT_SECRET)" '^[0-9a-f]{64}$'
assert_match 'empty env still generates an AI key' \
  "$(secret_get "$DATA3/secrets.env" PAIBAN_AI_ENCRYPTION_KEY)" '^[0-9a-f]{64}$'

# ------------------------- 5. partially populated secrets file is healed
echo '== 5. partially populated secrets file is healed =='
DATA4="$TMP/data4"
mkdir -p "$DATA4"
EXISTING_AI="abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
printf 'PAIBAN_AI_ENCRYPTION_KEY=%s\n' "$EXISTING_AI" > "$DATA4/secrets.env"
run_bootstrap "$DATA4" > /dev/null
assert_eq 'existing AI key in the file is kept' "$EXISTING_AI" \
  "$(secret_get "$DATA4/secrets.env" PAIBAN_AI_ENCRYPTION_KEY)"
assert_match 'missing JWT secret is appended' \
  "$(secret_get "$DATA4/secrets.env" PAIBAN_JWT_SECRET)" '^[0-9a-f]{64}$'

# ---------------------------------- 6. sourcing exports for the java process
echo '== 6. sourcing exports the values and keeps errexit =='
DATA5="$TMP/data5"
SOURCED="$(env -u PAIBAN_JWT_SECRET -u PAIBAN_AI_ENCRYPTION_KEY PAIBAN_DATA_DIR="$DATA5" \
  sh -c 'set -e; . "$1"; printf "jwt=%s\nai=%s\n" "$PAIBAN_JWT_SECRET" "$PAIBAN_AI_ENCRYPTION_KEY"; case $- in *e*) echo "errexit=on" ;; *) echo "errexit=off" ;; esac' \
  _ "$SCRIPT" 2>&1)"
assert_contains 'sourced script exports PAIBAN_JWT_SECRET' "$SOURCED" \
  "jwt=$(secret_get "$DATA5/secrets.env" PAIBAN_JWT_SECRET)"
assert_contains 'sourced script exports PAIBAN_AI_ENCRYPTION_KEY' "$SOURCED" \
  "ai=$(secret_get "$DATA5/secrets.env" PAIBAN_AI_ENCRYPTION_KEY)"
assert_contains 'sourced script restores the caller errexit setting' "$SOURCED" 'errexit=on'

# ---------------------------- 7. placeholder env values are never used
echo '== 7. placeholder env values are treated as missing =='
DATA6="$TMP/data6"
PH_JWT='replace-with-at-least-32-random-characters'
PH_AI='change-me-0123456789abcdef0123456789'
OUT7="$(run_bootstrap "$DATA6" PAIBAN_JWT_SECRET="$PH_JWT" PAIBAN_AI_ENCRYPTION_KEY="$PH_AI")"
RC7=$?
assert_eq 'placeholder env values still exit 0' 0 "$RC7"
JWT7="$(secret_get "$DATA6/secrets.env" PAIBAN_JWT_SECRET)"
AI7="$(secret_get "$DATA6/secrets.env" PAIBAN_AI_ENCRYPTION_KEY)"
assert_match 'placeholder JWT env is replaced by a generated secret' "$JWT7" '^[0-9a-f]{64}$'
assert_match 'placeholder AI env is replaced by a generated key' "$AI7" '^[0-9a-f]{64}$'
if [ "$JWT7" != "$PH_JWT" ] && [ "$AI7" != "$PH_AI" ]; then
  pass 'placeholder values are not reused as secrets'
else
  fail 'placeholder values are not reused as secrets' "jwt=[$JWT7] ai=[$AI7]"
fi
assert_contains 'ignored placeholder names the JWT key' "$OUT7" 'PAIBAN_JWT_SECRET'
assert_contains 'ignored placeholder names the AI key' "$OUT7" 'PAIBAN_AI_ENCRYPTION_KEY'
assert_contains 'ignored placeholder is logged as a placeholder' "$OUT7" '占位'
assert_not_contains 'placeholder JWT value is never echoed' "$OUT7" "$PH_JWT"
assert_not_contains 'placeholder AI value is never echoed' "$OUT7" "$PH_AI"

# Every documented placeholder prefix (case-insensitive) must be ignored.
PH_INDEX=0
for placeholder in \
  'replace-with-x' \
  'replace_with_x' \
  'change-me-x' \
  'changeme-x' \
  'your-secret' \
  'your_secret' \
  'TODO-set-this' \
  'REPLACE-WITH-X'; do
  PH_INDEX=$((PH_INDEX + 1))
  PH_DIR="$TMP/ph-$PH_INDEX"
  run_bootstrap "$PH_DIR" PAIBAN_JWT_SECRET="$placeholder" > /dev/null 2>&1
  assert_match "placeholder prefix [$placeholder] is ignored" \
    "$(secret_get "$PH_DIR/secrets.env" PAIBAN_JWT_SECRET)" '^[0-9a-f]{64}$'
done

# ------------------------- 8. placeholder in secrets.env is healed
echo '== 8. placeholder value in secrets.env is regenerated =='
DATA7="$TMP/data7"
mkdir -p "$DATA7"
printf 'PAIBAN_JWT_SECRET=%s\n' "$PH_JWT" > "$DATA7/secrets.env"
OUT8="$(run_bootstrap "$DATA7")"
RC8=$?
assert_eq 'placeholder in secrets.env still exits 0' 0 "$RC8"
assert_match 'placeholder in secrets.env is replaced' \
  "$(secret_get "$DATA7/secrets.env" PAIBAN_JWT_SECRET)" '^[0-9a-f]{64}$'
assert_contains 'placeholder in secrets.env is reported' "$OUT8" '占位'
assert_not_contains 'placeholder from secrets.env is never echoed' "$OUT8" "$PH_JWT"

# ------------------- 9. the admin password is not this script's job
echo '== 9. admin password is left to the Java bootstrap =='
DATA8="$TMP/data8"
PH_ADMIN='replace-with-a-strong-initial-admin-password'
SOURCED_ADMIN="$(env -u PAIBAN_JWT_SECRET -u PAIBAN_AI_ENCRYPTION_KEY \
  PAIBAN_DATA_DIR="$DATA8" PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD="$PH_ADMIN" \
  sh -c '. "$1"; printf "admin=%s\n" "$PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD"' \
  _ "$SCRIPT" 2>&1)"
assert_contains 'admin password env is left untouched by secret-bootstrap.sh' \
  "$SOURCED_ADMIN" "admin=$PH_ADMIN"
assert_eq 'admin password is never written to secrets.env' '' \
  "$(secret_get "$DATA8/secrets.env" PAIBAN_SECURITY_BOOTSTRAP_ADMIN_PASSWORD)"
assert_not_contains 'admin password never appears in secrets.env' \
  "$(cat "$DATA8/secrets.env" 2>/dev/null)" "$PH_ADMIN"

# ---------------------------------------------------------------- summary
printf '\n%d passed, %d failed, %d skipped\n' "$PASS" "$FAIL" "$SKIP"
if [ "$FAIL" -ne 0 ]; then
  exit 1
fi
exit 0
