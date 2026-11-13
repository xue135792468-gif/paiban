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

## 最终修复源增量复查与主控裁定（2026-11-13）

原候选与原始发现均保留，上文的“未修”是be48fc0当时状态。第一批增量见whole-standards-first-delta-raw.md与whole-spec-first-delta-raw.md；Standards J5关于两个spec未消费错误数组的负面结论已由原轴自行撤回，见whole-standards-first-delta-corrigendum.md，不增加冗余断言迎合误报。

Standards：H1/J1/J2/J3已关闭；Hard各级0，Judgement仅J4 P3，按此前裁定不采纳、非延期、未伪称修复，最坏J4 P3。最终手机delta新Hard0、新Judgement0，完整原文如下。
# Standards 手机导航 delta 复核（d7ee6ff…52cbf13，独立只读）

- 源 `E:/排课系统/.worktrees/dingtalk-mobile-nav-review-snapshot`，HEAD `52cbf131382f769a25dc5e7b781ad4f8abcbebce`，tracked clean，未移动 d7/be48；未跑测试/构建/网络，未 stage/commit。delta 16 文件，源码仅 6：`MainLayout.vue`、新 `MainNavigationMenu.vue`、`navigation.ts`、`navigation.spec.ts`、新 `dingtalk-mobile-navigation.spec.ts`、`dingtalk-acceptance.spec.ts`；无后端改动、无迁移。
- **新 Hard：0**（P1/P2/P3 全 0）。新 spec 复用 `trackBrowserErrors/installFakeWindowDd/apiCall/capture`，无新增内联 collector、无自写登录/下拉，未复制公共交互。
- **新 Judgement：0**。`MainNavigationMenu` 是两处真实 caller（aside:11-16、drawer:64-70）的共享实现，不属 Middle Man；两处传同一 `visibleGroups`+`activeMenu`，差异只在 `menuTestId` 与 tour 锚点，符合单一来源。
- **M1 产品修复核对无问题**：`MainLayout.vue:335-341` 在 ≤900px 隐藏 `.aside` 并显示 `.nav-toggle`（base `display:none`，`data-testid=mobile-nav-toggle`，aria-label 打开导航）；drawer `destroy-on-close`、`direction=ltr`、`mobile-layout-menu`、`with-tour-anchors=false`，桌面保留 `layout-menu` 与 `data-tour`（tour 选择器仍唯一）；`@select` 与 `watch(route.fullPath)` 双保险收起属刻意冗余，不致重复渲染，不升级。≤520px 对 `.page-title/.user-name` 做 96px 省略，不引入横向滚动。
- **resolver**：`navigation.ts:111-118` 仅在「有 `substitution:submit` 且非完整 `WORKBENCH_MANAGER_PERMISSIONS`」时把 `/substitution` 指向既有 `/substitution/requests`，未新增路由、未改 defaultLogin/权限；完整管理组合与管理员仍进工作台（`navigation.spec.ts:71` 钉住）。教师保留 `assignment:view:own`，故「数据管理」入口合法，测试只断言其可见，未反向改权限。
- **e2e 断言只增不减**：原 acceptance 由 `page.goto` 改为真实「打开导航→调代课」并断言 URL 与抽屉收起，后续断言原样；新 nav spec 断言默认落 `/workbench/timetable`、aside 隐藏、toggle 可见、无设置/规则/AI 入口、往返与 `errors` 空，清理仅关停自建账号与配置。
- **原轴计数不变**：Hard P1 0 / P2 0 / P3 0（H1 关闭）；Judgement P3 **1**，仅 **J4**（主控不采纳、非延期、**未修复**）。本 delta 未改变任何原项。
- **边界**：browser 12-spec targeted 仍在跑，未 claim green；我不复跑。修复源对象与 1007 源 Git 56ce 一致、以及“除新增 drawer capture/合法数据入口外红 oracle 未削弱”按主控核对记录，未独立重放。full、真实 PG/钉钉租户/手机仍未验证，**不宣称整规格通过**。


### Spec最终手机增量

本增量新增P3行为提示1项（P0/P1/P2均0）：自定义submit+approve但缺完整管理权限组合的角色现在直接落requests，原待审批页签仍可用。主控采纳交付说明，已写入t08-main-acceptance.md；预置三角色权限不变。没有未决规格硬缺陷，最坏项为这条P3说明。原be48候选0不回写，M1由主控后续QC发现的来源保留。
# 手机导航 delta：Spec 轴（d7ee6ff…52cbf13）

审源 `52cbf131382f769a25dc5e7b781ad4f8abcbebce`（只读 WT，rev-parse 全 SHA 已核、干净）；diff `d7ee6ff...52cbf13` 6 个源文件（5 UI + 新 nav spec，另 1 acceptance spec 改真实点击）。未跑测试/构建/网络，未改源。

