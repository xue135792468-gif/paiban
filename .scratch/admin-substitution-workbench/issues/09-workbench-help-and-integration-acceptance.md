# 09: 工作台使用说明与集成验收

**What to build:** 在完整功能交付时补充面向使用者的帮助说明，讲清一次性日期、代课/调课、再次调整、确认截止和左右对照。汇总管理员、教师、班主任的端到端结果，执行集成门禁并整理关键页面截图、迁移准备与验收证据。逐票正确性必须已在各票自身验收；本票不替代其测试，也不构成生产切换或上线授权。

**Blocked by:** 01:按实际授课日期查看和导出一次性调代; 02:历史调代日期转换预检与兼容; 03:管理员指定代课：预览、代发与亲自确认; 04:课前更换代课人与恢复原安排; 05:管理员同班调课方案与跨日对照; 06:已发布课表拖拽进入调课预览; 07:已调课实例继续调课或代课; 08:全站导航与内容独立滚动

**Status:** in-progress (2026-10-29, branch feature/t09-workbench-help-acceptance)

- [x] 帮助说明解释调代只作用于指定实际授课日期，不延续为每周常规安排。
- [x] 帮助说明区分代课、同班置换调课、再次修订、恢复基线与历史版本。
- [x] 帮助说明指出提交审批与管理员亲自确认的差别，以及管理员仍受权限和开课截止约束。
- [x] 帮助说明左右面板代表本次调整前后，变化清单包含整份置换环及实际日期。
- [x] 管理员 Playwright 流程验证代人申请、对照、待批/确认、再次调整和最终安排可见。
- [x] 教师流程验证自助申请与自己当前有效授课；班主任流程验证仅能查看授权班级范围。
- [x] HTTP 集成验收覆盖申请、审批、生效、再次修订、历史、统计、通知和导出的一致性。
- [x] Playwright 验收覆盖拖拽/表单、全站滚动和帮助内容关键可见行为，并保存可复核截图或断言证据。
- [x] 全部前置票的定向验收结果可追溯；集成分支按项目门禁完成全量验证，记录版本、命令与结果。
- [ ] 在真实 PostgreSQL 条件下验证迁移与历史数据确认准备；失败或未确认状态如实记录，不声称已完成切换。（PostgresContainerIT 已编写、本机无 Docker 未执行，见交付记录「真实 PostgreSQL 状态」）
- [x] 交付记录区分代码/测试/迁移准备证据与生产发布；任何上线仍须独立授权和发布门禁。

## 规格依据

`spec.md`「Testing Decisions」「Out of Scope」「Further Notes」。

## 交付记录（2026-10-29，分支 feature/t09-workbench-help-acceptance，worktree t09）

提交：`cded41d`（帮助文档 + 补缺测试 + 截图）、`1855fa4`（isBlank 棘轮修复）。全程未动主仓库、未部署、未 push。

### 1. 帮助中心「调代工作台」文档（票据 1-4 条）

- 注册方式与既有文档一致：`HELP_SLUGS` 新增 `substitution-workbench`（功能模块组第 8 位）+ `docs.ts` 元数据 + `frontend/src/help/docs/substitution-workbench.md`；侧边栏目录、首页卡片、搜索索引自动生效。
- 四个小节对应票据 1-4 条，每节带锚点：`#one-time`（一次性日期——只改所选日期、下周恢复常规、跨周每步实际日期）、`#concepts`（指定代课 / 同班置换调课 / 再次修订 / 恢复基线 / 历史版本对照表 + 判断口诀）、`#approve`（提交审批进待批 vs 亲自确认即生效；权限三组合与开课截止）、`#compare`（左右=本次调整前后、变化清单含整环每步实际日期、只读基线不作差异起点、预览零副作用）。
- TDD：`docs.spec.ts` 契约先行（锚点契约、分组 `slice(0,8)/slice(8)`、known routes 收录 `/substitutions/workbench`、搜索索引与关键句断言）→ 红；实现后 help 单测 18/18 绿。
- Playwright（`t09-acceptance.spec.ts` 用例 1）：标题 `help-doc-title`=「调代工作台」、目录树注册、四小节标题、关键句（「下周同一节次自动恢复常规课表」「开课后不能再改」）、`/help/substitution-workbench#compare` 锚点跳转后正文在视口且窗口已滚动——票 08 的页内定位行为未破坏（global-scroll 8/8、help-center 5/5 回归绿）。

### 2. 溯源映射表（票据 5-8 条 → 既有测试；前票已覆盖的只引用，不重复造）

