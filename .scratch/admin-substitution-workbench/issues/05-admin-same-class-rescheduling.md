# 05: 管理员同班调课方案与跨日对照

**What to build:** 管理员可从实际授课实例指定明确的目标日期和课节，在同一行政班已有课槽中求解闭合置换环，比较候选方案并查看整环调整前后对照。跨日、跨周均以实际日期呈现；可提交审批或整单确认。表单是完整入口；空格、跨班、窗口外、已开始槽位和无解目标明确说明原因且不改变课表。

**Blocked by:** 03:管理员指定代课：预览、代发与亲自确认

**Status:** in-progress (2026-10-29, branch feature/t05-admin-same-class-rescheduling)

- [ ] HTTP 接受明确目标日期和课节并返回候选方案的实际日期；不会将目标改成相同星期的另一周。
- [ ] 在非空同班目标槽可得到二人环及多人成环候选；移动保留课程、当前教师、教室、班级每天节数和整组课程集合。
- [ ] 跨日/跨周方案的每一步均显示实际日期、课节和教师；选定候选的整环变化完整列出。
- [ ] 左右对照对象、视角和日期范围联动；来源格、目标格、教师变化高亮，清单与课表对应同一方案。
- [ ] 切换方案、教师或受影响周后仍可查看完整环；基线参考只读且不改变本次差异起点。
- [ ] 提交审批后原课表继续生效；有权管理员确认后整环全部生效，失败时整单不变。
- [ ] 空格、跨班、跨学期、窗口外、超学期及已开始目标均被拒绝并说明原因；不会静默替换用户目标。
- [ ] 无解时显示原因并保留原安排；跨日范围遵守来源日起配置窗口及学期边界。
- [ ] 每次最多返回三个候选；保留三个排序预设的既有语义、默认 7 天窗口及 1–30 天配置范围，覆盖同日优先与跨周边界。
- [ ] 方案仅修改其列出的日期实例；后续周常规安排不变，教师/班级/教室/全校视图及导出一致。
- [ ] Playwright 覆盖明确选择目标日期与课节、检查完整环对照、提交审批和整单确认。
- [ ] 批准时独立重验：普通硬规则只拒绝新引入违反，教师/班级/教室同刻唯一及每天节数等核心不变量仍绝对检查，失败整单零生效写入。

## 规格依据

`spec.md`「代课与调课」「对照、清单与预览生命周期」「提交审批、亲自批准与并发」。

## 交付记录

2026-10-29，分支 `feature/t05-admin-same-class-rescheduling`（已并入 `feature/admin-substitution-workbench`，无文本冲突，两边语义共存）。提交 `4ba63ee` + 合并 `1e3de83`。

### 实现摘要

- `SwapPlanner.Request` 新增可空 `targetDate`（4 参构造器保留）：管理端原样使用明确日期并检查方向/窗口/学期/星期；旧教师自助路径传 null 继续按星期推算，`SwapFlowIntegrationTest` 全绿。
- 新建 `AdminSwapService`（不动票 03 的 `AdminSubstitutionService`，与票 04 可叠加）：swap-targets（同班目标槽）、生成候选（≤3，落库为无申请的 PROPOSED 方案，取消预览/切换方案/无解零申请）、SWAP 预览凭证（复用票 03 的 `SubstitutionPreview` 合同：绑定 planId + 快照哈希 + 环内最小开课截止 + ≤30 分钟）、提交审批（进待批，旧课表继续）与亲自确认（同事务创建申请后调用 `SubstitutionPlanService.approvePlan` 原路径——学期锁、哈希重验、`PlanVerifier` 独立校验、整环生效行、审计与通知，失败整单零写入）。
- 本单截止 = 环内所有被改变实例「当前与拟生效」开课时刻的最小值（`LessonDeadline.earliest`）；锁后重读时钟。
- `SubstitutionPlanService` 的时间源从 `Instant.now()` 统一为注入的权威 `Clock`（方案 TTL 与时间戳）；`approvePlan` 对无申请方案加防御性拒绝。
- `TimetableViewService.overlayMap` 修正：调课环落点日可以早于来源日（明确目标日期使反向移动成为现实），旧的 `effective_date BETWEEN` 裁剪会漏掉「来源日在查询日之后、落点日=查询日」的移入行；改为全学期生效行 + 实例日期精确匹配（新格式）与原持续覆盖语义（旧格式）。
- 迁移 V42：`substitution_request.target_date`、`substitution_plan.request_id` 放开非空、`substitution_preview.plan_id` + `substitute_teacher_id` 放开非空。
- 前端：`SwapPlannerPanel`（目标日期 + 目标课节下拉 + 候选卡片 + 无解/空格原因）、`SwapCompare`（受影响日期逐日左右对照、班级/教师视角切换（`GET .../swap-compare`）、完整变化清单、只读基线参考、提交审批/亲自确认）；工作台选中课节后先选动作（指定代课 / 调课），票 04 的恢复基线按钮保留在代课分支。

