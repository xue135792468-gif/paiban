# 01 持久化课节锁定与草稿写入口保护

Status: done
Type: task
Blocked by: 无
Owner: worker
日期：2026-11-02

## 目标与输入

规格§四、七；contracts.md 的身份、锁定、并发契约；现有 assignmentId + lessonIndex 字段。
前置票必须验收并合入后才能认领；ready-for-agent表示规格已就绪，不表示依赖已完成。
共同约束见[规格](../spec.md)、[接口契约](../contracts.md)、[执行路线](../ticket-plan.md)。

## 写入范围与边界

DraftLesson、相关DTO/Repository；SchedulingService、SwapService及草稿删除/覆盖/导入写入口；新增锁定服务/控制器、迁移和对应测试。不要修改规划器或前端。
只修改本票必要文件；发现契约冲突先由统筹统一更正，不能让多个worker各自猜接口。不得改生产数据或部署。

## 实施步骤

1. 以草稿内授课身份持久化锁定标记，默认旧课均未锁；核对各worktree/分支后分配Flyway版本，禁止先写死版本。跨草稿继承使用 assignmentId + lessonIndex，并验证源任务内容未变化。
2. 增加单节/批量锁定接口及详情字段，校验权限、学期/草稿归属、DRAFT状态；有明确课节才能上锁，已有违规不因上锁变合格。
3. 统一服务端锁定保护：移动、交换、换教师、覆盖/删除等不得绕过；整稿删除含锁课时要求先解锁；预留02/04的整单变更入口调用契约。
4. 对锁定变更和位置/任课变更使用一致的事务互斥/版本策略；不能先检查锁定再无保护地写。批量操作去重并整单成功或失败，回传实际处理数量。

## 验收清单

- [x] 旧数据读取为未锁；同科多节仅锁中选中那节，刷新后保留。
- [x] 普通编辑权限可操作；无权限、越草稿、已发布、未排时间和混合错误批次拒绝且零部分写入。
- [x] 锁定后既有单课移动、同班交换、跨班换人和删除/覆盖均不能改时间或教师。
- [x] 强制交错锁定与写课请求，不出现一边宣称上锁成功、一边仍移动的落库结果；双线程行锁测试已覆盖；真实数据库验证待 09/CI（Docker 不可用）。

## 验证与交付

定向后端锁定服务/控制器集成与迁移测试；Docker不可用时明确记录，真实PostgreSQL验证由09同提交CI/部署门禁补齐。
每票提交Delivery：实际改动文件、分支/基点/提交、真实命令与退出码、剩余问题；全量门禁统一由09执行。
任务前检查资源；实际执行默认group/deepseek、high；机械搜证仅group/gemini3.8；算法结论由主模型负责。
遵守AGENTS/CODING_STANDARDS，不同worktree隔离；结束清理本票临时文件与进程。

## Delivery

- 结论：已实现并通过定向测试；全量门禁与真实 PostgreSQL 验证按票面分工留给 09。
- 含锁草稿删除（本 worktree `codex/delete-locked-draft`）：默认 DELETE 仍拒绝含锁草稿；只有显式 `unlockLocked=true` 且必须携带 `expectedLockedCount`、并与当前锁数一致时才一并移除锁定并删除，成功写 `DRAFT_DELETE` 审计；`DraftLockIntegrationTest` 新增 6 个用例覆盖直接拒绝、缺少确认数量、锁数过期（含当前无锁）、确认删除与无锁删除，E2E `draft-locks.spec.ts` 覆盖确认框文案（含「无法恢复」）、取消后草稿与锁定均保留、确认后 404。
- 列表页删除（本 worktree `codex/list-locked-delete`，分支 `codex/list-locked-delete`，基点 `main` 4444477）：删除确认抽成共享 `useDraftDeletion`，草稿编辑器与排课列表共用同一文案与请求参数；`DraftDto` 新增 `lockedCount`，列表与详情同步返回，列表行不再直接 DELETE，含锁草稿同样弹出「解锁并删除」并携带 `expectedLockedCount`，取消不发请求，失败刷新列表以更新锁定数。测试：`DraftLockIntegrationTest` 新增列表/详情锁数（锁定 1 节与解锁后为 0），`useDraftDeletion.spec.ts` 覆盖锁定、无锁、取消、失败四种结果，`draft-locks.spec.ts` 新增列表页取消保留与确认后 API 404。
- 分支 / 基点 / 实现提交：`codex/t01-persistent-locks` / `codex/draft-swap-locks`（4353a16）/ `0d253fd`。
- 迁移编号：V45（实施前已扫本 worktree 与全部 refs/worktree，最高为 44；`scripts/check-migrations.sh` 通过）。

### 改动文件

