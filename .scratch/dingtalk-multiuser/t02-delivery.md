# 02 交付记录：账号操作审计与查看页

日期：2026-11-11。分支 `codex/dingtalk-t02`，基于集成 `codex/dingtalk-multiuser`（收尾合并到 `01ae454`）。

> 本记录是 `4eb1886` 初次施工的阶段快照。复查后的动作目录、实现与最新验证见 `t02-review-fixes-delivery.md`；下面的“尚未运行E2E”等描述保留当时事实，不代表整改后的状态。

## 做了什么

- 复用 `audit_log`（V61 加列）：`category`（`ACCOUNT` 分类）、`target_user_id`（稳定目标账号 ID）、`before_json` / `after_json`（allowlist 快照）+ `(category, created_at, id)` 索引；旧日志全保留，既有账号事件（`APP_USER`、`users/IMPORT`）回填为 `ACCOUNT`。
- 唯一写入入口 `AccountAuditService`（建号 / 改账号 / 重置密码 / 停用 / 启用 / 批量导入按账号逐条 + 整批摘要），写入与业务同事务；快照只有账号标识、姓名、启用、关联教师、身份编码——不含密码、哈希、token、密钥或原请求。
- 动作目录 9 类：`ACCOUNT_CREATE / UPDATE / RESET_PASSWORD / DISABLE / ENABLE / IMPORT` + 预留 `DINGTALK_AUTO_PROVISION / DINGTALK_LINK / SYNC_DISABLE`（只进目录，不造假实现）；系统操作者常量「系统（钉钉免登）」已备好，供 03/04 传入。
- 查看接口 `GET /api/account-audits`（`audit:view:account`）：动作 / 目标账号过滤、`createdAt+id` 倒序、分页全部在数据库层；`GET /api/account-audits/actions` 给页面下拉与中文标签。
- 旁路封堵：旧 `GET /api/foundation/audit-logs`（`foundation:manage`，教务也持有）改为数据库层排除 `ACCOUNT`，其余基础日志照旧可读。
- 并发：`resetPassword` / `enable` 与 `update` / `disable` 共用超管锚点锁 + 锁内重读；否则整行更新会把并发停用的旧 `enabled=true` 写回（已用变异验证）。
- 前端：设置页签「账号审计」→ `/settings/account-audit`（`audit:view:account`），五列表格（时间 / 操作者 / 目标账号 / 动作 / 变更），动作 9 类下拉、目标账号下拉（`/users`，含停用账号，提交稳定 ID）、空态、底部分页、筛选变化回第一页；变更列中文渲染，不裸露 JSON；迟到响应按请求序号丢弃。

## 红 → 绿（首次失败 → 通过）

| 切片 | 首次失败证据 | 通过后 |
|---|---|---|
| 手工建号审计 | `GET /api/account-audits` 返回 404 | 1 条 `ACCOUNT_CREATE`，actor/目标/after 齐全 |
| 改身份 / 重置密码 / 停用启用 | 三条断言均失败（仍写旧 `DISABLE_USER`、update/reset 无审计） | 各 1 条，前后快照可比 |
| 过滤分页 / 三角色权限 / 旧接口旁路 | 回归钉住，首跑即绿（契约在切片 1 一并实现） | 教务、老师 403；教务旧接口看不到 `ACCOUNT_*` |
| 失败不留事件 / 批量导入逐目标 | 导入仍记 `ACCOUNT_CREATE` 且摘要从旧接口可见 | 每账号 1 条 `ACCOUNT_IMPORT` + 1 条摘要，`ACCOUNT_CREATE`=0 |
| 重置密码不复活停用账号 | 变异去掉锁 + 重读 → `Expecting false but was true` | 恢复后 `enabled=false`，无 `ACCOUNT_ENABLE` |
| 前端迟到响应 | 变异去掉请求序号 → 旧响应覆盖新结果 | 恢复后表格保持最新结果 |

## 实测命令与计数

- 后端定向（本票 + 受影响回归，`-DforkCount=0`）：`mvnw -Dtest="AccountAuditIntegrationTest,UserImportIntegrationTest,SuperAdminGuardIntegrationTest,RoleReshapeIntegrationTest,RolePermissionMatrixIntegrationTest,RoleUpgradeDataPreservationTest,IdentityDataInitializerTest,SettingsPermissionIntegrationTest,AuthRbacIntegrationTest,FoundationIntegrationTest,ExcelImportIntegrationTest,LifecycleIntegrationTest,ImportRecipeManagementIntegrationTest,DraftChangeIntegrationTest,DraftLockIntegrationTest" test` → **112 通过，0 失败**；其中 `AccountAuditIntegrationTest` 11/11（变异恢复后单独复跑仍 11/11）。
- 前端定向：`npx vitest run --no-file-parallelism src/views/AccountAuditView.spec.ts src/utils/accountAuditChange.spec.ts src/layouts/sectionTabs.spec.ts src/router/roleAccess.spec.ts` → **18 通过（4 文件），0 失败**。
- `npm run lint`（eslint 全量 `--max-warnings 0`）退出码 0；`npm run build`（vue-tsc + vite）退出码 0。
- 仓库级 shell 检查：migrations / ticket-status / sfc-size / blank-checks / module-boundaries / e2e-helpers / local-dates / ai-http-timeouts 全部 OK。
- 环境：`bash scripts/doctor.sh` 内存 5.3 GiB 可用；Docker 未安装（非阻塞，本机无真实 PG）。

## 边界与未验证

- **Playwright**：`frontend/e2e/account-audit.spec.ts` 由独立 QA 编写（本票未改动），需完整前后端整合后由主模型运行；本票未运行 e2e。
- **真实 PostgreSQL**：V61（加列 + 回填 + 索引）只在 H2（`MODE=PostgreSQL`, `ddl-auto=validate`）上跑过；未在真实 PG/生产库演练，`PostgresContainerIT` 本机无 Docker 未跑。
- **系统动作**：02 没有系统动作可写，「系统（钉钉免登）」只是常量与显式 actor 入口，真正写入留给 03/04。
- **旧接口语义变化**：`GET /api/foundation/audit-logs` 对任何角色都不再返回账号事件（含超级管理员）；超级管理员改从账号审计页看，属本票的旁路封堵决定。
- 未做全量 `verify.sh`、未跑全量前端/后端套件（按票面只跑定向）；未推送、未部署。

## 备注

变异验证后恢复源码时，Maven 增量编译曾因文件时间戳回退沿用旧的 class（出现一次假失败）；touch 源文件后重编译即 11/11 绿，源码无净变化。

复查补充（2026-11-11）：上述定向集合的通过结果真实，统筹在删除已合入的施工工作树前直接核对过全部15份JUnit XML，失败/错误/跳过均为0。该命令包含 `LifecycleIntegrationTest`，不包含 `ExtendedLifecycleIntegrationTest`，不能据此宣称所有生命周期用例通过。复查额外发现后者两条旧断言仍从通用日志读取 `DISABLE_USER/ENABLE_USER`，在基点实测失败；现已修正并纳入整改后的定向集合，见 `t02-review-fixes-delivery.md`。这是覆盖范围补齐，不是否定已验证的通过数。