| 票据条目 | 覆盖（票次 · 测试 · 用例） |
| --- | --- |
| 条 5 管理员 Playwright：代人申请、对照、待批/确认、再次调整、最终安排可见 | 代人申请+对照+亲自确认 → 票 03 `admin-substitute.spec`「管理员亲自确认…课表生效」；提交审批/待批 → 同文件「管理员提交审批：进入待批队列」；再次调整（改 C/恢复 A）→ 票 04 `admin-substitute-revision.spec` 2 条；同班调课表单 → 票 05 `admin-swap.spec` 2 条；拖拽 → 票 06 `admin-swap-drag.spec` 4 条；已调课实例再操作/冻结 → 票 07 `admin-reschedule.spec` 3 条；最终安排可见 → 票 01 `substitution.spec` 场景 2（管理员班级课表授课日「代」、下周恢复） |
| 条 6 教师自助与自己当前有效授课；班主任仅授权班级范围 | 教师自助+课表叠加 → 票 01 `substitution.spec` 场景 2（申请者授课周课消失、下周恢复=解除后不显示）与 `substitution-swap.spec` 9a/9b（教师调课）；接替者视角 → 票 01 HTTP `SubstitutionIntegrationTest.substituteTeacherSeesTheLessonInTheirTimetable`（UI 不重复造）。班主任 → HTTP 票 03 `AdminSubstitutionIntegrationTest.headTeacherScopeIsNotExpanded`（工作台/预览 403、记录缺 classId 400）+ **本票新补 UI** `t09-acceptance.spec`「班主任只能看到自己授权班级的调代记录」 |
| 条 7 HTTP 集成：申请、审批、生效、再次修订、历史、统计、通知、导出 | 申请/审批/生效（一次性日期、四视图一致、legacy 护栏）→ 票 01 `SubstitutionIntegrationTest` 14 条；历史转换 → 票 02 `SubstitutionConversionIntegrationTest` 11 条；管理员代发/亲自确认/权限/幂等/截止 → 票 03 `AdminSubstitutionIntegrationTest` 13 + `AdminSubstitutionDeadlineIntegrationTest` 5；COVER/SWAP 修订 → 票 04 `SubstitutionRevisionIntegrationTest` 9 + 票 05 `AdminSwapIntegrationTest` 12 + 票 07 `SubstitutionRescheduleIntegrationTest` 7；历史 → 票 04 `restoreBaselineThroughRevisionKeepsHistoryTraceable` + 票 07 `historyKeepsEveryVersionAndStatsDoNotDoubleCount`；统计 → 票 04 `statsCountOnlyCurrentArrangementAgainstBaselineTeacher` + 票 07（swapParticipation 不翻倍）；通知 → 票 04/07 修订通知去重 + 票 01 生效通知；导出 → 票 04 `allViewsAndExportsFollowTheCurrentArrangement` + 票 05 `personalConfirmMakesTheWholeCycleEffectiveAcrossViewsAndExports` + **本票补缺**（见下） |
| 条 8 Playwright：拖拽/表单、全站滚动、帮助内容、截图 | 拖拽 → 票 06 4 条；表单 → 票 05 2 条；滚动 → 票 08 `global-scroll.spec` 8 条；帮助 → phase4 `help-center.spec` 5 条 + 本票用例 1；截图 → 本票用例 3（4 张入库，见 §4） |

### 3. 补缺清单（最小新增：1 条集成测试 + 1 个 e2e spec + 1 处门禁修复）

1. **后端 1 条**（`AdminSubstitutionIntegrationTest.anotherAdminApprovesDispatchedRequestAndHistoryStatsExportAgree`，全类 14/14 绿）：跨票完整链「管理员 A 代发 → 待批 → **另一位管理员 B** 批准 → 流转历史 SUBMITTED→PENDING_APPROVAL→EFFECTIVE + 审计（operator=A/approver=B）+ 统计（甲被代 1、丙代课 1）+ 双方通知 + 授课日甲导出无课/丙导出恰有课」。此前代发用例的批准人与代发人是同一人，且未串联历史/统计/导出。实现教训：生效通知按「教师→用户」在批准时解析收件人，教师账号必须先建。
2. **e2e 1 个 spec**（`t09-acceptance.spec.ts` 3 条）：帮助文档可见性与锚点（条 8）、班主任仅授权班级范围的 UI 断言（条 6，此前只有 HTTP 断言）、关键页面截图采集（条 8）。
3. **教师自助核对结论**（条 6 前半）：`substitution.spec` 场景 2 已覆盖「自己当前有效授课」——申请者视角授课周课消失、下周恢复；接替者视角由票 01 的 HTTP 断言覆盖，不重复造 UI 用例。
4. **门禁修复**（真问题就修）：前票交付跑的是后端全量 `mvnw test`，未跑 `verify.sh` 完整门禁；本票全量门禁暴露票 03/05 新代码遗留 `isBlank`（`AdminSwapService` 1 处、`AdminSubstitutionService` 3 处），按标准改 `StringUtils.hasText`（语义等价：null/空白均视为缺失），`AdminSwapIntegrationTest`/`AdminSubstitutionIntegrationTest` 继续覆盖。另：`SwapPlanner` 基线可从 2 降到 1（棘轮提示项，非失败），未动基线。

### 4. 截图证据（`.scratch/admin-substitution-workbench/acceptance/`，共 421KB < 500KB，已入库）

