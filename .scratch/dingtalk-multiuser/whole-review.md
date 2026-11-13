# 整特性双轴复查与主控闸门

固定候选be48fc0e3b08013c4ffbd7d716d9385169ce6e8d，起点2e5e585f6ed80d5024af6b734162a70829b067cf。两原轴只读独立执行；下面保留候选报告原文，主控裁定另见whole-review-owner-decisions.md。原预审及Spec自行更正文件均保留。当前未放行：确认项由唯一fixer Turing整改，独立增量与受影响浏览器/最后完整门禁待完成。

## Standards

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


## Spec

# 全 8 票 Spec 轴终审初稿（P 级，非最终放行）

审源 `be48fc0e3b08013c4ffbd7d716d9385169ce6e8d`（只读 WT，rev-parse 全 SHA 已核，未移动、干净）；base `2e5e585f…`；delta `016859b...be48fc0` 22 files +1309/-47。spec/issues01–08、ADR-0036、已确认绑定保护未变。未跑测试/构建/网络，未改源。

## (a) 缺失 0
spec:25、41「所有账号操作留审计」：自动开号+配对各一条、精确 2 条（`DingTalkAcceptanceChainIntegrationTest.java:105-109`）。spec:134 期望/最终分别记录（同 132/140；`dingtalk-acceptance.spec.ts:142-143`）；spec:24、145 仅两类通知、仅最终接课人：外发恰 2 条且收件人=申请人+最终人，提交阶段 0 条、期望/最终教师均无影子账号（160-176）。真实 `POST /api/auth/dingtalk` 取 JWT（96-101；E2E 69-78），申请人/期望/最终/教务四身份分离（134-135）。旧表保全 21 张逐列投影+灵敏度自检（`OldDatabaseUpgradeAcceptanceTest.java:49、145`）。补审原未覆盖：`AccountAuditView.vue:21-43、83-87`、五状态 `types.ts:164-170`、`UserAdminView.vue:55` 免登无重置密码、`NotificationDeliveryGateway.java:25`（NOT_SUPPORTED）与 `DingTalkNotificationChannel.java:54-76`（3 次重试、首成功降级）。

## (b) 范围外 0
`fake:acceptance:` 仅存在于 `@ConditionalOnProperty(fake=true)` 的假客户端、不入同步目录（`FakeDingTalkClient.java:24、99-104、160-166`），不构成产品扩范围。

## (c) 实现错 0
独立计数：P0/P1/P2/P3 均 0，最坏项无。preliminary 的 4 条 P3 已按主控初裁与 corrigendum 全部撤回，不重开；本 delta 与新读 hunks 未发现新缺陷。

## 证明边界（不得据此宣称）
H2/Fake/离线手机视口不证明真实 PostgreSQL、钉钉租户、手机送达或实机；升级 5 项为 H2，真实 PG 仅靠本轮未运行的 `PostgresContainerIT`；`verify --full` 尚未跑。本轴结论限于「本审源未发现产品 confirmed 缺陷」，不是全量通过。


## 主控边界与下一步

本例原任课老师就是申请人，收到审批结果；最终接课老师收到代课安排，不再额外发原教师抄送模板。不是说原教师没有任何通知。原Spec聊天摘要的歧义以此和原始文件精确两对象断言为准。

H1已确认；J1、J2/J3中有实际价值的部分一并交单一fixer。J4配置开关包装不采纳（现有apiCall已共用且各fixture输入不同），保留为原P3判断建议，不伪称已修复。整改新源应独立补核，不删除/改写原候选发现。全量未跑，不push/部署；PG、真实租户及手机仍未验证。

候选计数：Standards Hard=1(P2)、Judgement=4(P3)，最坏H1 P2；Spec=0，最坏项无。两轴不混并或跨轴重排。
