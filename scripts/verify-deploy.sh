#!/usr/bin/env bash
# Run every deployment-script test suite (scripts/tests/test-*.sh).
#
# Prints the suite name before running it and stops at the first failure, so CI
# output points straight at the broken suite. No Docker is required: the suites
# assert static configuration and stub the Docker CLI.
#
# Usage: bash scripts/verify-deploy.sh
# Exit:  0 = every suite passed, 1 = at least one suite failed.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

found=0
for test_script in "$ROOT"/scripts/tests/test-*.sh; do
  [ -f "$test_script" ] || continue
  found=1
  name="$(basename "$test_script")"
  printf '\n===== %s =====\n' "$name"
  if ! bash "$test_script"; then
    printf '\n[失败] %s\n' "$name" >&2
    exit 1
  fi
done

if [ "$found" -eq 0 ]; then
  printf '[失败] 没有找到 scripts/tests/test-*.sh\n' >&2
  exit 1
fi

printf '\n[完成] 全部部署脚本测试通过。\n'
exit 0
