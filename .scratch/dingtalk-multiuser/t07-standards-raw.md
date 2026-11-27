# 票 07 Standards 轴原始报告（独立、只读）

- 审源：`E:/排课系统/.worktrees/dingtalk-t07-review-snapshot`，HEAD=`64a8885cefda7782608d1a78a1a6f664cdf2f32a`，工作树 clean；未改源、未 stage/commit、未删除、未跑测试/构建/网络/服务、未读 `.env`、未读另一轴报告。
- 基点 `e7d0bfc55274099b9f79bff795b3c83c0a00e318`；`git diff <base>...<HEAD>` 34 路径（1837+/55-）。
- 开工资源：FreeGB 6.45 / 15.73（41%），未派并行子代理。
- 依据：CODING_STANDARDS.md、AGENTS.md、ADR-0005/0036、spec.md:157-158、t07-wire-contract.md、t07-probe-plan.md。

## 提交列表（11 条，按给定顺序）

```
64a8885 docs(dingtalk): verify probe browser flow and complete illustrated school guide
e6c1d27 fix(dingtalk): clarify credential reuse and probe persistence feedback
5c91976 Merge commit 5ba87a7
5ba87a7 fix(dingtalk): isolate fake probe capability from legacy client replacements
a01c264 Merge branch codex/dingtalk-multiuser into backend
1277c3c feat(dingtalk): probe unsaved credentials and save integration configuration atomically
7243516 test(dingtalk): cover safe probe rejection through real browser flow
e7d8f34 docs(dingtalk): correct probe evidence boundaries and prepare isolated acceptance identity
59d8ca7 merge(dingtalk-t07): merge UI slice f3b0d49
f3b0d49 feat(dingtalk-t07): add connection probe UI
9d6ea2b docs(dingtalk): draft school setup and real-device acceptance guide
fbca99d docs(dingtalk): start ticket 07 with isolated backend and UI scopes
```

## Hard：明确违反已文档标准

**H1｜P2｜E2E spec 内复制同名 helper**
新增 `frontend/e2e/dingtalk-connection.spec.ts:20-27` 与既有 `frontend/e2e/dingtalk-login.spec.ts:9-16` 的 `trackBrowserErrors` 逐字相同（console error + pageerror 收集）。
规则：CODING_STANDARDS《测试》「E2E 公共交互…只在 `frontend/e2e/helpers.ts` 实现一次；各 spec 不复制同名 helper」。
未拦原因：`scripts/check-e2e-helpers.sh` 只比对 helpers.ts 的导出函数名（脚本头自述），非导出名不受检。
最小修复：`trackBrowserErrors` 上收 helpers.ts 导出，两 spec 改 import。

## Judgement：Fowler 启发式（possible，均为判断项）

- **J1｜P3 possible Duplicated Code（同文件）**：`HttpDingTalkProbeClient.java:53-75` 与 `77-97` 两段四个 catch（RestClientResponseException/ResourceAccessException/RestClientException/RuntimeException）同形重复。建议抽 `callStage(stage, Supplier<Map<?,?>>, rejectedCode)`。
- **J2｜P3 possible Duplicated Code（跨文件）**：`HttpDingTalkProbeClient.java:28-43` 的 `API`/`OAPI` 常量与 `boundedRestClient()`（5s/15s）与 `HttpDingTalkClient.java:32-33,46-51` 完全一致。建议抽共享常量+传输工厂；两个客户端仍应分立（有/无 token 缓存）。
- **J3｜P3 possible Data Clumps**：`(appKey, appSecret)` 两个 String 从 `IntegrationDtos.java:15` → `DingTalkConnectionProbeService.java:27` → `HttpDingTalkProbeClient.java:51` 一路同行，而 `DingTalkStoredCredentialReader.java:28` 的 `StoredCredentials` 已是同一对值。建议新增 `DingTalkCredentials` 记录并传一个值。
- **J4｜P3 possible Duplicated Code / 语义漂移**：复用判定前端 `DingtalkConnectionProbe.vue:61-69` 用 `Boolean(appKey.value)`，后端 `DingTalkConnectionProbeService.java:37` 用 `hasText`；纯空白 AppKey 时前端放行按钮、后端 400。建议抽 trim+空判断纯函数并配单测。
- **J5｜P3 Mysterious Name（测试）**：`DingTalkConnectionProbeIntegrationTest.java:70` 名为「非法策略不动旧值」，:104-116 又断言保存密钥复用；名不副实，建议拆分（同文件 :113-114 还混用全限定 `assertThat`，而 :3 已静态导入）。
- **J6｜P3 possible Speculative Generality**：`SettingsView.vue:214` `DingTalkConfig & { appSecret: string }` 与 `integration.ts:19` 的 `appSecret?: string` 重复声明；建议直接用局部表单类型。
- **J7｜P3 possible Duplicated Code**：`SUPER_ADMIN` 字面量重复于 `dingtalk-connection.spec.ts:6`、`account-audit.spec.ts:11`、`dingtalk-roles.spec.ts:14`；建议 helpers.ts 导出一次。

## 已核对、未判为问题

- 凭据保密：`HttpDingTalkProbeClient.java:141-146` 只记 stage+code，响应仅 success/code/message，vendor 正文不入日志/响应、不入审计（符合《审计与 AI》）；探针不写任何行（《事务与失败》）。
- 事务：`SettingsService.java:218-221` `putAll` 自带 `@Transactional`（self-invocation 由外层事务覆盖），校验先于写入，网络在事务外。
- 权限：新端点用 `Permissions.INTEGRATION_MANAGE`（`IntegrationController.java:137`）；请求 secret 走 body，不进 URL。
- SFC：`DingtalkConnectionProbe.vue` 123 行；`SettingsView.vue` 613→467（基线只缩）。
- 空白判断：新代码用 `hasText`；`IntegrationController.java:118` 保留 `isBlank` 属存量，baseline 计数仍 3、脚本通过（不计违规）。
- fake=true 仅 test/e2e profile（`application.yml:122-125`、`application-e2e.yml:26-28`），生产默认 `HttpDingTalkProbeClient`。迁移 0 条，无编号冲突。

## 计数

Hard：P1=0，P2=1，P3=0。Judgement：P2=0，P3=7。
最坏项：H1（P2，E2E `trackBrowserErrors` 复制，规则明文、工具未覆盖）。
未跑测试/构建/网络，未验证 full verify 与真实 PG/租户/手机；本报告不代主控宣称放行。