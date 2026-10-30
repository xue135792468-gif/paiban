#!/usr/bin/env bash
# 生产前端日期格式检查：禁止用 toISOString() 截取日期。
#
# toISOString() 返回 UTC，北京时间凌晨 0–8 点会把它算成前一天，页面上的「今天」
# 会变成昨天。日期格式化统一走本地分量（如 substitution/types.ts 的 today()）。
# 只拦「toISOString() 后直接截日期」这一种写法，容忍链式调用里的空格。
#
# Usage: scripts/check-local-dates.sh [src-dir]
# Exit:  0 = 没有命中；1 = 至少一处命中或扫描失败（grep 报错）
set -euo pipefail
cd "$(dirname "$0")/.."

SRC_DIR="${1:-frontend/src}"
if [[ ! -d "$SRC_DIR" ]]; then
  echo "frontend src not found: $SRC_DIR" >&2
  exit 1
fi

# toISOString() 后接 .slice(0, 10) / .substring(0, 10) / .split('T')[0]。
# 扫描 .ts/.vue，排除 *.spec.ts；grep 的 file:line:text 输出直接可用。
pattern='toISOString\(\)[[:space:]]*\.[[:space:]]*(slice[[:space:]]*\([[:space:]]*0[[:space:]]*,[[:space:]]*10[[:space:]]*\)|substring[[:space:]]*\([[:space:]]*0[[:space:]]*,[[:space:]]*10[[:space:]]*\)|split[[:space:]]*\([[:space:]]*['"'"'"']T['"'"'"'][[:space:]]*\)[[:space:]]*\[[[:space:]]*0[[:space:]]*\])'

status=0
if matches=$(grep -rnE --include='*.ts' --include='*.vue' --exclude='*.spec.ts' "$pattern" "$SRC_DIR"); then
  echo "$matches" >&2
  echo "用 toISOString() 截日期会按 UTC 计算，北京时间 0–8 点会得到昨天；改用本地日期分量（如 today()）。" >&2
  exit 1
else
  status=$?
fi

# grep 0 = 命中（上面已失败退出）；1 = 无命中（通过）；2+ = grep 自身出错。
if [[ $status -ne 1 ]]; then
  echo "local dates check: grep failed with exit status $status while scanning $SRC_DIR" >&2
  exit 1
fi

echo "local dates ok (no toISOString() date slicing in production frontend)"
