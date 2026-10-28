#!/usr/bin/env bash
# Regression tests for scripts/check-ticket-status.sh.
#
# Usage: bash scripts/tests/test-check-ticket-status.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CHECK="$ROOT_DIR/scripts/check-ticket-status.sh"

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

mkdir -p "$TMP_DIR/.scratch/stale/issues" "$TMP_DIR/.scratch/pending/issues" "$TMP_DIR/.scratch/done/issues" "$TMP_DIR/.scratch/empty/issues"
printf '# stale\n\n**Status:** ready-for-agent\n\n- [x] one\n- [x] two\n' \
  > "$TMP_DIR/.scratch/stale/issues/01-stale.md"
printf '# pending\n\n**Status:** ready-for-agent\n\n- [x] one\n- [ ] two\n' \
  > "$TMP_DIR/.scratch/pending/issues/01-pending.md"
printf '# done\n\n**Status:** done\n\n- [x] one\n- [x] two\n' \
  > "$TMP_DIR/.scratch/done/issues/01-done.md"
printf '# empty\n\n**Status:** ready-for-agent\n\nNo checklist yet.\n' \
  > "$TMP_DIR/.scratch/empty/issues/01-empty.md"

run_check "$TMP_DIR"
if [[ "$STATUS" -eq 1 ]]; then pass "all-checked ready-for-agent fails"; else fail "all-checked ready-for-agent fails"; fi
if grep -qF "01-stale.md" "$OUT"; then pass "failure names the stale ticket"; else fail "failure names the stale ticket"; fi

rm -rf "$TMP_DIR/.scratch/stale"
run_check "$TMP_DIR"
if [[ "$STATUS" -eq 0 ]]; then pass "pending and done tickets pass"; else fail "pending and done tickets pass"; fi

rm -rf "$TMP_DIR/.scratch/empty"
run_check "$TMP_DIR"
if [[ "$STATUS" -eq 0 ]]; then pass "no checklist passes"; else fail "no checklist passes"; fi

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ $FAIL -eq 0 ]]
