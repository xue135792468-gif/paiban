# 07 双轴复查与主控判定（统一整改中）

2026-11-13。固定基点e7d0bfc55274099b9f79bff795b3c83c0a00e318，候选64a8885cefda7782608d1a78a1a6f664cdf2f32a；三点diff非空34路径。完整12条提交及提交后源码与浏览器源对象一致的证明见logs/t07-review-input。原Standards标题写11条是数量笔误，列表实际12条；原报告保留，不据此改发现数量/等级。

两独立轴已完成，只做标题层级调整后列于下方；不合并或重排发现。主控验收见t07-main-acceptance.md。当前07尚未放行，08只准备；确认问题交单一fixer，修复及独立增量后才下一票。

## Standards
### 票 07 Standards 轴原始报告（独立、只读）

- 审源：`E:/排课系统/.worktrees/dingtalk-t07-review-snapshot`，HEAD=`64a8885cefda7782608d1a78a1a6f664cdf2f32a`，工作树 clean；未改源、未 stage/commit、未删除、未跑测试/构建/网络/服务、未读 `.env`、未读另一轴报告。
- 基点 `e7d0bfc55274099b9f79bff795b3c83c0a00e318`；`git diff <base>...<HEAD>` 34 路径（1837+/55-）。
- 开工资源：FreeGB 6.45 / 15.73（41%），未派并行子代理。
- 依据：CODING_STANDARDS.md、AGENTS.md、ADR-0005/0036、spec.md:157-158、t07-wire-contract.md、t07-probe-plan.md。

#### 提交列表（11 条，按给定顺序）

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

#### Hard：明确违反已文档标准

**H1｜P2｜E2E spec 内复制同名 helper**
新增 `frontend/e2e/dingtalk-connection.spec.ts:20-27` 与既有 `frontend/e2e/dingtalk-login.spec.ts:9-16` 的 `trackBrowserErrors` 逐字相同（console error + pageerror 收集）。
规则：CODING_STANDARDS《测试》「E2E 公共交互…只在 `frontend/e2e/helpers.ts` 实现一次；各 spec 不复制同名 helper」。
未拦原因：`scripts/check-e2e-helpers.sh` 只比对 helpers.ts 的导出函数名（脚本头自述），非导出名不受检。
最小修复：`trackBrowserErrors` 上收 helpers.ts 导出，两 spec 改 import。

#### Judgement：Fowler 启发式（possible，均为判断项）

- **J1｜P3 possible Duplicated Code（同文件）**：`HttpDingTalkProbeClient.java:53-75` 与 `77-97` 两段四个 catch（RestClientResponseException/ResourceAccessException/RestClientException/RuntimeException）同形重复。建议抽 `callStage(stage, Supplier<Map<?,?>>, rejectedCode)`。
- **J2｜P3 possible Duplicated Code（跨文件）**：`HttpDingTalkProbeClient.java:28-43` 的 `API`/`OAPI` 常量与 `boundedRestClient()`（5s/15s）与 `HttpDingTalkClient.java:32-33,46-51` 完全一致。建议抽共享常量+传输工厂；两个客户端仍应分立（有/无 token 缓存）。
- **J3｜P3 possible Data Clumps**：`(appKey, appSecret)` 两个 String 从 `IntegrationDtos.java:15` → `DingTalkConnectionProbeService.java:27` → `HttpDingTalkProbeClient.java:51` 一路同行，而 `DingTalkStoredCredentialReader.java:28` 的 `StoredCredentials` 已是同一对值。建议新增 `DingTalkCredentials` 记录并传一个值。
- **J4｜P3 possible Duplicated Code / 语义漂移**：复用判定前端 `DingtalkConnectionProbe.vue:61-69` 用 `Boolean(appKey.value)`，后端 `DingTalkConnectionProbeService.java:37` 用 `hasText`；纯空白 AppKey 时前端放行按钮、后端 400。建议抽 trim+空判断纯函数并配单测。
- **J5｜P3 Mysterious Name（测试）**：`DingTalkConnectionProbeIntegrationTest.java:70` 名为「非法策略不动旧值」，:104-116 又断言保存密钥复用；名不副实，建议拆分（同文件 :113-114 还混用全限定 `assertThat`，而 :3 已静态导入）。
- **J6｜P3 possible Speculative Generality**：`SettingsView.vue:214` `DingTalkConfig & { appSecret: string }` 与 `integration.ts:19` 的 `appSecret?: string` 重复声明；建议直接用局部表单类型。
- **J7｜P3 possible Duplicated Code**：`SUPER_ADMIN` 字面量重复于 `dingtalk-connection.spec.ts:6`、`account-audit.spec.ts:11`、`dingtalk-roles.spec.ts:14`；建议 helpers.ts 导出一次。

