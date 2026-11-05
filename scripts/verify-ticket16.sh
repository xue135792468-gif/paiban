#!/usr/bin/env bash
# 票 16 全量验收的复跑脚本：只做只读检查并调用现有定向测试。
#
# 明确不做的事：
#   - 不跑 Playwright 全量（那是 scripts/verify.sh --full 的职责）
#   - 不连接生产环境，不写生产数据
#   - 不跑 Docker（真实 PostgreSQL 由 CI 的 -Dtestcontainers=true 覆盖）
#   - 真实工作簿（D 盘物理文件）用例默认跳过，需要 PAIBAN_REAL_WORKBOOK 显式指定
#
# 用法：
#   bash scripts/verify-ticket16.sh
#   PAIBAN_REAL_WORKBOOK="D:/360MoveData/Users/liu/Desktop/自明排课系统-交互课表.xlsx" \
#     bash scripts/verify-ticket16.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ -f .tools/env.sh ]]; then
  # shellcheck disable=SC1091
  source .tools/env.sh
fi

MVN_EXTRA=()
REAL_WORKBOOK_RAN=0

if [[ -n "${PAIBAN_REAL_WORKBOOK:-}" ]]; then
  if [[ ! -f "$PAIBAN_REAL_WORKBOOK" ]]; then
    echo "==> 错误：PAIBAN_REAL_WORKBOOK 指向的文件不存在：$PAIBAN_REAL_WORKBOOK" >&2
    exit 1
  fi
  echo "==> 真实工作簿启用：$PAIBAN_REAL_WORKBOOK"
  echo "    文件大小：$(stat -c %s "$PAIBAN_REAL_WORKBOOK") 字节"
  MVN_EXTRA+=("-Dpaiban.realWorkbook=$PAIBAN_REAL_WORKBOOK")
  REAL_WORKBOOK_RAN=1
else
  echo "==> 未指定真实工作簿，跳过 D 盘物理文件用例（合成 560 槽 fixture 仍会跑）"
fi

echo
echo "==> 1/4 真实工作簿数字"
(cd backend && ./mvnw -B -DforkCount=0 -Dtest=ManualImportIntegrationTest \
  ${MVN_EXTRA[@]+"${MVN_EXTRA[@]}"} test)

echo
echo "==> 2/4 V55 旧数据迁移保全"
(cd backend && ./mvnw -B -DforkCount=0 -Dtest=TrimMigrationDataPreservationTest test)

echo
echo "==> 3/4 全链主链验收"
(cd backend && ./mvnw -B -DforkCount=0 -Dtest=FullChainAcceptanceTest test)

echo
echo "==> 4/4 按年级排课不移动其他年级"
(cd backend && ./mvnw -B -DforkCount=0 -Dtest=ScheduleByGradeIntegrationTest test)

echo
if [[ "$REAL_WORKBOOK_RAN" -eq 1 ]]; then
  echo "==> ticket16: ok（含 D 盘物理工作簿用例）"
else
  echo "==> 注意：本次未跑 D 盘物理工作簿用例（第 1 项需显式传 PAIBAN_REAL_WORKBOOK 复跑）；本脚本只确认合成 560 槽 fixture 与其余定向测试通过"
  echo "==> ticket16: partial ok（合成 fixture + 3 项定向测试；物理工作簿用例跳过）"
fi