- 结论：新增规格缺失 0、越界 0；行为变更 1（P3）；独立计数 P0 0 / P1 0 / P2 0 / P3 1，最坏项 P3-1。
- M1 覆盖逐项对上：默认仍落本人课表（`dingtalk-mobile-navigation.spec.ts:46`，`useLoginLanding` 未改）；≤900 aside 隐藏且 toggle 显示（`MainLayout.vue:336-340`）；drawer 复用同一 `visibleGroups/activeMenu`（`MainLayout.vue:55-66`）；桌面保留 `layout-menu`+`data-tour`、手机 `mobile-layout-menu` 不带 tour（`MainNavigationMenu.vue:15、28-31`）；`destroy-on-close` + select 与 `route.fullPath` watch 双关闭（`MainLayout.vue:58、200-205`）。
- 入口解析：`resolveMenuPath` 仅 `can(substitution:submit)` 且非完整 `WORKBENCH_MANAGER_PERMISSIONS` 时去 `/substitution/requests`（`navigation.ts:111-118`）；完整组合仍 `/substitution`，单测已钉（`navigation.spec.ts:70`）。路由表、权限点、后端与布局层手机专用权限均未新增；老师「数据管理」仍由合法 `assignment:view:own` 可见（`navigation.ts:65-74`、spec 62 行）。
- P3-1：注释称「遗漏权限的老入口保持原样」，但缺少 `timetable:view` 的自定义 submit+approve 角色也会改落 requests；其「待审批」页签仍在（`SubstitutionsView.vue:49、89`），不越权，属行为微变，交付说明需注明，预置三角色不受影响。
- 边界：真实 red→green 与 12 spec 浏览器回执尚未完成，本文件不宣布 browser 转绿或 full 通过；原轴 0 不改写、不抹 M1 由主控 QC 发现的来源。


### 主控运行证据与收口

主控实际取得最终12 spec/32 passed、EXIT=0、SOURCE_IDENTICAL=1，整合后1008个源对象逐一相等，日志见logs/whole-fixes-browser-run1。M1已关闭，原整链改为真实菜单点击，不削弱后续业务断言。上述raw中“仍在跑”属于各自写作时点，最终运行以本节直接回执为准。两轴独立计数，不合并/跨轴重排；真实PG/租户/手机未验，完整verify尚待，未push/部署。

### Standards运行补核（原文）

# Standards 浏览器运行补核（browser-run1，只读收尾）

- 只读核对 `integration/.scratch/dingtalk-multiuser/logs/whole-fixes-browser-run1`：`playwright.exit.txt`=`EXIT=0`；`playwright.log` 末行 `32 passed (4.3m)`，含 `dingtalk-acceptance.spec.ts:21`（ok 4）与 `dingtalk-mobile-navigation.spec.ts:12`（ok 9），tour 两条亦 ok（31/32）。
- 源冻结：`source-stability.txt`=`SOURCE_IDENTICAL=1`，`source-hashes-before/after` 内容逐行相同且两者 SHA256 同为 `3DC566B6…6806D3`；`source-refs.txt` HEAD 前后均 `52cbf131382f769a25dc5e7b781ad4f8abcbebce`。
- 合并后源证明：`post-integration-source-proof.txt` 为 `COMMIT=7413a01…`、`CHECKED_SOURCE_FILES=1008`、`MISMATCHES=0`、`EXTRA_COMMITTED_FILES=0`、`POST_INTEGRATION_SOURCE_IDENTICAL=1`；另核 `261be4a^{tree}`=`52cbf13^{tree}`=`2fceb80a…a863`，`52cbf13…7413a01` 仅增 `.scratch/.../whole-fixes-merge-receipt.md`，属收据文档。
- `SHA256SUMS.txt` 覆盖 9 项且已存在；证据目录 `evidence/whole-fixes-browser-run1` 实有 25 个 PNG（含 drawer/phone submit/effective）。
- 结论：浏览器运行与源一致性证据自洽，支持「通过」的**定向浏览器切片**，且不影响本轴结论。
- 计数保持：**Hard P1 0 / P2 0 / P3 0**（H1 已关）；Judgement **P3=1，仅 J4**（主控不采纳、非延期、未修复），不阻止本地主控 full。
- 自纠：mobile delta 报告与聊天中的 `submission:submit` 系笔误，实际源码为 **`substitution:submit`**（`navigation.ts:113`、`constants/permissions.ts`）；原 raw 不重写，计数不变。
- 边界：未复跑测试、未重读全仓、未改源；full 门禁仍未跑，真实 PostgreSQL/钉钉租户/手机未验证，**不宣称全量通过**。


### Spec运行补核（原文）

# 浏览器实据补核（Spec 轴，只读）

核于集成 `.scratch/dingtalk-multiuser/logs/whole-fixes-browser-run1/`：`playwright.exit.txt`=EXIT=0；`playwright.log` 尾行「32 passed (4.3m)」；`source-stability.txt`=SOURCE_IDENTICAL=1；`source-refs.txt` HEAD_BEFORE=HEAD_AFTER=`52cbf131…`（运行期间源未动）。

- `post-integration-source-proof.txt`：COMMIT=`7413a01469cf6dbd3460d6c16e3709ea0cdd0666`、CHECKED_SOURCE_FILES=1008、MISMATCHES=0、EXTRA_COMMITTED_FILES=0、POST_INTEGRATION_SOURCE_IDENTICAL=1；集成 HEAD 即 7413a01，其父 261be4a 合入候选 52cbf13（receipt-only 提交，树=52 树）。
- 运行与源相符：M1 行为边界（默认本人课表、≤900 仅 drawer 入口、同 `visibleGroups/activeMenu`、桌面 `layout-menu`+tour 锚点、手机独立 testid 无 tour、点菜单/route 关闭、无越权菜单）在 52cbf13 源中即为本轮实跑对象，非事后补源。
- **保留 P3-1（采纳主控交付说明口径）**：`substitution:submit`+`substitution:approve` 但缺完整工作台权限的自定义角色，菜单落 `/substitution/requests`；「待审批」页签仍在（`SubstitutionsView.vue:49,89`），不越权，预置三角色不受影响。不改源。
- 边界：全量 `verify --full` 未跑；H2/Fake/离线手机视口不证明真实 PostgreSQL、钉钉租户或手机送达。本补核不构成这些项通过。


主控放行最终源复查，进入唯一剩余的本地完整门禁；不是部署放行。261be4a与52cbf13整树相同，7413a01多一份接收单，受检1008个源对象相同。
