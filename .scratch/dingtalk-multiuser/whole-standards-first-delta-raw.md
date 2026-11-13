# Standards 首次修复 delta 复核（be48fc0…d7ee6ff，独立只读）

- 源 `E:/排课系统/.worktrees/dingtalk-standards-fixes-snapshot`，HEAD `d7ee6ffca6456296f5daf654809d6c8516b6f3ea`，clean、未移动旧 be48 快照；未跑测试/构建/网络，未 stage/commit。delta 31 文件，代码仅 9：`NotificationService`、`helpers.ts`、6 个 spec、`SettingsView.vue`；无新迁移。
- **H1（P2）关闭**：`account-audit:28`、`dingtalk-sync:9`、`notification-delivery-logs:19`、`substitution-approval:32` 均改为 `browserErrors = trackBrowserErrors(page)`，监听范围不变；后两者原 `afterEach` 断空保留。Hard 现 **P1 0 / P2 0 / P3 0**。
- **J1 关闭**：`helpers.ts:74-81` 新增 `fillDateInput(Locator,date)`（fill+Tab 原义）；`substitution.spec.ts:56`、`substitution-approval.spec.ts:44`、`dingtalk-acceptance.spec.ts:82` 三处改调用，日期值与后续断言未动，私有 `pickDate` 已删。
- **J2 关闭（按裁定）**：`SettingsView.vue:204,237` 复用 `INTEGRATION_MANAGE_PERMISSION`；`'substitution:view'` 按主控裁定不变，未造权限字典。
- **J3 关闭（按裁定）**：`NotificationService` 两个零调用空方法已删（−8 行），`notify(...)` 保留为既有 public seam。
- **J4 保留 P3**：主控不采纳、非延期、未修复，本轴不改记录。
- **新增 J5｜P3 possible Speculative Generality／测试空转**：`account-audit.spec.ts` 与 `dingtalk-sync.spec.ts` 只在 `beforeEach` 收集 `browserErrors`，全文件无 `afterEach` 断空（另两 spec 有），收集值无人消费，浏览器错误可静默通过。建议各补 `test.afterEach(() => expect(browserErrors).toEqual([]))`。
- **计数**：Hard P1 0 / P2 0 / P3 0；Judgement P1 0 / P2 0 / **P3 2**（J4 保留 + J5 新增）。最坏项：J5（P3，测试守卫空转）。
- **边界**：R6「backend 12 pass / EXIT 0」为交付方报告，本轴未复跑；R5 的 class-not-found red 属 `MSYS_NO_PATHCONV` 环境误设，非业务红，不据此判行为。writeset before/after 为施工前后哈希，**不构成测试前后源冻结证据**，最终 full 需另给真冻结。默认 `/workbench/timetable` 隐藏侧栏、无他处入口的 R1 导航缺口属主控新增 UI 修复，不在本快照、本轴本次未查 MainLayout。
- 本 delta 未改事务/UID 保护/异步边界/权限常量后端面，无回归迹象；仍不宣称整规格通过（full、真实 PG/钉钉租户/手机未验证）。
