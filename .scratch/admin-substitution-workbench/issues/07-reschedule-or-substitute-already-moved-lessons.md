# 07: 已调课实例继续调课或代课

**What to build:** 课前可基于当前实际安排再次移动已调课实例，或为其指定代课；代课后仍可调课。重复修订均作为新版本预览、校验并留存历史。旧置换环中已开课的实例冻结；其余实例只有在新方案不改动任何已开始实例且满足完整不变量时才可调整，禁止按旧环反向重放形成空堂或冲突。

**Blocked by:** 04:课前更换代课人与恢复原安排; 05:管理员同班调课方案与跨日对照

**Status:** in-progress (2026-10-29, branch feature/t07-readjust-moved-lessons)

- [ ] HTTP 端到端先移动授课实例，再基于其当前日期/课节发起新调课；批准后只更新当前有效修订。
- [ ] HTTP 端到端代课后调课、调课后代课；教师、班级、教室与全校最终安排一致。
- [ ] 再次预览以当前实际安排为起点，原始基线日期已过去不单独锁死尚未开始的当前授课。
- [ ] 已开始或已结束实例不可改变；截止取本次调整前后被改变授课的最早开课时刻，按 Asia/Shanghai 服务端时钟判定，等待锁后重新读取时间。
- [ ] 旧环部分实例已开始时，冻结实例保持不动；不涉及冻结实例且合法的新方案可完成整单生效。
- [ ] 任何必须改变已开始实例、导致空堂、冲突或违反核心不变量的方案均整单拒绝，旧有效安排保留。
- [ ] 再次调整待批/驳回/过期/校验失败期间上一版本继续生效；确认后只替代本次涉及实例。
- [ ] 历史保留每次操作者、前后安排和确认时间；当前统计不累计被替代的中间版本。
- [ ] 通知覆盖前后受影响教师并集且按修订去重；解除旧安排的教师收到变化通知。
- [ ] 竞争确认与重复请求不会产生半环生效、重复通知或多份当前有效安排。
- [ ] Playwright 覆盖已移动课再次操作、含已开课环成员的预览及冻结/可调整结果。

## 规格依据

`spec.md`「课前再次调整与截止」「提交审批、亲自批准与并发」「统计、历史与通知」。

## 交付记录

2026-10-29，分支 `feature/t07-readjust-moved-lessons`（基于集成分支，含票 01-05 全部工作）。实现人：票 07 代理。

### 设计要点（ADR-0030 的 SWAP 修订扩展）

- **修订类别**（`RevisionPolicy`）：`RESCHEDULE` = 首次管理调课（无基准申请）；`RESCHEDULE_REVISION` = 以当前生效安排为基准的修订调课（调过课再调、代课后调课）。COVER 侧沿用票 04 的 `CHANGE_SUBSTITUTE` / `RESTORE_BASELINE`（「调课后代课」以 SWAP 申请为基准，走既有 COVER 修订路径）。
- **净差行不变量**：SWAP 生效行的 `lesson_date` 恒等于实例的基线来源日（首次调课 = 当前实际日；修订行继承基准 SWAP 行）。修订批准时基准链生效行转 `SUPERSEDED`，净差行（基线来源日 → 新落点）接管「来源日移出」语义——「移动后再移动」「代课后调课」的读模型整链闭合。恢复基线的环成员净差为零、不写行（`RevisionPolicy.restoresBaseline`）。
- **SUPERSEDE 范围**：SWAP 修订按「本次 moves 涉及的每个实例（条目 + 基线来源日）」替代其全部生效行——不按整个基准申请（环成员的当前行可能分属不同申请）；旧环未触及的成员（如上一次环移走的数学）保持 EFFECTIVE，绝无半环。
- **冻结规则**（纯逻辑类 `LessonFreeze`，预览生成与确认校验共用）：开课时刻 = Asia/Shanghai + `Period.startTime`，时刻已过（含恰好开课）即冻结；开始时间缺失按冻结处理（不许猜测）。`SwapPlanner.Request` 新增 `frozenSlots`（`plan/FrozenSlot`），求解器绝不把冻结槽选作环节点，来源/目标冻结给 `SOURCE_FROZEN`/`TARGET_FROZEN` 诊断；`AdminSwapService.generatePlans` 把窗口内本班已开始槽位传入。
- **COVER 行继承基准行的槽位/教室覆盖**（`SubstitutionEffects.createCoverSubstitution`）：基准是 SWAP 行时（调课后代课），COVER 行完整描述实例当前有效安排，修复「最新行遮蔽」读模型会把实例退回基线槽的语义缺口。
- **截止**：修订 SWAP 批准（`SubstitutionPlanService.approvePlan`）在学期行锁后重读时钟，按环内全部 from/to 槽最小开课时刻严格复核；预览与确认两处既有截止检查不变。首次调课行为不变（票 05 护栏）。
- **迁移**：不加 V44——V41 的 `revises_request_id`/`revision_kind` 与 V42 的 `target_date`/`plan_id` 结构对本票完全够用，未新增任何持久化字段。
- **前端**：零源码改动。工作台动作选择器对 SWAP 生效实例（当前落点日的课）天然开放「调课/代课」；修订横幅经既有 `revisesRequestId` 字段自动呈现「取代申请 #x」。`SwapPreviewResponse` 新增 `revisesRequestId`/`revisionKind` 两个只读字段。

