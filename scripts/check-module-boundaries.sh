#!/usr/bin/env bash
# 模块边界检查：scheduling 不得直接 import timetable 或 substitution。
#
# 1) scheduling 通过 SemesterBaselineQuery 端口读取已发布基线，timetable 依赖
#    scheduling；若 scheduling 反过来 import com.paiban.timetable.*，会重新引入
#    循环依赖，端口也就失去了作用。
# 2) substitution 依赖 scheduling（SwapPlanner 使用 ConstraintChecker 等）；草稿
#    与调代共用的策略类型必须放在中立的 common.policy，scheduling import
#    com.paiban.substitution.* 就是反向依赖（契约 §3）。
#
# Usage: scripts/check-module-boundaries.sh
# Exit:  0 = 没有命中；1 = 至少一处命中或扫描失败（grep 报错）
set -euo pipefail
cd "$(dirname "$0")/.."

SRC_DIR=backend/src/main/java/com/paiban/scheduling
if [[ ! -d "$SRC_DIR" ]]; then
  echo "scheduling source not found: $SRC_DIR" >&2
  exit 1
fi

# check_forbidden <target-package> <explanation>
# 0 = 无命中；1 = 命中（已打印）；2 = grep 自身出错。
check_forbidden() {
  local target="$1"
  local explanation="$2"
  local matches
  local status
  if matches=$(grep -rn "^import com\\.paiban\\.${target}\\." "$SRC_DIR"); then
    echo "scheduling must not import ${target} (${explanation}):" >&2
    echo "$matches" >&2
    return 1
  else
    status=$?
  fi
  # grep 0 = 命中（上面已返回 1）；1 = 无命中（通过）；2+ = grep 自身出错。
  if [[ $status -ne 1 ]]; then
    echo "module boundaries check: grep failed with exit status $status while scanning $SRC_DIR for ${target}" >&2
    return 2
  fi
  return 0
}

check_forbidden timetable "use SemesterBaselineQuery instead" || exit 1
check_forbidden substitution "shared exchange policy lives in common.policy; substitution depends on scheduling" || exit 1

echo "module boundaries ok (scheduling does not import timetable or substitution)"
