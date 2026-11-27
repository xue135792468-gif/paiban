# 票02 双轴复查整改交付（2026-11-11）

分支 `codex/dingtalk-t02-review`，基于 `4eb1886`，收尾合并集成 `639fb72`。单一 implementer，有界整改，未开 03/04 业务。

## 做了什么

- 旧动作码只读别名：`DISABLE_USER/ENABLE_USER/IMPORT` 在 ACCOUNT 分类下按规范码读取与筛选，库中原值不改；未知历史码由页面中文兜底。
- 历史记录如实展示：DTO 暴露 `detail/successRows/failedRows`，页面区分历史记录、整批摘要、逐账号记录；不解析 detail 猜目标账号，不用当前账号伪造旧快照，旧原文只作文本渲染。
- 新增 V60→V61 旧日志保全用例；未改 V1–V60，未新增迁移版本。
- 动作目录 10 类：新增 `ACCOUNT_ROLE_CHANGE`（改身份），`ACCOUNT_UPDATE` 改名「修改资料」；仅角色真变写 ROLE_CHANGE。
- 复合 PUT 拆成身份/资料与启停两条连续不重叠事件；守卫、锚点锁与锁内重读保持。
- 重置密码显示「密码已重置（内容不记录）」。
- 两个筛选下拉加稳定 testid，e2e 删除本地 `clickOption` 并复用公共 `pickOption`。
- 修正 4eb1886 起即红的 `ExtendedLifecycleIntegrationTest` 两条旧断言（改查账号审计接口）。

## 红 → 绿

| 行为 | 首次失败 | 通过 |
|---|---|---|
| 旧码筛选命中 | `?action=ACCOUNT_DISABLE` total 期望 1、实得 0 | 1/1 |
| 改身份单列 | `ACCOUNT_ROLE_CHANGE` total 0 | 1/1 |
| 复合 PUT 两条事件 | 最新事件是 `ACCOUNT_UPDATE` 而非 `ACCOUNT_DISABLE` | 1/1 |
| 页面历史/摘要/重置文案 | vitest 3 条失败（无文案、缺导出） | 11/11 |
| 既有回归 | 基线 ExtendedLifecycle 2/2 红 | 2/2 绿 |

V60→V61 保全用例首跑即绿（升级分类本已正确，作为边界钉住）。

## 实测（真实退出码）

- 后端定向：`./mvnw -B -DforkCount=0 -Dtest="AccountAuditIntegrationTest,AccountAuditUpgradeDataPreservationTest,UserImportIntegrationTest,SuperAdminGuardIntegrationTest,ExtendedLifecycleIntegrationTest" test` → **38 通过 / 0 失败，BUILD SUCCESS，exit 0**（`logs/backend-targeted.log`）。
- 前端定向：`npx vitest run --no-file-parallelism src/views/AccountAuditView.spec.ts src/utils/accountAuditChange.spec.ts src/layouts/sectionTabs.spec.ts src/router/roleAccess.spec.ts` → **21 通过（4 文件），exit 0**（`logs/frontend-targeted.log`）。
- `npm run lint` exit 0（`logs/frontend-lint.log`）；`npm run build` exit 0（`logs/frontend-build.log`）。
- 仓库级检查 8 项全 OK（`logs/repo-checks.log`）。
- 真实浏览器：`bash scripts/e2e.sh e2e/account-audit.spec.ts --grep "超级管理员" --timeout=30000 --max-failures=1` → 1 通过，exit 0；全量 `--timeout=60000 --max-failures=1` → **3 通过（root/教务/老师），25.7s，exit 0**（`logs/e2e-root.log`、`logs/e2e-full.log`）。
- 截图 4 张：`.scratch/dingtalk-multiuser/acceptance/dingtalk-t02-account-audit-{target,action}.png`、`...-denied-audit_{jw,teacher}.png`。

## 边界与未验证

- 仅 H2 e2e（`e2e` profile + 假钉钉）；Docker/真实 PostgreSQL 缺失，`PostgresContainerIT` 未跑，不是生产库验收。
- 未跑全量 `verify.sh`（按票面只跑定向）；未部署、未推送。
- 首条 e2e 首次尝试因 `pickOption` 需要 Locator 作用域而失败（`scope.page is not a function`），改传 `page.locator('body')` 后通过；未改公共 helper。
