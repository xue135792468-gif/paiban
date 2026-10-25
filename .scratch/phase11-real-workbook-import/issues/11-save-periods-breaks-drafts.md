# 11: 保存作息表会让已有草稿静默失效（课节 id 变了，草稿无提示）

**What to build:** 「学期与作息 → 作息表 → 保存」走的是 `replacePeriods`：删掉本学期全部 `period` 行再重建，**id 全变**。而 `draft_lesson.period_id` 没有外键（只有 `fk_lesson_draft`），于是所有已存在的草稿都指向不存在的课节，没有任何报错或提示；之后打开草稿校验会显示「所选课节不存在」，排课、发布、调代全部无法继续。

**证据（2026-10-25 云端实测）**：对 `semesters/1/periods` 执行一次保存后，`period` 变成新 id 46–85，`draft_lesson` 里 **1092 行（2 份草稿 × 546）全部悬空**，`GET /timetable-drafts/1/validation` 随之失效。已人工把 `period_id` 平移回新 id 修复，悬空归 0。

**Blocked by:** None

**Status:** done（已上线 2026-10-25）

- [x] 保存作息表前检测：同一 (day, periodNo) 保留原 period 行 id；要删除且仍被草稿引用的槽位整次拒绝（409）并回滚
- [x] 给 `draft_lesson.period_id` 加外键 `fk_lesson_period`（V24），草稿指向不存在课节在写库时被拦住
- [x] 已发布课表的学期仍然拒绝改作息（现有语义保留，含生成默认作息入口）
- [x] 集成测试：导入 → 保存作息 → 草稿校验仍通过且课节仍指向存在的记录

**实现说明：**

改动的文件：

- `backend/src/main/java/com/paiban/foundation/service/SemesterService.java`
  - `replacePeriods` 不再 `deleteBySemesterId` 后重建，而是按 `(dayOfWeek, periodNo)` 取回既有行：命中的行原地更新 label/section/startTime/endTime（id 不变），仅新增槽位插新行。
  - 差集里被移除的槽位先用 `draftLessons.countByPeriodIdIn(...)` 查引用，命中则 `ApiException.conflict` 整次回滚；未被引用的槽位照常删除。
  - `replacePeriods` / `delete` 开头改用 `semesters.findWithLockById`（`@Lock(PESSIMISTIC_WRITE)`，仓库里已有）：两次并发保存、保存与删学期不会在「引用检查」和「写入」之间交错。
  - `delete` 改为先删本学期草稿（`drafts.deleteBySemesterId`，靠 `fk_lesson_draft` 级联删课节）再删作息：新外键若排在前面，删学期会从「静默级联」变成 500。行为不变，只是把数据库原来做的级联顺序写进代码。
- `backend/src/main/java/com/paiban/scheduling/domain/DraftLessonRepository.java`：新增 `countByPeriodIdIn(List<Long>)`。
- `backend/src/main/java/com/paiban/scheduling/service/SchedulingService.java`：`moveLesson` 在 `force=true` 时也校验 `periods.existsById`，把「强制把课节挪到不存在的时段」从写脏数据（或新外键下的 500）变成 400。
- `backend/src/main/resources/db/migration/V24__draft_lesson_period_reference.sql`：外键 + 索引，注释里带 preflight 查询。
- `backend/src/test/java/com/paiban/foundation/PeriodReferenceIntegrityTest.java`：新增 7 条集成测试（全部走公开 REST，另有一条直接验数据库边界）。

迁移编号 **V24**（V23 是原最大编号，V14 不存在；票 12 不用 V24）。迁移只加约束和索引，没有 DO 块或 PG 专有语法，H2（PostgreSQL 模式）+ `ddl-auto=validate` 与 PostgreSQL 都适用。`period_id` 保留可空，未排课草稿仍合法。

上线前 preflight（任一行为空才算安全；本票不授权自动修历史数据）：

```sql
SELECT dl.id, dl.draft_id, dl.period_id, td.semester_id
  FROM draft_lesson dl
  JOIN timetable_draft td ON td.id = dl.draft_id
 WHERE dl.period_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM period p WHERE p.id = dl.period_id);
```

返回非空时 ALTER 会失败——这是有意的闸门，说明需要人工把历史悬空 `period_id` 修回真实课节，迁移不猜 id 平移。

