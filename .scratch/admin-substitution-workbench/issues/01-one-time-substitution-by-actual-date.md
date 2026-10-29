# 01: 按实际授课日期查看和导出一次性调代

**What to build:** 为新格式调代建立可按学期、基线条目和原始授课日期识别的一次授课实例。普通申请、审批和生效链路可以只改变所指日期；最新教师、班级、教室、全校课表、求解/校验、只读 CLI 与导出按实际日期所在周呈现同一结果。旧格式继续兼容读取并保持现有行为，本票不解释或转换旧历史。

**Blocked by:** None (can start immediately)

**Status:** done (2026-10-29, 集成分支 `feature/admin-substitution-workbench`；门禁后端 1012 / 前端 417 / Playwright 117 全绿)

- [ ] 新格式实例可通过现有 HTTP 用户流程完成申请、批准和生效；仅指定日期的安排发生变化。
- [ ] 同一基线条目下一周仍按常规安排显示，未被一次性调代延续。
- [ ] 移出与移入日期各显示一次；实例身份在移动后稳定，下一周对应课节是独立实例。
- [ ] 同周期槽不同周日期可同时查询并分别显示，日期列头与实际安排吻合。
- [ ] 按最终有效教师、班级、教室查询时，被解除安排者不再看到该课，接替者只看到一次。
- [ ] 教师、班级、教室、全校视图及导出对该实例和实际日期给出一致结果。
- [ ] 求解快照、独立校验与只读 CLI 使用同一日期语义，不把后续周永久覆盖。
- [ ] Playwright 覆盖普通申请者查看日期周、提交申请及审批后课表可见变化。
- [ ] 旧格式历史读取结果与改动前一致；未发生数据转换或语义切换。
- [ ] 为实例查询和日期边界补充必要的纯算法/服务验证，使用外部结果断言，不依赖私有实现。

## 规格依据

`spec.md`「授课实例、日期读取与历史兼容」「代课与调课」「Testing Decisions」。

## 交付记录（2026-10-29，worktree t01 / feature/t01-dated-substitution-instances）

**实现概要（方案 A）：** 迁移 `V38__substitution_lesson_date.sql` 给 `substitution` 表加 `lesson_date DATE NULL`（实例原始授课日期）与 `target_lesson_date DATE NULL`（SWAP 落点实例日期），加索引 `idx_sub_lesson_date (semester_id, lesson_date)`；判定规则 `lesson_date IS NULL` = 旧格式持续覆盖，非空 = 一次性日期实例。只加列，不做任何数据转换。SWAP 落点选择「加列」而非一拆二：一行同时携带来源/落点日期，读路径按 entryId 归并的现有结构不变，且两个日期都可校验。

**关键文件：**
- 写路径：`SubstitutionEffects.createCoverSubstitution` / `createSwapSubstitution` 写入实例日期（后者补上丢失的 `move.toDate()`）；`SubstitutionRequestService.create/approve` 与 `SubstitutionPlanService.approvePlan` 流程不变，经 Effects 落库即成为新格式。
- 读路径双语义：`TimetableViewService.overlayMap`（新格式按 `lesson_date`/`target_lesson_date` 精确命中查询日，旧格式保持 `effective_date <= cutoff` 持续覆盖；between 查询不变，不会漏落点日行）；`teacherView`/`roomView` 改为「先叠加当天生效实例，再按最终有效教师/教室过滤」，顺带修正被代教师残留问题；`PlanSnapshot.latestPerEntry`/`scheduleOn` 同步双语义并新增「来源日移出」分支——SwapPlanner、PlanVerifier、swapTargets 经 `scheduleOn` 自动继承。
- 契约：`TimetableEntryDto` 增 `lessonDate`/`targetLessonDate`；CLI `SubstitutionJson` 增同名可选字段，`SCHEMA_VERSION` 保持 1（可选字段向后兼容，旧快照文件照读、缺省按旧语义）。

