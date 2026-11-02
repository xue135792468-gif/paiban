# 07 指定当前草稿继续优化并保留锁定

Status: done
Type: task
Blocked by: 01、02
Owner: worker
日期：2026-11-02

## 目标与输入

01身份及锁定契约、02合规口径；ADR-0033与新ADR-0034；contracts.md显式来源模式。
前置票必须验收并合入后才能认领；ready-for-agent表示规格已就绪，不表示依赖已完成。
共同约束见[规格](../spec.md)、[接口契约](../contracts.md)、[执行路线](../ticket-plan.md)。

## 写入范围与边界

SchedulingService生命周期、TimetableProblemBuilder、WarmStartSeedService/相关构建器、求解Lesson与持久化映射、DTO和后端测试；不改前端。
只修改本票必要文件；发现契约冲突先由统筹统一更正，不能让多个worker各自猜接口。不得改生产数据或部署。

## 实施步骤

1. 给排课启动增加显式sourceDraftId模式（旧调用省略时行为不变），校验权限、学期、来源可编辑状态、任务匹配/引用有效性；不能回退已发布基线或最早草稿。
2. 以assignmentId + lessonIndex及内容一致性验证映射具体授课；未锁课程也从当前草稿起步，锁课以求解pin及固定教师保护，不能仅把锁课当初始解。
3. 沿用单求解线程、排队/取消和异步状态机；新结果写另一份草稿，继承对应锁定标记；原稿完全保留。
4. 源快照与授课任务在启动/执行/结果落库之间变化时保证读取一致；使用固定快照时清楚记录来源版本，不能混读。
5. 源任务变化、同科多节映射歧义、失效引用/锁定规则冲突明确拒绝；全锁无自由度明确返回；失败/取消/不足发布标准不宣称合格成功。

## 验收清单

- [x] 两份不同排列草稿下显式选择第二份，初始安排确实来自第二份；默认普通新排课策略不变。
- [x] 同任务同科多节由lessonIndex区分，原稿保留，锁定的时间/教师及新稿锁标记全保留。
- [x] 更改周课时/教师/班级或任务失效不能静默漏锁/改映射；源快照竞争不混读。
- [x] 全锁、锁与规则冲突、无可发布解、排队取消和求解失败均有准确状态，原稿未改，未增加求解线程。

## 验证与交付

定向warm start/builder/生命周期/锁定继承集成及求解测试；测试实际pin行为，不能只断言DTO字段。
每票提交Delivery：实际改动文件、分支/基点/提交、真实命令与退出码、剩余问题；全量门禁统一由09执行。
任务前检查资源；实际执行默认group/deepseek、high；机械搜证仅group/gemini3.8；算法结论由主模型负责。
遵守AGENTS/CODING_STANDARDS，不同worktree隔离；结束清理本票临时文件与进程。

## Delivery

**实现分支**：`codex/t07-source-draft`（worktree `E:/paiban-wt/t07-source`）
**基点**：`codex/draft-swap-locks`（含 01/02/04，HEAD `96838d8`）
**提交**：`feat(scheduling): optimize from an explicit source draft with locks`

### 改动文件

新增：

- `backend/src/main/resources/db/migration/V47__scheduling_job_source_draft.sql`（job 的来源快照与结果发布字段；开工前扫过所有分支/worktree，最高为 V46）
- `backend/src/main/java/com/paiban/scheduling/service/SourceDraftSnapshot.java`（冻结快照与 `SourceLesson`，按 `assignmentId + lessonIndex` 标识）
- `backend/src/main/java/com/paiban/scheduling/service/SourceDraftSnapshotService.java`（capture/JSON/任务指纹/一致性校验/全锁拒绝）
- `backend/src/test/java/com/paiban/scheduling/service/SourceDraftSnapshotServiceTest.java`
- `backend/src/test/java/com/paiban/scheduling/SourceDraftOptimizationIntegrationTest.java`
- `backend/src/test/java/com/paiban/scheduling/solver/PaibanConstraintProviderTest.java`

修改：

