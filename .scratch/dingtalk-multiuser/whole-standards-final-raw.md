# 整特性 Standards 轴终审（固定候选 be48fc0，未放行）

- 源：`E:/排课系统/.worktrees/dingtalk-whole-review-snapshot`，`git rev-parse HEAD`=`be48fc0e3b08013c4ffbd7d716d9385169ce6e8d`，只读、未移动；016859b 工作树原样。
- 全范围 `2e5e585f…be48fc0`：**345 文件 / +26382 / −931**；增量 `016859b…be48fc0`：**22 文件 / +1309 / −47**（15 文档、7 代码：FakeDingTalkClient、3 个新测试、acceptance spec、login spec、helpers）。
- 未跑测试/构建/网络/服务；未读 `.env`；未 stage/commit/删除。**不宣称已通过**。

## Hard（明确违反文档标准）

**H1｜P2｜E2E 共用错误收集仍内联（未修）**
`account-audit.spec.ts:22-34`、`dingtalk-sync.spec.ts:6-15`、`notification-delivery-logs.spec.ts:19-29`、`substitution-approval.spec.ts:25-35` 各写一份 console+pageerror 收集；共享实现已在 `helpers.ts:44 trackBrowserErrors`（另两个 `pageerror` 站点属本特性未改的既有 spec，不计入）。
规则：`CODING_STANDARDS.md`《测试》「E2E 公共交互只在 helpers.ts 实现一次；各 spec 不复制同名 helper」；工具盲区为 `check-e2e-helpers.sh` 只查导出名。08 新 spec 已正确复用该 helper。已交唯一 fixer，待新源 delta 关项。

## Judgement（Fowler 启发式，均 P3，非硬违反）

- **J1 未修**：无 `fillDateInput`；`dingtalk-acceptance.spec.ts:72-74` 为第三处「fill + Tab」（另见 `substitution-approval.spec.ts:41-45`、`substitution.spec.ts:59-60`）。主控已采纳。
- **J2 未修**：`SettingsView.vue:236` 仍写死 `'integration:manage'`；按裁定只复用 `INTEGRATION_MANAGE_PERMISSION`，`'substitution:view'` 保持惯例。
- **J3 部分未修**：`notify(...)` 按裁定保留（两处集成测试 public seam）；`NotificationService.java:116-122` 两个零调用空方法仍在，待删除。
- **J4 新增**：`dingtalk-acceptance.spec.ts:57,171`、`dingtalk-connection.spec.ts:35,124`、`dingtalk-login.spec.ts:26,84`、`dingtalk-sync.spec.ts:28,139` 各自手工开通/关闭钉钉配置，同形四份；建议 helpers 增 `enableDingTalk/disableDingTalk`。

## 08 新增核对（实读，未判问题）

`FakeDingTalkClient:97-121,159-166` 合成身份带 `[A-Za-z0-9_-]{1,40}` 校验、不进可同步目录，`FakeDingTalkAcceptanceIdentityTest` 断言目录仍只有 dd-001/002；`helpers.ts` 新增 `installFakeWindowDd/DINGTALK_UA`（登录 spec 就地私有实现已上收，属改善）、`seedSchool(teacherAccount)` 默认不变、`classEntries` 仅收窄类型。整链 E2E 用行作用域断言与固定可观察状态，finally 只关停自建账号与配置。`DingTalkAcceptanceChainIntegrationTest` 用 FIFO 屏障而非 sleep，断言「未批准不生效、无外发」→「批准后仅申请人+最终接课人两条钉钉记录」与 account 审计两条；`OldDatabaseUpgradeAcceptanceTest` 用独立内存库、逐值投影对照 + V60/61/62/63 专项，明确自述不证明真实 PG。08 无新迁移。

## 未覆盖

08 浏览器/后端实跑、full verify、真实 PostgreSQL 与钉钉租户/手机；fixer 工作树下一次整改结果；`UserAdminView/DingTalkSyncPreview/SubstitutionApprovalPanel` 仅抽查；`.scratch` 文档一致性未审。

## 计数

Hard：P1 **0**、P2 **1**（H1）、P3 **0**。Judgement：P1 0、P2 0、P3 **4**（J1/J2/J3 未修 + J4 新增）。本轴合计 5 项；最坏项 **H1 P2**（规则明文、工具未覆盖、4 spec 待改）。