### 验收清单逐条证据

- 明确目标日期并回传实际日期、不改成同星期另一周：`AdminSwapIntegrationTest.generatesPlansForAnExplicitTargetDateAndReturnsActualDates`（targetDate 回传周五、moves 落点=传入日期）；纯单测 `explicitTargetDatePlansTheCycleOnThatExactDate`（明确下周三，落点=下周三而非本周三）与 `sameWeekdayNextWeekIsNotMistakenForTheOriginalSlot`（同星期下一周不误判为原槽）。
- 非空同班目标槽的二人环与多人环：`offersAMultiTeacherCycleWhenTheTwoTeacherPathIsBlocked`（二人环被阻断后返回 3 人环候选）；纯单测 `multiTeacherCycleWorksWithAnExplicitTargetDate`；二人环 `generatesPlans...`（cycleLength=2）。移动保留课程/教师/教室与每天节数由 `PlanVerifier` 断言（`PlanVerifierTest` 8 条全绿）。
- 跨日/跨周每步显示实际日期与教师：候选 moves（fromDate/toDate/fromPeriodLabel/toPeriodLabel）与预览变化清单（`previewShowsWholeCycleCompareAndChangeListWithZeroSideEffects` 中 changes 两条的 fromDate/toDate 精确断言）。
- 左右对照联动、来源格/目标格高亮、清单与课表同源：同上测试（days 两日左右网格、`changed=true`、右侧周三第 1 节 entryId=数学条目）；教师视角切换走 `swap-compare` 端点（同测试内 teacher 视角断言）。
- 切换方案/教师/受影响周仍见完整环、基线只读：候选各自独立预览（服务端按 planId 读 moves 无状态）；SwapCompare 逐日展示全部受影响日期；基线参考为只读折叠面板（view=baseline）。
- 提交审批旧课表继续；确认整环生效；失败整单不变：`submitApprovalKeepsTheOldTimetableUntilTheApproverApprovesThePlan`（待批零生效行、普通批准入口可批）；`personalConfirmMakesTheWholeCycleEffectiveAcrossViewsAndExports`；`snapshotChangeBeforeConfirmRejectsTheWholeCycle`（409 且环内三条目零生效行）。
- 空格/跨班/跨学期/窗口外/已开始拒绝并说明原因：`rejectsAnEmptyTargetSlotWithAReason`（400 空堂）、`rejectsTargetDatesBeforeTheSourceOrOutsideTheWindowOrSemester`（向前搜索/窗口外/学期外各 400 带文案，跨班与空格同源拒绝且文案说明）、`AdminSwapDeadlineIntegrationTest.previewCannotBeCreatedAtOrAfterTheEarliestLessonStart`（已开始 409「开课」）。
- 无解显示原因保留原安排：`noSolutionExplainsTheReasonAndKeepsTheTimetableUntouched`（NO_SOLUTION + 零申请零生效 + 班级课表不变）。
- ≤3 候选、三预设语义、默认 7 天、1–30 配置：`SwapPlanner` 取 top3 与预设比较器未改动，`SwapPlannerTest.presetsOrderTheSameCandidatesDifferently` 保留全绿；窗口边界 `explicitTargetDateBeyondTheWindowIsRejected`（7 天窗口 +8 拒绝）；生成端点 preset 参数可覆盖（settings 默认）。
- 方案只改列出的日期实例、后续周常规不变、四视图导出一致：`personalConfirm...` 断言生效行 lessonDate/targetLessonDate 成对（周三↔周五）、class（周三/周五）/teacher（张周五、王周三）/room（教室B 周三）/school 视图一致、Excel 导出 200、下周三恢复基线（substituted=false）。
- Playwright：`admin-swap.spec.ts` 两条——明确选目标日期与课节 → 二人环候选 → 整环对照（两天左右面板 + 清单两步）→ 提交审批（API 侧断言待批 SWAP 申请带 targetDate、零生效行）；亲自确认（API 侧断言两条 SWAP 生效行实例日期成对 + 班级课表周五出现语文带调标记）。
- 批准时独立重验（差分 + 核心不变量绝对检查）：确认走 `approvePlan` → `PlanVerifier.verify`（未绕过）；`snapshotChangeBeforeConfirmRejectsTheWholeCycle` 证明快照变化后整单零写入，`AdminSwapIntegrationTest.confirmRetryIsIdempotentAndParamChangesAreRejected` 证明幂等与参数改变拒绝。

