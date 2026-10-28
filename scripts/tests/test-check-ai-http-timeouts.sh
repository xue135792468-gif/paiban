#!/usr/bin/env bash
# Regression tests for scripts/check-ai-http-timeouts.sh.
#
# Usage: bash scripts/tests/test-check-ai-http-timeouts.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CHECK="$ROOT_DIR/scripts/check-ai-http-timeouts.sh"

PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); printf 'ok   - %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf 'FAIL - %s\n' "$1"; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
OUT="$TMP_DIR/out.txt"

run_check() {
  if bash "$CHECK" "$1" >"$OUT" 2>&1; then
    STATUS=0
  else
    STATUS=$?
  fi
}

mkdir -p "$TMP_DIR/clean" "$TMP_DIR/direct"
printf 'final class Good {}\n' > "$TMP_DIR/clean/GoodStrategy.java"
printf 'class Bad { void call() { httpClient.send(request, handler); } }\n' \
  > "$TMP_DIR/direct/BadStrategy.java"

run_check "$TMP_DIR/clean"
if [[ "$STATUS" -eq 0 ]]; then pass "clean strategy dir passes"; else fail "clean strategy dir passes"; fi

run_check "$TMP_DIR/direct"
if [[ "$STATUS" -eq 1 ]]; then pass "direct send fails"; else fail "direct send fails"; fi
if grep -qF "direct HttpClient.send" "$OUT"; then pass "failure names the file"; else fail "failure names the file"; fi

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ $FAIL -eq 0 ]]