- 迁移：`backend/src/main/resources/db/migration/V45__draft_locks_and_version.sql`
- 实体/仓储：`scheduling/domain/{DraftLesson,TimetableDraft,DraftLessonRepository,TimetableDraftRepository}.java`
- 守卫/锁定：`scheduling/service/{DraftWriteGuard,DraftLockService}.java`、`scheduling/web/{DraftLockController,DraftLockDtos}.java`
- 写入口：`scheduling/service/{SchedulingService,SwapService}.java`、`scheduling/web/{SchedulingController,SchedulingDtos,SwapController,SwapDtos}.java`
- 测试：新增 `scheduling/DraftLockIntegrationTest.java`；同步 `scheduling/{SwapIntegrationTest,SwapCandidateIntegrationTest,WeeklyNoClassPublishedAcceptanceTest}.java`、`substitution/{SwapFlowIntegrationTest,SwapApprovalReverificationIntegrationTest,SubstitutionConversionIntegrationTest}.java`（后 5 个仅修手工建课的 identity 以适配 V45 唯一索引）。
- 未改前端、规划器、生产数据、其它票文件。

### 接口契约

- `PUT /api/timetable-drafts/{id}/lessons/locks`（`timetable:edit`）
  - 请求 `{lessonIds:[...], locked:true|false, expectedVersion?:n}`；`expectedVersion` 可空，旧客户端不受影响。
  - 响应 `200 {requested,updated,locked,version}`；`requested` 为去重后的数量，`updated` 为实际变化行数，幂等调用 `updated=0` 且 version 不变。
  - 错误：403 无权限；400 空列表/未排课不能锁/非法课节 id（整批零写入）；400 已发布或已归档；409 版本过期。
- `PATCH /api/timetable-drafts/{id}/lessons/{lessonId}`：请求新增可空 `expectedVersion`；响应新增 `version`。锁课 → 409「该课节已锁定…」；已发布/归档 → 400；跨学期时段 → 400「作息时段不属于该学期」。
- `POST /api/timetable-drafts/{id}/swap`：请求新增可空 `expectedVersion`；响应新增 `version`；任一侧锁定 → 409；不再清零 `scoreHard`。
- `GET /api/timetable-drafts/{id}`：`lessons[].locked`、`draft.version`（只增字段）。
- `DELETE /api/timetable-drafts/{id}`：默认含锁课 → 409「草稿含 N 节锁定课节，请先解锁后再删除」；`unlockLocked=true` 时 `expectedLockedCount` 必填，缺失 → 400「删除含锁草稿需要确认锁定数量」；且只要 `unlockLocked=true`，`expectedLockedCount` 与当前锁数（包括当前为 0）不一致 → 409「锁定状态已变化，请刷新后重试」；一致时一并移除锁定并删除。PUBLISHED 改由统一守卫返回 400「课表已发布，发布后请走调代流程」。
- 补教室 `POST .../room-fill`：语义与响应不变；锁定只固定 period/teacher，仍可补 roomId，实际赋值 >0 时 version+1。

### 测试命令与退出码（真实执行）

1. `./mvnw -B -DskipTests compile` → 0
2. `./mvnw -B -Dtest=DraftLockIntegrationTest,SwapIntegrationTest,SchedulingIntegrationTest test` → 0（32 个用例，0 失败；未触发 surefire 分叉问题，未用 `-DforkCount=0`）
3. `./mvnw -B -Dtest=SwapCandidateIntegrationTest,DraftRoomFillIntegrationTest,WeeklyNoClassPublishedAcceptanceTest,SwapFlowIntegrationTest,SwapApprovalReverificationIntegrationTest,SubstitutionConversionIntegrationTest test` → 0（41 个用例）
4. `./mvnw -B -Dtest=PeriodReferenceIntegrityTest,DraftPublishReadinessIntegrationTest,TimetablePublishIntegrationTest,WeeklyNoClassIntegrationTest,AdminSubstitutionIntegrationTest,AdminSwapIntegrationTest,SubstitutionRescheduleIntegrationTest test` → 0（58 个用例）
5. `bash scripts/check-migrations.sh` → 0（44 个唯一版本）

### 剩余问题

- 已补 `DraftLockIntegrationTest.aLockWriteWaitsForTheDraftRowLockInsteadOfRacingIt`：一个线程持有草稿行悲观锁时，锁定写请求确实等待，释放后才落库；版本过期/混合批次另有零写入断言。
- Docker 不可用：未跑 `PostgresContainerIT`，V45 在真实 PostgreSQL（唯一索引 + `SELECT … FOR UPDATE`）上的迁移验证待 09 的 CI/部署门禁补齐。
- V45 的 `uk_draft_lesson_identity` 要求 `(draft_id, assignment_id, lesson_index)` 无重复；本 worktree 的生成路径与测试数据无重复，但生产库上迁移前需 preflight 去重（施工单风险 3），由 09/部署执行。
- `deleteDraft` 对已发布草稿的拒绝由 409「已发布的课表不能删除」变为统一守卫的 400「课表已发布，发布后请走调代流程」；如前端依赖旧状态码需在 04/06 联调时确认。
- 规则/设置/任务变化进入一致性检查（契约 §1 末条）本票只落了 draft 行版本，扩展留给 02/07。
- `renameDraft` 未纳入写守卫（施工单未要求）。
- 并行票迁移请续 V46；本票未改前端，前端需按契约消费 `locked`/`version`/`expectedVersion`。

## Comments
