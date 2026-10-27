#!/usr/bin/env bash
# Regression tests for scripts/check-migrations.sh.
#
# Usage: bash scripts/tests/test-check-migrations.sh
# Exit:  0 = all assertions pass
#        1 = at least one assertion failed
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CHECK="$ROOT_DIR/scripts/check-migrations.sh"

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); printf 'ok   - %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf 'FAIL - %s\n' "$1"; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
OUT="$TMP_DIR/out.txt"

new_case() {
  local name="$1"
  CASE_DIR="$TMP_DIR/$name"
  mkdir -p "$CASE_DIR"
}

run_check() {
  if bash "$CHECK" "$CASE_DIR" >"$OUT" 2>&1; then
    STATUS=0
  else
    STATUS=$?
  fi
}

expect_status() {
  local expected="$1" label="$2"
  if [[ "$STATUS" -eq "$expected" ]]; then
    pass "$label exit $expected"
  else
    fail "$label exit $expected (got $STATUS)"
    sed 's/^/       /' "$OUT"
  fi
}

expect_output() {
  local needle="$1" label="$2"
  if grep -qF "$needle" "$OUT"; then
    pass "$label"
  else
    fail "$label (missing: $needle)"
    sed 's/^/       /' "$OUT"
  fi
}

new_case valid-with-gap
touch "$CASE_DIR/V1__baseline.sql" "$CASE_DIR/V3__next.sql"
run_check
expect_status 0 "gap is allowed"
expect_output "2 unique versions" "valid directory reports the count"

new_case duplicate
touch "$CASE_DIR/V1__first.sql" "$CASE_DIR/V1__second.sql"
run_check
expect_status 1 "duplicate version fails"
expect_output "duplicate migration version V1" "duplicate message names the version"

new_case duplicate-leading-zero
touch "$CASE_DIR/V1__first.sql" "$CASE_DIR/V01__second.sql"
run_check
expect_status 1 "leading-zero duplicate fails"
expect_output "duplicate migration version V1" "leading zeros are normalised"

new_case invalid-name
touch "$CASE_DIR/V1_bad.sql"
run_check
expect_status 1 "malformed filename fails"
expect_output "invalid migration filename" "malformed filename is reported"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ $FAIL -eq 0 ]]
