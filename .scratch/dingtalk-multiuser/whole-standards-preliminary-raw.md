# 整特性 Standards 轴预审 checkpoint（016859b，非最终放行）

- 源：`E:/排课系统/.worktrees/dingtalk-t07-review-snapshot`，HEAD `016859bc35a31f2cf5c93e2280e395cbad2b6d62`，工作树 clean，未移动。
- 范围：`git diff 2e5e585f6ed80d5024af6b734162a70829b067cf...016859bc`。真实计数：**335 文件 / +25112 / −923**；其中代码面（backend+frontend+scripts）**198 文件 / +19645 / −918**，其余为 `.scratch` 文档与验收截图。
- 本候选不含 08；未跑测试/构建/网络/服务，未读 `.env`，未改源、未 stage/commit、未删除。原轴口径：仅 Standards 轴。

## Hard（明确违反文档标准）

**H1｜P2｜E2E 共用浏览器错误收集未上收 helpers**
新增四个 spec 各自内联同一段 console error + pageerror 收集：`frontend/e2e/dingtalk-sync.spec.ts:6-15`、`substitution-approval.spec.ts:25-35`、`account-audit.spec.ts:22-34`、`notification-delivery-logs.spec.ts:19-29`；共享实现已在 `frontend/e2e/helpers.ts:44` 导出（07 整改产物）。
规则：`CODING_STANDARDS.md`《测试》「E2E 公共交互……只在 `helpers.ts` 实现一次；各 spec 不复制同名 helper」。未被工具拦：`scripts/check-e2e-helpers.sh` 只比对 helpers.ts 导出名。
最小修复：`let browserErrors: string[]` + `beforeEach(({page}) => { browserErrors = trackBrowserErrors(page) })`，四 spec 改 import。若主控把该条字面限定为「同名」，可降为 P3 judgement，理由与改法不变。

## Judgement（Fowler 启发式，均 P3，非硬违反）

- possible Duplicated Code：`substitution-approval.spec.ts:41-45` `pickDate`（fill+Tab）与 `substitution.spec.ts:59-60` 内联同形；建议上收 helpers。
- possible Duplicated Code / Primitive Obsession：`SettingsView.vue:236-237` 写死 `'integration:manage'`、`'substitution:view'`，而 `constants/permissions.ts:23` 已导出常量且 `TeachersView.vue:191` 已在用。
- possible Speculative Generality：`notification/service/NotificationService.java:55-90` `notify(...)` 与 `:116-122` 两个空实现退役入口，主源码零调用点；建议删除或注明保留理由。

## 已实读并判为无违规（覆盖证据）

- 事务：`SettingsService.putAll` 整批事务；`LocalNotificationChannel` `REQUIRES_NEW` 两表同事务；`AccountAuditService.record` 加入调用方事务；账号导入全量校验后单事务写入。
- UID 写入保护：`FoundationService` 建/改教师、`DingTalkAccountProvisioner`、`DingTalkOrgDataProvider.applyEvent`、同步 `DingTalkSyncPlanner`+`apply` 全部先取 `AccountMutationGuard` 锚点锁并过 `DingTalkBindingPolicy`；锁序统一 anchor→teacher(id 升序)→account；Excel 导入 UID 传 null。
- 异步提交后边界：`NotificationDeliveryBridge` AFTER_COMMIT 才入 `notificationExecutor`（1 线程/200 队列/AbortPolicy）；gateway `NOT_SUPPORTED`；resolver `REQUIRES_NEW` 读完后才发网络。
- 权限与 allowlist：新端点全用 `Permissions` 常量；`/api/settings` 对无 `integration:manage` 者剥离 `integration.dingtalk.*`；公开配置仅 enabled+corpId；AppSecret 只回布尔。
- 敏感信息：探针只记 stage+code，不回 vendor 正文/带 token URL；通知失败日志不含收件人；审计快照 allowlist 无密码/哈希/token。
- Fake 门控：`fake=true` 仅 test/e2e profile（`application.yml:122-125`），生产默认 Http 实现。
- V60–V63 与实体/`ddl-auto=validate` 一致，编号无冲突。

## 未覆盖边界

08 全切片；最终 snapshot 的增量与整规格一致性需重审。未验证 PostgreSQL、真实钉钉租户、手机送达、全量门禁与 Playwright 实跑。前端仅抽读 `SettingsView/AccountAuditView/SubstitutionsView/sectionTabs/DingtalkConnectionProbe/LoginView` 关键段，未逐行全量；`.scratch` 历史文档一致性与另轴 raw 未读。**不据此宣称整规格通过。**

## Checkpoint

本文件锁定 016859b 口径。最终 snapshot 出来后按「全起点→最终候选」重跑：先核 `rev-parse`/`status`，再 `git diff --stat 2e5e585...<final>` 与 016859b…`<final>` 增量；H1 若未修即为最终 Hard，新增 spec 与 helpers 变化需复查共享 E2E。