**验收清单逐条证据：**
1. HTTP 流程只变指定日期 → `SubstitutionIntegrationTest.oneTimeCoverChangesOnlyTheSpecifiedDate`（申请→批准→授课日 `substituted=true`/教师=代课人）。
2. 下周同槽恢复常规 → 同上（下周断言，本票先行红测试：翻转前 expected false actual true）+ 纯侧 `datedCoverAppliesOnlyOnItsLessonDate`。
3. 移出移入各一次、身份稳定、下周独立实例 → `SwapFlowIntegrationTest.swapMovesShowOnceAndNextWeekRestoresTheBaseline`（`findAll size==1`、两课节次互换、行上 `lessonDate=授课日`、下周基线恢复）+ 纯侧 `datedSwapMovesOutOnTheSourceDateAndAppearsOnTheTargetDate`（来源日移空、落点日呈现、下周基线）。
4. 同周期槽不同周分别显示 → `SubstitutionIntegrationTest.coversOnDifferentWeeksResolveIndependently`（同条目两周两次代课，各周显示各自代课人且 `lessonDate` 与查询日吻合、第三周基线）+ 纯侧 `sameSlotInDifferentWeeksResolvesToItsOwnInstance`。列头仍为周结构（period 表），周内列与实际安排吻合由上述断言覆盖；「列头标日期」的展示归工作台后续票。
5. 被解除教师不再看到、接替者恰一次 → `substituteTeacherSeesTheLessonInTheirTimetable`（teacherB 恰一节、teacherA 视角无此课；旧断言「原教师仍看到」按新语义改写）+ `roomViewFollowsTheInstanceOnItsDate`（换教室：当天新教室有/原教室无，下周双向恢复）。
6. 教师/班级/教室/全校/导出一致 → `allViewsAgreeOnTheDatedInstance`（三视图同结果、行带实例日期、授课日与下周导出 Excel 均 200），room 由上一条覆盖，SWAP 的教师视角由第 3 条覆盖。
7. 快照/校验/CLI 同一日期语义 → 纯侧 `PlanSnapshotDateSemanticsTest`（全部经公共 `scheduleOn` 断言 `DaySchedule`）+ `SubstitutionCliTest.aDatedCoverDoesNotReachTheFollowingWeekButALegacyOneDoes`（同一份下周置换方案：dated 快照 PASS、legacy 快照 TEACHER_CHANGED FAIL）；SwapFlow 全流程经 snapshotHash + PlanVerifier 在日期语义下通过。
8. Playwright → `substitution.spec.ts` 场景 2 重写课表断言：申请者授课周课消失且无「代」标记、下周课恢复（数量 +1）且无「代」，管理员班级课表授课日见「代」、下周消失（原 `getByText('代')` 会误匹配页面副标题，属弱断言，一并改为容器作用域）；`substitution-swap.spec.ts` 9b 增加第三周（窗口外）恢复基线断言，9a/9b 原有断言不依赖持续覆盖、原样通过。
9. 旧格式行为不变 → HTTP 护栏 `legacySubstitutionRowKeepsOverridingFollowingWeeks`（真实流程生效后把生效行 `lesson_date` 置空模拟历史数据：date/+7/+28 均持续覆盖，`lessonDate=null` 原样透出）+ 纯侧 `legacySubstitutionKeepsOverridingFollowingWeeks` + CLI legacy 快照用例 + 存量 `PlanSnapshotFactoryTest`（旧格式行下周仍生效）原样绿。迁移无 UPDATE，未转换任何数据。
10. 纯算法/服务验证 → `PlanSnapshotDateSemanticsTest` 四用例，只断言外部结果（`DaySchedule` 课表），不触私有方法。

**测试数字：** 后端全量 `./mvnw test` 884 run / 0 failures / 0 errors / 5 skipped（skipped 为既有基线项）；定向：SubstitutionIntegrationTest 14、SwapFlowIntegrationTest 6、PlanSnapshotDateSemanticsTest 4、SubstitutionCliTest 16、SwapPlannerTest 7、PlanVerifierTest 8、SwapFlow 相关（SwapApproval 4 / Recommendation 6 / Property 1）全绿。Playwright 定向 `substitution.spec.ts` + `substitution-swap.spec.ts` 3 例全过（合并集成分支后复跑仍全过）。前端 lint 0 错、vitest 404 passed (50 files)、build 成功。

**遗留风险与保守决策：**
- `snapshotHash` 结构未纳入新字段：新格式行落库即改变快照内容（行本身进哈希），存量 PROPOSED 方案按既有机制判 stale 需重新生成，测试已按此处理；未把 `lesson_date` 单独编进哈希，因新行 `lesson_date == effective_date` 不带来额外灵敏度。
- 周网格限制保持现状：跨周调课的落点课仍显示在查询周网格的目标星期列（与改动前一致的呈现方式），一次性语义由行上 `lessonDate`/`targetLessonDate` 与「窗口外恢复基线」表达；「列头标日期、每列解析该日实例」的完整展示归工作台界面票。
- `SubstitutionQueryService`（records/recordsCsv/stats）与 `apply()` 的 note 文案未动，旧数据解释与历史展示语义归票 02。
- e2e 种子阶段观察到偶发空课表（连跑多次中 2 次在 `solveAndPublish` 后取不到 entries，基线提交上偶发复现、与本次改动无关的重跑后自愈），疑似隔离端口下后端启动时序抖动，未在本票处理。
