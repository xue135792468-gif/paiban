# 票 04-B：同步预演「将停用」前端选择与确认切片

2026-11-11，分支 `codex/dingtalk-t04-ui`，工作树 `.worktrees/dingtalk-t04-ui`。

开工前已 fast-forward 合并 `codex/dingtalk-multiuser` 最新提交 `73ddd1f`（`t04-wire-contract.md`），并执行 `scripts/doctor.sh` 核实可用物理内存约 5.0 GB、端口空闲。

## 范围与边界

- 本切片为 **04-B 前端切片 + 真实 API Playwright 用例准备**，严格遵循 `.scratch/dingtalk-multiuser/t04-wire-contract.md`。
- 只修改前端契约与组件文件：
  - `frontend/src/api/integration.ts`
  - `frontend/src/components/DingTalkSyncPreview.vue`（351 行，≤ 600 行限制）
  - `frontend/src/components/DingTalkSyncPreview.spec.ts`（新增组件单测）
  - `frontend/src/views/SettingsView.spec.ts`（补齐 `previewFixture` 的 `scanComplete`/`deactivationWarning`/`deactivations` 字段及 `confirm` 请求载荷中的 `deactivationSelections: []`）
  - `frontend/e2e/dingtalk-sync.spec.ts`（新增票 04 真实 API 端到端用例，待 A/C 后端整合后运行）
  - `.scratch/dingtalk-multiuser/t04-ui-delivery.md`（本交付记录）
- 未修改 `backend/**`、其他票据、旧 ADR，未将 `.scratch/dingtalk-multiuser/issues/04-sync-deactivation.md` 标为 done/implemented。

## 接口与交互实现要点（对照 `t04-wire-contract.md`）

1. **扫描完整性严格判定**：
   - 仅当 `preview.scanComplete === true && preview.failedDepartments === 0` 时视为完整扫描；若 `scanComplete` 为 `false` 或字段缺失（`undefined`），一律视为不完整扫描。
   - 不完整扫描时：渲染 `[data-testid="dingtalk-deactivation-warning"]` 警告提示（优先展示后端 `deactivationWarning`，否则按 `failedDepartments` 展示兜底原因），**不显示误导性的「将停用 0」标签**，不渲染待停用候选表，且即使响应夹带 `deactivations` 也不采纳。
2. **默认勾选与逐个排除**：
   - 完整扫描且有候选时，在摘要栏展示 `[data-testid="dingtalk-deactivation-tag"]`（`将停用 M`），并渲染 `[data-testid="dingtalk-deactivation-section"]` 待停用账号表格（展示账号、姓名、关联教师、钉钉 UserId、说明）。
   - 默认勾选当前预演的全部候选，支持逐个取消勾选排除；当 `props.preview` 更新时，立即清空旧的排除状态，仅从新预演生成选择，不残留旧预演的账号。
3. **按钮状态与二次确认**：
   - 确认按钮禁用条件为 `confirmCount === 0 && selectedDeactivations.length === 0`；当「0 人导入、M 个账号停用」时按钮可点击，且无部门导入时提示「本次没有新增或补齐的教师，仅处理上方待停用账号。」。
   - 按钮文案区分：`确认导入 N 人、停用 M 个账号` / `确认停用 M 个账号` / `确认导入 N 人`。
   - 当存在待停用账号（`M > 0`）时，点击确认先弹 `ElMessageBox.confirm`，写明「将停用 M 个账号。停用后无法登录，不删除教师档案、不改动课表，后续可在账号页重新启用。确定继续？」；取消则不触发 `confirm` 事件。
4. **确认载荷（`deactivationSelections` 三字段期望值）**：
   - `confirm` 事件载荷追加 `deactivationSelections: Array<{ accountId, teacherId, dingtalkUserId }>`，仅携带当前勾选候选的三字段期望值供后端锁内复核。
   - `SyncResult` 增加可选 `accountDisabled?: number`，与远端 inactive 教师指标 `deactivated` 保持区分。

## TDD 红 → 绿记录

测试 seam：用户交互 → `DingTalkSyncPreview` 公开 DOM 与 emitted `confirm` payload（不测私有函数）。

| 垂直切片 | 红（失败断言摘要） | 最小实现 | 绿 |
|---|---|---|---|
| 1. 完整扫描默认全选、按钮文案、二次确认与三字段载荷 | `AssertionError: expected '...确认导入 1 人...' to contain '将停用 2'` | `integration.ts` 补充类型；`DingTalkSyncPreview.vue` 实现待停用表格、动态按钮文案、`ElMessageBox.confirm` 与 `deactivationSelections` emit | 1 passed |
| 2. 扫描不完整或缺失 `scanComplete` 警告与防误停用 | `AssertionError: expected false to be true`（`[data-testid="dingtalk-deactivation-warning"]` 不存在） | 增加 `deactivationWarningMessage` 与警告 `el-alert`，严格限制仅 `scanComplete === true` 才渲染标签与采纳候选 | 2 passed |
| 3. 逐个排除、二次确认取消不提交、0 导入仅停用可确认、新预演清空旧选择 | `AssertionError: expected '...没有可导入的用户。' not to contain '没有可导入的用户。'` | 空部门但有待停用候选时渲染专属说明文案，完成排除/取消/新预演重置闭环 | 4 passed |

## 已执行验证命令与退出码

均在 `E:/排课系统/.worktrees/dingtalk-t04-ui/frontend`（或工作树根）通过 GitPortable Bash 串行执行：

1. `npx vitest run --no-file-parallelism src/api/integration.spec.ts src/components/DingTalkSyncPreview.spec.ts src/views/SettingsView.spec.ts` → **3 files / 13 tests passed, exit 0**
2. `npm run lint`（`eslint . --max-warnings 0`） → **exit 0**
3. `npx vue-tsc -b` → **exit 0**
4. `npx vite build` → **exit 0**
5. `npx playwright test --list e2e/dingtalk-sync.spec.ts` → **2 tests in 1 file, exit 0**
6. `bash scripts/check-sfc-size.sh && bash scripts/check-e2e-helpers.sh && bash scripts/check-ticket-status.sh` → **exit 0**

## 未运行项（待 C 后端停用事务整合后由主线程执行）

- **Playwright 真实浏览器与后端 API 执行（未跑）**：`frontend/e2e/dingtalk-sync.spec.ts` 已准备好第 2 条用例「钉钉同步预演『将停用』候选：逐个排除、二次确认停用、保留档案并写同步停用审计」（含截图埋点 `dingtalk-t04-sync-deactivation-preview` 与 `dingtalk-t04-sync-deactivation-confirmed`）。因 04-A（预演返回 `deactivations`）与 04-C（确认停用事务与 `ACCOUNT_SYNC_DISABLE` 审计写入）尚未合入本分支，本切片按要求未启动全栈服务与真实浏览器，留待 C 整合后由主线程统一运行并采集验收截图。
- **后端 Maven 测试与全量 `scripts/verify.sh`（未跑）**：本切片不涉及后端改动。