#### 已核对、未判为问题

- 凭据保密：`HttpDingTalkProbeClient.java:141-146` 只记 stage+code，响应仅 success/code/message，vendor 正文不入日志/响应、不入审计（符合《审计与 AI》）；探针不写任何行（《事务与失败》）。
- 事务：`SettingsService.java:218-221` `putAll` 自带 `@Transactional`（self-invocation 由外层事务覆盖），校验先于写入，网络在事务外。
- 权限：新端点用 `Permissions.INTEGRATION_MANAGE`（`IntegrationController.java:137`）；请求 secret 走 body，不进 URL。
- SFC：`DingtalkConnectionProbe.vue` 123 行；`SettingsView.vue` 613→467（基线只缩）。
- 空白判断：新代码用 `hasText`；`IntegrationController.java:118` 保留 `isBlank` 属存量，baseline 计数仍 3、脚本通过（不计违规）。
- fake=true 仅 test/e2e profile（`application.yml:122-125`、`application-e2e.yml:26-28`），生产默认 `HttpDingTalkProbeClient`。迁移 0 条，无编号冲突。

#### 计数

Hard：P1=0，P2=1，P3=0。Judgement：P2=0，P3=7。
最坏项：H1（P2，E2E `trackBrowserErrors` 复制，规则明文、工具未覆盖）。
未跑测试/构建/网络，未验证 full verify 与真实 PG/租户/手机；本报告不代主控宣称放行。
## Spec

### 07 Spec 轴只读评审（原始）

2026-11-13。源：dingtalk-t07-review-snapshot @ 64a8885cefda7782608d1a78a1a6f664cdf2f32a（工作树干净）；基点 e7d0bfc；命令 `git diff e7d0bfc...64a8885`（34 路径）。开工前资源：物理内存空闲 6.49GB/15.73GB，无并发压力。全程只读源，未跑测试/构建/网络。权威：spec.md 89–95、155–160；issues/07；t07-wire-contract.md；t07-probe-plan.md。

#### 逐项符合（无缺陷）

1. 测试连接用未保存参数、换 token 后读 dept_id=1：DingTalkConnectionProbeService.java:27-43、HttpDingTalkProbeClient.java:54-86，独立适配器无常驻缓存（:22-48）。符合 spec.md:158。
2. 不写库/不发通知/不落审计：集成测试 204-235 实测库计数与配置不变；保存原子化 Controller:105-133（putAll 有 @Transactional，SettingsService:218）。
3. 权限：Controller:136-142 仅 integration:manage；集成测试 157-174 教务/老师 403；SettingsView.vue:45 整卡隐藏，Spec 组件 spec 断言隐藏且零请求。
4. 空 secret 复用边界（非空优先、显式 useSavedSecret、appKey 严格相等、有保存密钥）：Service:31-41；测试 118-154 覆盖含空白变体与空保存密钥。UI 提示与请求一致（DingtalkConnectionProbe.vue:61-69）。
5. 结果码与固定中文：DingTalkProbeCode.java:8-12；失败 200 且不含凭据（测试 177-191）；UI 固定成功说明（组件 49,87）。符合 wire-contract“不显示供应商正文”。
6. 输入变更后结果失效、loading 独立于保存：组件 71-74、SettingsView.spec 新增 231-243 断言测试期间保存未被锁。
7. 手册：三要素齐备——H5 微应用/权限/可见范围/参数/测试/预演（dingtalk-school-setup.md:19-70）、官方链接（:23,31,32）、mermaid 流程图（:7-17）、实机清单（:73-85，明确由学校在真实租户手机执行）、真实系统截图。我实看 connection-unsaved/rejected 两图：未启用标签、未保存 AppKey、密钥打码、绿/红固定文案，与图注一致。

