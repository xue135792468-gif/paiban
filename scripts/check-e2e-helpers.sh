#!/usr/bin/env bash
# E2E 公共 helper 判重。
#
# CODING_STANDARDS 要求 E2E 公共交互只在 frontend/e2e/helpers.ts 实现一次；
# 各 spec 若再次定义同名函数，helper 一改就漂移，且只有全量跑才暴露差异。
# 只检查 helpers.ts 导出的函数名；spec 自己的种子函数、toast 等非导出名不受影响。
#
# Usage: scripts/check-e2e-helpers.sh [e2e-dir]
# Exit:  0 = spec 没有重复定义 helpers 的导出函数；1 = 至少一处重复
set -euo pipefail
cd "$(dirname "$0")/.."

E2E_DIR="${1:-frontend/e2e}"
HELPERS="$E2E_DIR/helpers.ts"
if [[ ! -f "$HELPERS" ]]; then
  echo "e2e helpers not found: $HELPERS" >&2
  exit 1
fi

names=$(sed -nE 's/^export (async )?function ([A-Za-z0-9_]+).*/\2/p' "$HELPERS")
if [[ -z "$names" ]]; then
  echo "e2e helpers check: no exported functions found in $HELPERS" >&2
  exit 1
fi

failures=0
while IFS= read -r name; do
  [[ -z "$name" ]] && continue
  while IFS= read -r spec; do
    while IFS=: read -r line _; do
      [[ -z "$line" ]] && continue
      printf '%s:%s: local %s duplicates helpers.ts\n' "$spec" "$line" "$name" >&2
      failures=$((failures + 1))
    done < <(grep -nE "^(async )?function ${name}([^A-Za-z0-9_]|$)" "$spec" || true)
  done < <(find "$E2E_DIR" -maxdepth 1 -type f -name '*.spec.ts' | sort)
done <<< "$names"

if [[ $failures -gt 0 ]]; then
  echo "e2e helpers check failed: $failures duplicate definition(s); import from helpers.ts instead" >&2
  exit 1
fi

echo "e2e helpers ok (no spec redefines an exported helper)"