- `backend/src/main/java/com/paiban/scheduling/domain/SchedulingJob.java`（`sourceDraftId`/`sourceDraftVersion`/`sourceSnapshotJson`/`inheritedLockCount`/`resultPublishable`/`resultBlockers`）
- `backend/src/main/java/com/paiban/scheduling/web/SchedulingDtos.java`（`StartJobRequest.sourceDraftId` 末尾追加；`JobDto` 末尾追加五个结果字段）
- `backend/src/main/java/com/paiban/scheduling/service/TimetableProblemBuilder.java`（抽出共用 `Context`；新增 `buildFromSource`，锁定课无条件保留时段、教师取来源当前教师）
- `backend/src/main/java/com/paiban/scheduling/solver/Lesson.java`（`lockedPeriodId`/`locked`）
- `backend/src/main/java/com/paiban/scheduling/solver/PaibanConstraintProvider.java`（硬约束「锁定课节必须保持时间」）
- `backend/src/main/java/com/paiban/scheduling/service/SchedulingService.java`（start 冻结快照；run 走 `buildFromSource` 并在求解前后各校验任务指纹；persistDraft 事务化、锁保持后置检查、新稿锁继承、`resultPublishable`/`blockers`；`JobDto` 映射）
- `backend/src/test/java/com/paiban/scheduling/service/TimetableProblemBuilderTest.java`（`buildFromSource` 用例）
- `.scratch/draft-swap-locks/contracts.md`（回写 §5.1 实际契约）
- 本票面

### 关键实现口径

- 旧调用省略 `sourceDraftId` 时完全走原逻辑，`warmStartSource` 仍为 BASELINE/DRAFT/NONE，来源字段为空。
- 显式来源不复用默认热启动：启动即 `capture` 并冻结 `(assignmentId, lessonIndex)`、当前教师、时段、锁定与学期任务指纹；未锁课也以来源时段作初始解。
- 锁定由求解硬约束实际保持 `periodId`（不是只做初始解）；教师不是规划变量，构建时取来源当前教师，手工跨班换人不被任务教师覆盖。
- 新稿另建、行 id 全新、`locked` 按稳定身份继承；原稿行与 `version` 不变。
- 全锁 409、映射/引用/学期/状态问题 400 均不创建 job；运行时任务指纹变化或锁无法保持 → job `FAILED` 且零新稿；未达发布标准 → `COMPLETED` + `resultPublishable=false` + `resultBlockers`，不宣称成功可发布。
- 未新增求解线程，排队/取消沿用单线程 `schedulingExecutor`。

### 验证命令与真实退出码

```bash
cd /e/paiban-wt/t07-source
source .tools/env.sh
cd backend
./mvnw -B -DskipTests compile
# EXIT=0

./mvnw -B -Dtest=SourceDraftSnapshotServiceTest,SourceDraftOptimizationIntegrationTest,TimetableProblemBuilderTest,SchedulingIntegrationTest,PaibanConstraintProviderTest test
# EXIT=0；Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
```

- `SourceDraftSnapshotServiceTest` 14/14：正常快照（索引/当前教师/锁定计数/指纹）、JSON 往返，以及未知任务/周课时不符/索引缺失或重复/班级或课程不符/教师不存在/锁无时间/全锁/跨学期/已发布/空稿与任务指纹变化的拒绝。
- `TimetableProblemBuilderTest` 6/6：新增 `buildFromSource` 源教师与 `lockedPeriodId`、`SOURCE_DRAFT` 标签；锁定格即使被当前规则禁用仍保留，未锁禁用格不设初始时段。
- `PaibanConstraintProviderTest` 3/3：锁定课移出原时段计硬分、保持原时段 0 硬分、未锁课可自由移动（真实评分，不是只断言 DTO）。
- `SourceDraftOptimizationIntegrationTest` 8/8：显式第二份来源与锁继承、原稿/版本不变；同任务 3 节仅选中索引继承锁；默认路径无来源字段；手工换教师保留；跨学期/已发布/空稿/任务不符 400 且无 job；全锁 409 且无 job；锁定课冲突 → `COMPLETED` + `resultPublishable=false` + blockers 且时段保持；取消无新稿并验证同学期并发 409。
- `SchedulingIntegrationTest` 10/10：旧启动语义无回归。
- 按票面要求未运行全量 `verify.sh`、未起 Docker。

### 剩余问题

- 真实 PostgreSQL + Flyway `V47` 的落库验证留给 09 的全量门禁；本机只用 H2 测试档。
- 所有测试使用 H2 的 PostgreSQL 兼容模式；`TEXT`/`INT` 列类型与真实 PostgreSQL 的差异由 09 复核。
- 求解失败/任务变化路径只断言 `FAILED` 与零新稿，未做真实并发压测；并发保护仍沿用“单个求解线程 + 版本/指纹校验”。
- 结果发布判定复用 `DraftValidator.validateDraft` 与 `DraftPublishReadiness`；若后续 08 调整阻碍文案或代码，本票只保存 message 文本，需要跟随更新。
- 09 复审补充：`capture` 已改为在事务内 `findWithLockById` 读取来源草稿，避免把两个时刻的行拼进快照；07/04 测试夹具改用真实教学班 id，避免全量顺序变化时外键失败。
- 全量门禁、合并与发布由 09 统一执行。

## Comments