#### 发现

P3-1（c 行为偏差）HttpDingTalkProbeClient.java:66-68：token 阶段任何 4xx 一律 TOKEN_REJECTED，故 429 限流、404 端点不符也显示“未能使用本次 AppKey/AppSecret 获取访问令牌”。与 wire-contract 失败阶段分型意图有偏差，学校可能据此误重置密钥。
P3-2（c 部分行为）探测最坏 5s+15s×2=40s（:39-41），前端默认 30s（frontend/src/api/http.ts:18），慢网下客户端先中断，只得到通用“无法连接服务器”，丢失阶段原因；同类钉钉调用已用 180s（http.ts:14）。

#### 不计数观察

- 90018/60020 说法有内部研究出处（t07-token-department-primary-research.md:20-21），离线无法独立复核，未计缺陷。
- README、install-single-user 属手册入口/边界文案，文档级、不越代码范围。Fake 的 `fake-probe:` 约定仅在 fake=true 生效，不构成生产路径。
- 08 准备文档未计；实机/租户/手机/真实 PG 及 full verify 未执行，非 07 规格缺陷，也不据此放行。

#### 计数

P1 0；P2 0；P3 2。最坏项：P3-1，限流/端点错误被表述为凭据问题（仅提示，未断言）。未发现缺失或范围外功能新增；本轴不宣称 07 放行。
## 主控逐项处置

|项|主控判定|执行边界|
|---|---|---|
|H1 P2 公共helper复制|确认。两个spec的同名错误收集函数确实相同，违反CODING_STANDARDS:37，不因脚本未拦而豁免。|上收helpers.ts导出trackBrowserErrors，03登录与07探测spec统一import；只移动原行为，不吞console错误，不改断言力度。|
|J1 P3 两段catch|不采纳抽取建议。token/root是两个协议与响应语义不同的失败边界，4xx分别返回本阶段代码；显式安全捕获便于核对。|不新增Supplier/跨阶段响应包装，当前没有业务规则错误；已裁定判断项，不是延期缺陷。|
|J2 P3 地址/传输配置重复|不采纳。两组固定供应商URL、5/15秒局部工厂属于小段稳定协议配置，探测与运行缓存刻意分立。|不为几个常量/构造行引入共享网络平台或触碰已验运行客户端；原P3保留为已裁定启发式。|
|J3 P3 凭据Data Clumps|不采纳。两个命名参数在单一服务→适配器的有限边界传递；StoredCredentials专为保存快照返回，语义并非所有未保存输入。|不为两个String增加跨层凭据类型或改DTO/公开接口，当前映射已有HTTP契约验证；不是延期缺陷。|
|J4 P3 空白输入语义|采纳。UI应把仅空白值视为空，避免放行纯空白key/把空白secret错误当本次非空输入。|仅用于空判断，不裁剪发送值、不裁剪原key与保存key的严格比较；同key空白secret可显式复用，不同key仍要求新secret；补公开组件红绿。|
|J5 P3 测试名/断言写法|不采纳拆分。该测试末尾probe不是额外功能断言，而是证明拒绝非法保存后私密旧secret逐值保持；GET只返回hasSecret，无法完成此证明。|保留同一原子保存场景的全部断言，不削弱或拆散密钥保全。两处全限定assertThat只是表达方式，未形成命名歧义或文档标准违反；不是延期缺陷。|
|J6 P3 表单类型交叉|不采纳。wire配置的appSecret是可选/不回传字段，编辑表单需要本地始终为string；交叉类型是在缩窄编辑状态。|不改wire类型要求服务器回传secret，不增加无谓表单抽象；既有类型修正有明确用途，不是Speculative Generality缺陷。|
|J7 P3 Super凭据重复|采纳小范围去重。root测试凭据集中helpers.ts，由三个直接重复spec引用，apiLogin默认值也可引用同一常量。|保持原值与显式身份意图，不改生产/测试账号口令、不猜或粘贴工具脱敏占位符；可直接从原文件移动字面量，参数类型保持string。|
|Spec P3-1 token阶段4xx|不确认违反规格。实际文案是“未能…获取访问令牌，请检查凭据和应用权限”，没有断言“凭据不正确”；TOKEN_REJECTED在wire约定中是失败阶段，并非已知密码错误。|保持安全阶段归类。404/429同样未取得令牌，现有提示未虚构唯一原因；无需本票新增限流码或更细平台。原P3与原最坏项不改写，已裁定而非延期缺陷。|
|Spec P3-2 30秒前端预算|确认。两次调用各5秒连接+15秒读取，既定串行预算约40秒，默认Axios30秒过短。|仅本探测POST设置60秒timeout，保留全局30秒及同步180秒；组件检查外部请求预算大于40秒，避免耗时真实sleep。|