### 测试数字

- 纯单测：`SwapPlannerTest` 15（新增 8 条明确目标日期）、`PlanVerifierTest` 8、`SwapPlanPropertyTest` 1、`PlanSnapshotDateSemanticsTest` 4 —— 全绿。
- 集成（本票新增）：`AdminSwapIntegrationTest` 12、`AdminSwapDeadlineIntegrationTest` 4 —— 全绿。
- 兼容护栏（既有，改后复跑）：`SwapFlowIntegrationTest` 6、`AdminSubstitutionIntegrationTest` 13、`AdminSubstitutionDeadlineIntegrationTest` 5、`SubstitutionIntegrationTest` 14、`SubstitutionConversionIntegrationTest` 11、`SwapApprovalReverificationIntegrationTest` 4 —— 全绿。
- 合并票 04 后定向：上述 7 套件 + `SubstitutionRevisionIntegrationTest` 9、`SubstitutionRevisionDeadlineIntegrationTest` 4 —— 全绿。
- 后端全量（`./mvnw -B test -DforkCount=0`，本票分支）：969 tests，0 failures，0 errors，8 skipped。（默认 fork 模式在本机会因 surefire 分叉被杀出现批量 NoClassDefFound 假失败，按 AGENTS.md 以 forkCount=0 为准，CI 全量另行把关。）
- 前端：`npm run lint` 0 错 0 警告、`npm run build` 成功、`npm run test:unit` 407 passed。
- Playwright：`admin-swap` 2 passed、`admin-substitute`（含票 04 revision 组）5 passed、`substitution-swap` 2 passed、`substitution` 4 passed。

### 遗留风险

1. `overlayMap` 改为拉全学期生效行再按实例日期过滤：调代行数量级小（学期几十行）无感知；若未来需要，可加 `(semester_id, lesson_date)` 索引查询优化。
2. 无申请的 PROPOSED 管理候选按 `request_id IS NULL` 全局清理：多管理员并发生成方案时后生成者会清掉先生成者的候选，被删方案的预览凭证消费时得到明确冲突并引导重新生成，属可接受语义，未做按操作者隔离。
3. V42 的 `ALTER COLUMN ... DROP NOT NULL` 已在 H2(MODE=PostgreSQL) 验证；生产 PostgreSQL 原生支持该语法，部署时照常以 `PostgresContainerIT` 把关。
4. 目标课节下拉依赖 `swap-targets` 按日期加载，教师视角 compare 为实时投影（无缓存）；窗口上限 30 天时单日数据量仍可控。
