#!/usr/bin/env bash
# 模块边界检查：scheduling 不得直接 import timetable。
#
# scheduling 通过 SemesterBaselineQuery 端口读取已发布基线，timetable 依赖
# scheduling；若 scheduling 反过来 import com.paiban.timetable.*，会重新引入
# 循环依赖，端口也就失去了作用。
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

status=0
if matches=$(grep -rn '^import com\.paiban\.timetable\.' "$SRC_DIR"); then
  echo "scheduling must use SemesterBaselineQuery, not timetable imports:" >&2
  echo "$matches" >&2
  exit 1
else
  status=$?
fi

# grep 0 = 命中（上面已失败退出）；1 = 无命中（通过）；2+ = grep 自身出错。
if [[ $status -ne 1 ]]; then
  echo "module boundaries check: grep failed with exit status $status while scanning $SRC_DIR" >&2
  exit 1
fi

echo "module boundaries ok (scheduling does not import timetable)"
