#!/usr/bin/env bash
# Regression tests for the CI wiring of scripts/check-module-boundaries.sh.
#
# 该守卫原本只挂在 .githooks/pre-commit 上，`git commit --no-verify` 即可绕过；
# 这个自测确保 CI 也真的调用它，防止以后改 workflow 时被顺手删掉。
#
# Usage: bash scripts/tests/test-check-module-boundaries-ci.sh
# Exit:  0 = CI calls the guard
#        1 = CI does not call the guard
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CI_FILE="$ROOT_DIR/.github/workflows/ci.yml"

if [[ ! -f "$CI_FILE" ]]; then
  printf 'ci module-boundaries guard FAIL: %s not found\n' "$CI_FILE" >&2
  exit 1
fi

if grep -q 'check-module-boundaries.sh' "$CI_FILE"; then
  printf 'ci module-boundaries guard ok\n'
  exit 0
fi

printf 'ci module-boundaries guard FAIL: %s does not call scripts/check-module-boundaries.sh\n' \
  "${CI_FILE#"$ROOT_DIR/"}" >&2
printf 'add a step like: - name: Check module boundaries / run: bash scripts/check-module-boundaries.sh\n' >&2
exit 1