原始轴内计数保持：Standards Hard P1=0/P2=1/P3=0，Judgement P1=0/P2=0/P3=7（原最坏H1 P2）；Spec P1=0/P2=0/P3=2（原最坏P3-1）。主控仅确认H1、采纳J4/J7、确认Spec P3-2，其他按上表裁定，不跨轴合并评级。

单一fixer限前端helper/探测输入/请求预算和其测试；后端业务源不改，不重跑已通过的后端组合。定向组件/静态检查后，主控串行跑直接受影响03/07/账号审计/角色E2E组并保留源清单、退出码与截图；两轴固定修复源独立增量复核。最终8票full verify仍留到08和全规格复查之后，未push/部署。

## 首轮增量复查与主控判定

固定增量64a8885...b253112，修复源独立合入2ec7608，全部树相同。原轴增量全文见t07-standards-delta-raw.md与t07-spec-delta-raw.md：Standards H1/J4/J7关闭，新增Hard P1/P2/P3均0；新增Judgement仅J8 P3。Spec原P3-2关闭，新增P1/P2/P3均0；原P3-1接受主控裁定，未提供新证据。既有被裁定的判断项原等级仍保留。

主控不采纳J8的生产常量抽取方案：测试中的40秒门槛是独立外部约束，若与实现常量一起回引比较，会让错误预算跟着实现一起变绿；三个请求断言中的60秒是在外部HTTP seam核对本票选择的请求配置，不是生产规则重复。只为此引入全局HTTP导出/修改mock，收益不足。新增P3原数保留，已裁定非延期缺陷，不开跟进票。

主控实际四组浏览器首轮10通过/1失败、退出1、源码一致；失败为01旧spec对“账号”的模糊定位同时匹配“账号审计”，不是运行权限错误。保留原log、trace及失败PNG；只把该spec的正/负导航断言改为精确href=/settings/users，未用first绕过、未删除审计入口、未更改权限或断言目标。第二轮四组11通过、退出0、源码一致；当前两张探测PNG与手册逐字节相同，角色三选项截图也已主控实看。实际运行记录见t07-main-acceptance.md。

最后新增仅这两行E2E定位修正，仍需原两轴固定新源做极小补充复核；在此完成前07不放行，08不施工。原轴各自最坏项不被跨轴合并或重排。

## 2026-11-13 主控最终07闸门

固定最终审源016859bc35a31f2cf5c93e2280e395cbad2b6d62。补核见t07-standards-final-supplement.md、t07-spec-final-supplement.md：两行精确href仍保留超管正向与教务负向权限断言，两轴新增明确问题均0；原始计数和已裁定建议保持，不将判断建议伪称修复。受影响四组E2E最终11通过，POST_COMMIT_SOURCE_IDENTICAL=1；既有后端35及组件24的证据范围与失败历史不变。主控批准07为implemented，进入08；全量后才done。真实租户/手机/PG、全量及部署尚无通过证据。