### 验收清单证据（票据 11 条 → 测试）

1. 移动后再移动、批准只更新当前有效修订：`SubstitutionRescheduleIntegrationTest.movedInstanceCanBeRescheduledAgainAsRevision`（净差行 + SUPERSEDED + 数学保持 + 视图）。
2. 代课后调课 / 调课后代课，四视图一致：`coveredLessonCanBeRescheduledKeepingCurrentTeacher`、`rescheduledLessonCanBeCoveredAndAllViewsAgree`（班级/教师/教室/全校）。
3. 当前安排起点、基线日期已过不锁死：`SubstitutionRescheduleDeadlineIntegrationTest.baselineDatePassingDoesNotLockTheCurrentFutureLesson`（截止 = 周五落点 08:00 而非已过的周三基线）。
4. 已开始/已结束不可变 + 截止三分 + 环内最小截止：`startedInstanceCannotBeChangedByAnyPath`（代课/恢复基线/再调课三路 409、零写入）、`rescheduleConfirmRejectedExactlyAtTheEarliestStartThenAllowedAfterRollback`（恰好开课 409、回拨后同凭证可生效）。
5. 部分已开始环——冻结不动、合法新方案生效：`legalNewPlanDetoursFrozenCycleMembersAndTakesEffect`；必须动冻结/已开始槽的整单拒绝：`startedSlotAsTargetIsRejectedAtGeneration`（同日第 1 节已开始时目标即拒）+ SwapPlanner 纯测 `cycleDetoursAroundFrozenIntermediateSlots`。
6. 待批/驳回/失败期间上一版生效：`pendingOrRejectedRevisionKeepsPreviousVersionEffective`；快照变化整单拒绝（票 05 既有 `snapshotChangeBeforeConfirmRejectsTheWholeCycle` 继续绿）。
7. 历史与统计：`historyKeepsEveryVersionAndStatsDoNotDoubleCount`（修订链 `revisesRequestId`/`revisionKind`、状态流转、`swapParticipation` 不翻倍、周课量不变）。
8. 通知并集 + 按修订去重 + 「取代申请 #x」：`revisionNotifiesAffectedTeachersWithSupersededRequestOnce`（COVER 修订沿用票 04 RELEASED/ASSIGNED 模板，既有测试继续绿）。
9. 竞争与幂等：`concurrentRevisionsFromSameBaseAllowOnlyOneWinner`（两凭证同基准只一个生效、冲突后以胜者为基准重新预览、幂等重放不重复通知）。
10. Playwright：`frontend/e2e/admin-reschedule.spec.ts` 3 条（已移动课再次调课含净差行断言、调课后代课含修订横幅、旧环已开课冻结拒绝零写入）。

### 测试数字

- 本票新增：纯单测 10（RevisionPolicyTest 3 + LessonFreezeTest 4 + SwapPlannerTest 3）+ 集成 12（SubstitutionRescheduleIntegrationTest 7 + SubstitutionRescheduleDeadlineIntegrationTest 5）+ Playwright 3 = 25 条，全绿。
- 兼容护栏：票 01-05 既有 substitution 定向 88 条 + plan/cli/service 纯测 41 条，全绿（旧格式 legacyLocked、教师自助路径、直接生效语义不变）。
- 后端全量：`mvnw test -DforkCount=0` 1007 条，0 失败 0 错误（8 skipped 为需 Docker 的 PostgresContainerIT），BUILD SUCCESS。
- 前端：`npm run lint` / `npm run build` / `npm run test:unit` 全绿；Playwright 定向 `bash scripts/e2e.sh e2e/admin-reschedule.spec.ts` 3 passed。
- 本地全量以 `-DforkCount=0` 运行（surefire 分叉被杀的既有环境问题），CI 全量以默认模式为准。

### 遗留风险

- 修订 SWAP 的批准走 `approvePlan`，其截止/冻结复核目前仅对修订请求生效；首次调课的「提交审批 → 数日后批准」跨点场景沿用票 05 现状（预览与提交时已各查一次），若要覆盖需另行开票。
- 净差行的 `effective_date` 语义 = 本次操作来源日；记录/CSV 导出展示它而非基线来源日（历史追溯以申请链为准）。
- SwapPlanner 冻结槽跳过不产生独立诊断码（无解时汇总为 NO_SOLUTION）；如需「因冻结而无解」的细分提示，可在预览层补 issue 文案。