全量 period 引用审查（`grep period_id` + 逐条迁移核对）：

| 引用列 | 外键 | 删除 period 时的保护 |
|---|---|---|
| `draft_lesson.period_id` | V24 `fk_lesson_period` 新增 | 服务层 409 + 数据库 NO ACTION |
| `timetable_entry.period_id` | V8 `fk_entry_period` 已有 | 已发布学期拒改作息 / 拒删学期（`countBySemesterId > 0`），外键兜底 |
| `substitution_request.period_id` | 无 | 只由已发布条目的 `entry.getPeriodId()` 写入；学期只要有 `timetable_entry` 就既不能改作息也不能删，故不会悬空 |
| `substitution_request.target_period_id` | 无 | 创建时 `periods.findById` + 同学期校验（`SubstitutionRequestService`），同上受发布/删除守卫 |
| `substitution.new_period_id` | 无 | 只由置换方案 `move.toPeriodId()` 写入，方案快照来自已发布条目 |
| `substitution_plan_move.from_period_id` / `to_period_id` | 无（只有 `fk_move_plan`） | 由 `PlanSnapshotFactory` 从已发布 `timetable_entry` 生成 |
| `substitution_request.entry_id` / `substitution.entry_id` | `ON DELETE CASCADE` | 条目被删时调代行连带消失，不会有指向旧 period 的残留 |

结论：唯一缺少强制一致性的是 `draft_lesson.period_id`（本票用真外键补上）。其余五列都只能从「已发布课表」派生，而「有已发布条目的学期不能改作息、不能删学期」这条既有守卫使其不可能指向被删除的 period，因此不再额外加外键（避免在上线时对历史调代数据再加一道可能失败的约束）。

绕过路径检查：

- 学期删除：修复前的顺序会让新外键把删学期打成 500；已改为先删草稿再删作息（见上），并加学期行锁。
- 学期复制：`SemesterCopyService.copyPeriods` 走同一个 `replacePeriods`。目标学期若已有草稿引用将被 409 拒绝（原来会静默破坏目标学期草稿），未新增删除策略。
- 新建学期：没有 period 行，全部新建，无影响。
- 生成默认作息：走 `replacePeriods`，同 (day, periodNo) 保留 id；只有「节次数变少且删到被引用槽位」时才 409，正是规格要求的行为；已发布学期仍 409。
- 并发保存 / 删除：学期行锁串行化。
- 并发保存 / 发布：发布只读草稿课节并写 `timetable_entry`；它要发布的槽位必然被草稿引用，而这类槽位 `replacePeriods` 一律 409 拒绝删除，所以两者不会互相破坏（无需给发布加锁）。
- 并发保存 / 导入：导入按 `(day, periodNo)` 先解析后写入；极端情况下解析后被并发删掉的槽位会让插入被新外键拒绝（500），而不是写进悬空 id。属于遗留风险，见下。

测试：`cd backend && ./mvnw -B test` → Tests run: 558, Failures: 0, Errors: 0, Skipped: 5（5 条 skip 是默认跳过的 Testcontainers/外部服务用例，含 `PostgresContainerIT`）。本机无 Docker，PostgreSQL 真库迁移只做了语法审查（`ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY` + `CREATE INDEX`，H2/PG 通用）。

遗留风险：

1. 本机无 Docker，V24 未在真实 PostgreSQL 上跑过；集成阶段若有 PG 环境，建议执行一次 `./mvnw verify -Dtestcontainers=true`。
2. 云端历史悬空引用（若有）需按上面的 preflight 查询人工修复后再部署——迁移不会自动迁移数据。
3. 导入与作息保存并发时，插入可能因新外键失败（500 而非悬空行）；没有给导入加学期锁，避免动票 12/导入链路的语义。
4. 身份按 `(dayOfWeek, periodNo)` 认定：如果用户在作息表里整体重编号（如中间插一节、后面的节次号顺移），既有行会被当成「同一槽位」改标签与时间，课节号码变化不会同步到已排课草稿。这是规格确认的语义（同「星期＋节次」保留身份），不在本票拓展。

**同类风险**：`period` 行被重建后，任何按 period_id 引用它的历史数据（草稿、调代记录）都可能悬空，需要一次全面排查。（已在本票内完成，见上表。）
