# 票 01 代码勘察：按实际授课日期查看/导出一次性调代

勘察日期 2026-10-29，基点 main 工作区（只读核实，全部行号为当前源码实测）。补齐并修正 `../decisions.md` 的「当前代码事实与差距」表。

## 1. 数据层现状

### 表与迁移

| 表 | 迁移 | 关键字段 |
| --- | --- | --- |
| `substitution_request` | `backend/src/main/resources/db/migration/V10__substitution.sql:1-25`；V22 加 `type`/`target_period_id`（`V22__substitution_swap_plans.sql:4-6`） | `entry_id`、`effective_date DATE NOT NULL`、`status`、`approval_mode` |
| `substitution`（生效覆盖行） | `V10__substitution.sql:38-55`；V22 加 `type`/`plan_id`（`V22__substitution_swap_plans.sql:8-10`） | `entry_id`、`effective_date DATE NOT NULL`、`original/substitute_teacher_id`、`new_room_id`、`new_period_id`、`new_day_of_week`、`new_period_no`、`status` |
| `substitution_plan` | `V22__substitution_swap_plans.sql:12-30` | `snapshot_hash`、`status`、`expires_at`（30 分钟 TTL） |
| `substitution_plan_move` | `V22__substitution_swap_plans.sql:32-50` | **`from_date` / `to_date` DATE NOT NULL**——方案层已有完整目标日期 |
| `substitution_history` | `V10__substitution.sql:27-36` | 流转记录，无日期语义 |
| `timetable_entry`（基线） | `V8__timetable_baseline.sql:1-18` | 学期 + `day_of_week` + `period_no`，**无任何日期维度** |

实体：`backend/src/main/java/com/paiban/substitution/domain/Substitution.java:41-42`（effectiveDate）、`SubstitutionRequest.java:54-55`、`SubstitutionPlanMove.java:54,66`（fromDate/toDate）。

### 实例身份（学期+条目+原始日期）缺什么

- 旧格式的 `substitution.effective_date` 实际语义是「**从此日起持续覆盖**」，不是实例日期：读路径按 `effective_date <= 查询日` 过滤（见 §3）。
- SWAP 生效行**丢失目标日期**：`SubstitutionEffects.createSwapSubstitution` 只把 `move.fromDate()` 写进 effective_date（`SubstitutionEffects.java:97`），`move.toDate()` 未落库，只能靠 `new_day_of_week` 反推周几，不知道落在哪一周。
- 唯一性仅靠代码层 `findFirstByEntryIdAndEffectiveDateAndStatus`（`SubstitutionRepository.java:39-40`），DB 无唯一约束（V10 只有普通索引 `idx_sub_entry_date:60`、`idx_sub_semester_date:61`）。
- 建议候选（不拍板）：**A.** `substitution` 加一列 `lesson_date DATE NULL`（= 该笔覆盖影响的实际授课日期，SWAP 行可一拆二或加两列 from/to 实例日期）；`NULL` 即旧格式。**B.** 新建 append-only 实例表（semester_id+entry_id+lesson_date），旧表原样保留。**C.** 复用 `effective_date` 改语义，但旧数据无法与新语义区分，需另加格式标记列——本质还是 A 的变体。

## 2. 写路径

- **create**：`service/SubstitutionRequestService.java:92-196`。管理者判定=有 `SUBSTITUTION_VIEW` 权限即可代人提交（100-104，票 03 要收紧）；星期与课节一致 105-108；学期范围 109-113；**去重**：同 entryId+effectiveDate 已有 EFFECTIVE 行 → conflict（114-117），同 entryId+date 存在非 REJECTED/CANCELLED 申请 → conflict（118-124）；direct 模式当场生效 179-188；否则 PENDING_APPROVAL 189-194。
- **approve（COVER）**：同文件 198-277。SWAP 一律拒绝走方案（204-206）；代课教师忙检查=基线 entry + 已生效 substitution 的 newPeriod（219-236）；重复生效检查 237-240；**学期行锁** `effects.lockSemester` 245；`verifyCoverOrReject` 367-396（用 effectiveDate 两侧构造 PlanMove，384，交给 PlanVerifier）；成功后 `createCoverSubstitution` 263。
- **SubstitutionEffects**：`service/SubstitutionEffects.java` — 学期锁 70-73；createCoverSubstitution 75-88；createSwapSubstitution 91-109（substituteTeacherId=move.teacherId()，教师跟课走）；通知变量 139-162。事务边界=各 Service 方法的 `@Transactional`（RequestService:92/198/280，PlanService:81/189），锁与写同一事务内。
- **approvePlan**：`service/SubstitutionPlanService.java:189-250`。TTL 200-204；锁 205；**快照哈希校验** 206-212（不匹配即 stale 拒绝）；`PlanVerifier.verify` 215-226；逐 move `createSwapSubstitution` 230-232；plan/request 状态原子翻转 233-242。generatePlans 81-143（生成前删旧 PROPOSED 99-103）。
- **SwapPlanner.Request**：`plan/SwapPlanner.java:44` `record Request(classId, entryId, date, targetPeriodId)`。**目标日期推算**：96-103 `targetDate = date + floorMod(目标周几 - date 周几, 7)`，超出 `date+window-1` 报 TARGET_OUT_OF_WINDOW，超出学期报 TARGET_OUT_OF_SEMESTER——只能表达「date 当周起最近的目标星期」，无法指定具体某周某日。