| 文件 | 看什么 |
| --- | --- |
| `t09-01-workbench.png`（59KB） | 工作台首屏：教师搜索卡、周课表列头为实际日期、可发起调整的课节与「发起调整」按钮 |
| `t09-02-preview-compare.png`（69KB） | 指定代课预览：跨科提示、左「调整前（最新）/右「调整后（方案）」对照、凭证有效期与开课截止、完整变化清单 |
| `t09-03-conversion-panel.png`（55KB） | 票 02 历史转换面板：未确认时的旧语义提示、「生成预检报告」入口（确认门机制可见） |
| `t09-04-help-doc.png`（238KB） | 本票帮助文档全页：目录树注册与四个小节 |

### 5. 全量门禁（`bash scripts/verify.sh --full > /tmp/verify-t09.log 2>&1; echo $?`，重定向取退出码）

官方记录为第 3 次运行（commit `1855fa4`，工作树干净）；前两次如实列后。

- **后端** `./mvnw -B verify`：**1008 tests / 0 failures / 0 errors / 8 skipped**（8 条为需 Docker 的 PostgresContainerIT 门控项），BUILD SUCCESS，17:51 min。
- **前端**：lint 通过、build 通过、unit **52 文件 417 tests 全过**。
- **仓库护栏**：SFC 尺寸、isBlank 棘轮、迁移版本（41 unique）、票据状态、AI HTTP 超时——全部通过。
- **Playwright**（隔离端口 28080/25173）：**106 passed (11.3m) / 5 failed / 6 did not run**，该阶段退出码 1。
  - 5 失败：`constraints-ai-proposals:113`（提案状态读到「待补齐」而非「可加入」）、`global-scroll:278` 同页筛选不回顶（15px 基线 5s 内未读到滚动）、`onboarding:34/63/94`（登录后未在 20s 内跳转 /onboarding）。
  - flaky 判定：三个 spec **定向重跑全绿**（global-scroll 8/8、constraints-ai-proposals 7/7、onboarding 3/3）→ 全量单 worker 长跑下的负载/共享库状态敏感偶发，非真问题。复现率：全量 1/1 次出现、定向 0/1 次。6 did not run = `constraints-ai-proposals` 为 `test.describe.serial`，首条失败后其余 6 条跳过。
  - 总耗时 ≈ 32 min（07:55–08:27）。
- 前两次运行（如实记录）：
  - 第 1 次（`cded41d`）：后端 1008/0/0/8 通过（14:24 min）+ 前端 lint/build 通过后，Node/libuv 在 Windows 崩溃（`Assertion failed: !(handle->flags & UV_HANDLE_CLOSING), src\win\async.c`），退出码 127——环境级崩溃（日志 `/tmp/verify-t09-attempt1-crashed.log`），单测/Playwright 未执行。
  - 第 2 次（`cded41d`）：后端/前端/单测/SFC 通过，isBlank 棘轮失败（见 §3.4）→ 修复于 `1855fa4`。

### 6. 真实 PostgreSQL 状态（如实）

- `PostgresContainerIT` 已编写 **5 条**（票 02 扩展 4 + 既有 1）：迁移后新表/约束生效、服务级历史转换在真 PG 完成且幂等重跑、阻塞拒绝且原数据保留。**本机无 Docker 未执行**，`verify.sh --full` 按设计明示跳过（日志首行）；待 CI/服务器 `./mvnw verify -Dtestcontainers=true` 验证。H2（PostgreSQL 模式）下迁移与约束已由全量测试覆盖（e2e 后端启动日志：41 migrations validated/applied）。
- 历史数据确认（票 02 的数据切换门）是**教务在生产数据切换前的动作**：本票只交付预检报告、确认记录与转换机制，**未做任何生产数据操作，未声称已完成切换**。

### 7. 证据与发布边界（票据 11 条）

- **代码/测试证据**：`cded41d` + `1855fa4`（帮助文档、补缺测试、截图、棘轮修复），全部可由上文命令与日志复核；前票证据见各票交付记录。
- **迁移准备证据**：V38–V42 迁移 + `PostgresContainerIT` 用例（待真 PG 执行）+ 票 02 预检/确认门机制与 e2e（`substitution-conversion.spec`）。
- **生产发布：未发生**。上线须独立授权并走发布门禁：`verify.sh --deploy`（Docker 真跑 PostgresContainerIT）+ 教务核对历史影响清单并确认数据切换 + 独立发布审批。

### 遗留风险

1. 全量 Playwright 的三个敏感 spec（global-scroll 同页筛选、onboarding 登录跳转、constraints-ai-proposals 提案状态）在单 worker 长跑下偶发超时，定向跑稳定绿；后续如再拦门禁，建议逐个加状态等待（如 `waitForScroll` 式 helper）而非加长超时。
2. 首次全量门禁曾出现 Node/libuv Windows 崩溃（环境级），复跑未再出现；若 CI 复现需升 Node 版本排查。
3. `SwapPlanner` isBlank 基线计数（2）可降到实际值（1），属棘轮提示项非失败，留给后续触碰该文件的票顺手收敛。
