#!/usr/bin/env bash
# E2E 公共 helper 判重。
#
# CODING_STANDARDS 要求 E2E 公共交互只在 frontend/e2e/helpers.ts 实现一次；
# 各 spec 若再次定义同名函数，helper 一改就漂移，且只有全量跑才暴露差异。
# 只检查 helpers.ts 导出的函数名；spec 自己的种子函数、toast 等非导出名不受影响。
#
# 白名单：scripts/e2e-helper-allowlist.txt 用于登记经评审确认、暂时无法上收的
# 个别重复定义；文件可选，不存在视为空。每行 `文件名|函数名`（文件名如
# `legacy-admin.spec.ts`，也可写相对仓库根的路径），`#` 开头为注释。命中白名单
# 的定义打印 `allowed: ...`，不计为失败；白名单只应缩小，不应随新增重复而扩大。
#
# Usage: scripts/check-e2e-helpers.sh [e2e-dir]
# Exit:  0 = spec 没有重复定义 helpers 的导出函数（白名单内除外）；1 = 至少一处未放行的重复
set -euo pipefail
cd "$(dirname "$0")/.."

E2E_DIR="${1:-frontend/e2e}"
HELPERS="$E2E_DIR/helpers.ts"
ALLOWLIST="scripts/e2e-helper-allowlist.txt"
if [[ ! -f "$HELPERS" ]]; then
  echo "e2e helpers not found: $HELPERS" >&2
  exit 1
fi

names=$(sed -nE 's/^[[:space:]]*export (async )?function ([A-Za-z0-9_]+).*/\2/p' "$HELPERS")
if [[ -z "$names" ]]; then
  echo "e2e helpers check: no exported functions found in $HELPERS" >&2
  exit 1
fi

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

is_allowed() {
  local spec="$1" name="$2" file fn
  [[ -f "$ALLOWLIST" ]] || return 1
  while IFS='|' read -r file fn; do
    file=$(trim "$file")
    fn=$(trim "$fn")
    [[ -z "$file" || "$file" == \#* ]] && continue
    [[ "$fn" == "$name" ]] || continue
    if [[ "$file" == "$(basename "$spec")" || "$file" == "$spec" ]]; then
      return 0
    fi
  done < "$ALLOWLIST"
  return 1
}

failures=0
allowed=0
while IFS= read -r name; do
  [[ -z "$name" ]] && continue
  while IFS= read -r spec; do
    while IFS=: read -r line _; do
      [[ -z "$line" ]] && continue
      if is_allowed "$spec" "$name"; then
        printf 'allowed: %s:%s: local %s duplicates helpers.ts (see %s)\n' \
          "$spec" "$line" "$name" "$ALLOWLIST" >&2
        allowed=$((allowed + 1))
      else
        printf '%s:%s: local %s duplicates helpers.ts\n' "$spec" "$line" "$name" >&2
        failures=$((failures + 1))
      fi
    done < <(grep -nE "^[[:space:]]*(export )?(async )?function ${name}([^A-Za-z0-9_]|$)" "$spec" || true)
  done < <(find "$E2E_DIR" -maxdepth 1 -type f -name '*.spec.ts' | sort)
done <<< "$names"

if [[ $failures -gt 0 ]]; then
  echo "e2e helpers check failed: $failures duplicate definition(s); import from helpers.ts instead" >&2
  exit 1
fi

if [[ $allowed -gt 0 ]]; then
  echo "e2e helpers ok ($allowed allowlisted duplicate definition(s); no other spec redefines an exported helper)"
else
  echo "e2e helpers ok (no spec redefines an exported helper)"
fi