## 3. 读路径（票 01 主战场）

- **`overlayMap`**：`timetable/service/TimetableViewService.java:169-185`。取 `[semesterStart, cutoff]` 全部 EFFECTIVE，按 createdAt 升序、同 entryId 后者覆盖（179-184）→ **「截止日期前最后一笔持续覆盖」的唯一定义点**。改日期语义只需把新格式记录的匹配条件从 `<= cutoff` 换成 `== 查询日`。
- 四个视图（同文件）：classView 78-85；**teacherView 87-99**（先按基线 teacherId 过滤，再并入 substitutedIn 的 entry；apply 在过滤之后 → 被代课后原教师仍保留该课、新教师额外多出一课，decisions.md 已标注）；roomView 101-114（apply 后再按 roomId 二次过滤 112，无残留问题）；schoolView 116-129。`apply` 187-229 替换教师/教室/节次，note 固定「调代生效于 <effectiveDate>」228。
- **导出**：`timetable/service/TimetableExportService.java` — grids 325-375 直接复用上述视图并透传 date；buildGrid 390-407 按 `dayOfWeek-periodNo` 落格，表头是常量 `DAY_NAMES`（62；PDF 表头 145-147，Excel 表头 241-245），**导出网格是周结构，与日期无关**；连堂合并 282-303 同样基于周格。端点见 §4。
- **求解快照**：`plan/PlanSnapshot.java` — `latestPerEntry` 158-172（与 overlayMap 同语义：`<=date`、createdAt 后者胜）；`scheduleOn` 119-156 应用覆盖；`snapshotHash` 195-215 把全部 substitution 编进哈希；`AppliedSubstitution` record 56-65。装载点 `service/PlanSnapshotFactory.java:100-106`（`findBySemesterIdAndStatus`）。SwapPlanner（74、109）与 PlanVerifier（135）都经 `scheduleOn` 取日课表——**改好 latestPerEntry/scheduleOn，校验器与规划器自动继承日期语义**；`PlanVerifier.verify` 的 before/after 日期集合来自 moves 的 fromDate/toDate（`PlanVerifier.java:126-143`，applyMoves 160-179）。
- **只读 CLI**：`substitution/cli/SubstitutionCli.java` — `SubstitutionJson` 470-482 字段与 AppliedSubstitution 对齐，**没有实例日期字段**；`SCHEMA_VERSION=1`（49），readSnapshot 199-288。新格式需要可选字段+版本策略（schemaVersion=2 或可选字段向后兼容）。
- **记录/统计**：`service/SubstitutionQueryService.java` — records 55、recordsCsv 88-106（列名「生效日期」=effectiveDate）、stats 120（直接数 EFFECTIVE 行）。
- 其他消费者：`SubstitutionEffects.variables:149-152` 查代课人；`SubstitutionRequestService.approve:222-233` 查忙档；`SubstitutionRecommendationService.candidatePool:151`。这些按「同一天同槽」查询，日期语义变化影响小，但建议核对。

## 4. 前端读视图

- 页面：`frontend/src/views/TimetableView.vue` — 日期选择器 32-38；latest/baseline 切换 40-44、203；加载 349-356（`GET /timetable/school` 或 `/timetable/{mode}/{id}`，params `view`+`date`）；导出 374-379；「调/代」tag 110-114；tooltip 321-323。
- DTO：`backend/.../timetable/web/TimetableDtos.java:10-30` `TimetableEntryDto`（substituted、substitutionType、substituteTeacherId/Name、originalTeacherName、substitutionReason、note）——**无日期列头字段**；课表格子的列头来自 period 表的 dayOfWeek，不是 DTO。
- Controller：`backend/.../timetable/web/TimetableController.java` — classView 48-61、teacherView 63-75、roomView 77-84、schoolView 86-92；导出 8 个端点 94-162（class/teacher/room/school × excel/pdf），全部已带 `view`+`date` 参数。
- 表单侧：`frontend/src/components/substitution/SubstitutionRequestForm.vue` — 日期 19-20；loadEntries 用 `view=baseline`+date 94；swap targets 116；提交 `effectiveDate: form.date` 147。`views/SubstitutionsView.vue`（117 行）+ `components/substitution/` 下 SubstitutionRequestForm/SubstitutionRecordsPanel/SubstitutionPendingPanel/SubstitutionMineTable。
- 票 01 契约改动候选：TimetableEntryDto 增加实例日期/指向日期字段；`view=latest` 语义改为「按 date 参数呈现当天实际安排」；导出端点是否需要「某日专版」由规格定。

## 5. 可复用测试

- 基类 `backend/src/test/java/com/paiban/support/ApiIntegrationTest.java:18`（DatabaseResetExtension 17、RestAssured 42-46、login/adminToken 48-65、createUser 67-77）。
- `support/ScheduleFixture.java` — solved 18-70（两班两师 12 课真实求解）；published 72-78；`publishedEntries` 85-90（view=baseline，注释明确：发布重建 id，必须按槽位/课程回查）；classEntries 92-97。
- `substitution/SubstitutionIntegrationTest.java` — prepare 44-84（published fixture + teacherA 的 entry + 日期固定 2025-09-01 那一周 + 新建代课账号）；submit 100-113；关键用例：overlay 全链 118、代课人视图 335、星期不匹配 318。**缺口**：没有「第二周同槽恢复基线」断言（现语义下周仍覆盖，正是票 01 要翻转的行为）。
- `substitution/SwapFlowIntegrationTest.java:34`（MONDAY=2025-09-01:36）：三人环整单批准 160、空槽/星期错 334、stale plan 292。同目录 SwapApprovalReverificationIntegrationTest、SubstitutionRecommendationIntegrationTest。
- 纯单测（无 Spring）：`substitution/plan/PlanFixtures.java`（MONDAY 26、builder 39、replay 112）+ SwapPlannerTest、PlanVerifierTest、SwapPlanPropertyTest；`service/PlanSnapshotFactoryTest`；`cli/SubstitutionCliTest`。
- Playwright：`frontend/e2e/helpers.ts`（seedSchool 129、seedSwapScenario 279、solveAndPublish 241、dateFor 247、classEntries 427）；`substitution.spec.ts:21`（申请→审批→课表叠加全链 UI）；`substitution-swap.spec.ts:47,79`（9a 同日调课、9b 跨周到下周）；`swap.spec.ts` 是草稿换课，与票 01 无关。种子学期日期都锚在 2025-09-01，跨周断言需确认学期范围覆盖第二周。

## 6. 实现顺序建议（最小改动路径）

1. **迁移+实体**：推荐选项 A（`substitution` 加 `lesson_date DATE NULL`，可再加 `target_lesson_date` 表达 SWAP 落点；或一行 SWAP 拆成 from/to 两条实例行）。续号从 V38 起（当前最高 V37，`scripts/check-migrations.sh` 会拦重复）。判定规则：`lesson_date IS NULL` = 旧格式持续覆盖；非空 = 一次性实例。
2. **写路径**：`SubstitutionEffects.createCoverSubstitution:75-88`、`createSwapSubstitution:91-109` 写入新字段（后者补上 `move.toDate()`）；`create` 去重 114-124 保持 entryId+date 维度即可。
3. **读路径双语义**：`TimetableViewService.overlayMap:169-185` 与 `PlanSnapshot.latestPerEntry:158-172` 改为「新格式按 lesson_date==查询日，旧格式保持 <=cutoff」；teacherView 91-99/roomView 105-113 的并集过滤改为按「当天实例」计算，顺带解决被代教师残留问题。
4. **导出/CLI**：TimetableEntryDto 加字段；导出仍走周网格（先靠 note/标记），CLI `SubstitutionJson` 加可选字段。
5. **测试**：先在 SubstitutionIntegrationTest 补「下周恢复基线」断言（红），再实现（绿）；纯侧用 PlanFixtures 补 latestPerEntry 日期用例。

**风险清单**：a) snapshotHash 含全部 substitution（PlanSnapshot.java:195-215），上线后旧 PROPOSED 方案哈希不匹配会被 stale 拒绝（PlanService:206-212），需发布说明；b) overlayMap 每请求拉全学期 substitutions，加列后注意走 `idx_sub_semester_date`（V10:61）；c) SwapPlanner.Request 无法表达指定目标日期，票 01 不动它，票 03/工作台才需要；d) 导出网格是周结构，一次性调代的呈现方式需要产品确认；e) 所有现有测试锚在学期第一周，翻转语义后「下不周覆盖」类断言可能反转。

## 7. 给后续票的备忘

- **票 02（历史转换/兼容读取）**：同一批读点——overlayMap（TimetableViewService.java:169-185）、latestPerEntry（PlanSnapshot.java:158-172）、apply 的 note（228）、SubstitutionQueryService.records/recordsCsv/stats（55/88-106/120）、CLI SubstitutionJson（SubstitutionCli.java:470-482）。旧数据一律不改写。
- **票 03（管理员代发）**：create 的管理者判定（SubstitutionRequestService.java:100-104，SUBSTITUTION_VIEW 过宽）、去重与修订语义（114-124）、direct 生效分支（179-188）；前端 SubstitutionRequestForm.vue 的 myTeacherId 门槛（90-96）与 SubstitutionsView.vue；推荐池 candidatePool 同科优先（SubstitutionRecommendationService.java:151-185）；指定目标日期要动 SwapPlanner.Request（SwapPlanner.java:44,96-103）。
