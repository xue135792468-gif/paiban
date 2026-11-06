# 新功能同步简报：在「按教师」课表状态下也能排课

> 用途：供 Hark 顾问读写工作区后，与用户做 grill / 写 spec。
> 本简报只陈述**已核实的事实**（代码 + 线上实验），不预设方案。
> 日期：2026-11-06　　工作区：`E:\排课系统`　　项目：`排课系统`

## 0. 一句话需求（用户原话）

「我需要有一个在『按教师』的课表状态下也能排课的新功能。」

用户已明确：接下来会和 Hark 做 grill 或写 spec。**这一步只做背景同步，不做实现、不改代码。**

## 1. 当前线上/代码状态（已全部核实）

### 1.1 「按教师」现在是什么
- 它是**草稿编辑器里切换课表查看视角**的单选按钮，不是排课范围。
  `frontend/src/components/draft/DraftEditorControls.vue:7-8`：
  ```vue
  <el-radio-button value="class">按行政班</el-radio-button>
  <el-radio-button value="teacher">按教师</el-radio-button>
  ```
- 同一个「按教师」视角按钮也出现在导入预览 `frontend/src/components/aiImport/TimetablePreviewGrid.vue:19`。
- 全仓 `grep -rn '按教师' frontend/src` 只有 3 处（上述 2 个 + 一处无关文案），**排课发起表单里完全没有这个选项**。

### 1.2 排课发起表单现在有什么
`frontend/src/views/SchedulingView.vue:54-86`「发起排课任务」卡片，只有 4 项：
- 「排课年级」多选：`GradeScopePicker`（`frontend/src/components/scheduling/GradeScopePicker.vue`），`data-testid="grade-scope-picker"`
- 「草稿名称」文本
- 「最大求解时长(秒)」数字
- 「开始排课」按钮（`v-if="canRunScheduling"` + `:disabled="isJobRunning"`）

提交（`SchedulingView.vue:528-543`）：`POST /scheduling-jobs?semesterId=X`，body `{ draftName?, maxSeconds, gradeIds: number[] }`。

### 1.3 后端契约
`backend/src/main/java/com/paiban/scheduling/web/SchedulingDtos.java:24-29`：
```java
public record StartJobRequest(
        String draftName,
        @Min(1) @Max(600) Integer maxSeconds,
        Long sourceDraftId,
        List<Long> gradeIds) {}
```
- **没有 `teacherIds` / 教师范围字段**。
- `gradeIds == null || empty` ⇒ 全校；非空 ⇒ 只排选中年级。
- 年级校验 `SchedulingService.java:221-236`；范围语义 `SchedulingDtos.java:23`。

### 1.4 按年级排课的实现（可复用的既有机制）
票 12 `.scratch/product-trim/issues/12-schedule-by-grade.md`：
- 未选中的年级课节**不移除**，而是从来源草稿/已发布基线生成「固定 lesson」，用 `lockedPeriodId` 硬钉住，仍放进**同一个** `TimetableSolution`；
- 这样共享教师的「每天最多 N 节 / 连续不超过 N 节」按**全校总账**计算，而不是每个范围单独算；
- 未选范围若既无来源草稿也无已发布基线 ⇒ 同步 409：
  `TimetableProblemBuilder.java:40`「其他年级还没有已排课表，无法保持不动；请选择全部年级，或先导入/发布其他年级」；
- 相关实现：`TimetableProblemBuilder.java:83-88, 126-130, 224-226, 273-302`；`SchedulingService.java:155, 166-182, 301-304`。
- V51 迁移给 `scheduling_job` 加了 `grade_ids_json` / `diagnostics_json`。
- 测试：`backend/src/test/java/com/paiban/scheduling/ScheduleByGradeIntegrationTest.java`（6 个用例）、`frontend/e2e/schedule-by-grade.spec.ts`。

### 1.5 收敛规格的边界（重要约束）
- `.scratch/product-trim/spec.md:132-137`：自动排课**只增加了年级范围**；发布仍整校一次；不引入课表版本/生效日期等新概念。
- `.scratch/product-trim/spec.md:162`：**「自动排课入口（高级）」被列为「冻结」**（代码留着、不再宣传/不进主导航），主线是「导入真实工作簿」。
- User Story 37（`spec.md:74`）：「自动排课时只选一个年级」；38「其他年级已排的课保持不动」；39「共享教师数量规则按总账」；40「排不出时知道卡在哪位教师」。
- 票 12 Notes（`issues/12:16`）：「不改发布模型；只改求解范围与教师总账」。
- `spec.md:201`：本 spec 不引入新概念。

## 2. 线上真实实验（2026-11-06，主代理已跑，已清理）

公网 `https://paiban.xhyzzz.dpdns.org`，学期 1、年级 1（八年级）、48 位教师、546 节已发布课表、Flyway 55。

| 实验 | 请求 body | 结果 |
|---|---|---|
| 绿对照（按年级） | `{"maxSeconds":5,"gradeIds":[1]}` | 200 → job 16 `COMPLETED`，`0hard/0soft`，546 节，`gradeIds:[1]` ✅ |
| 红对照（假想按教师） | `{"maxSeconds":5,"teacherIds":[1]}` | **200 → job 17 `COMPLETED`，但 `gradeIds: []`**：后端**静默忽略** `teacherIds`，当成全校排课 |

结论：**「按教师排课」从未实现**；即使前端硬塞教师 ID，后端也不认识。

清理状态：job 16、17 均 COMPLETED，无 RUNNING/PENDING；只生成草稿 18、17，**未发布、未改动已发布课表、生产数据未变**。

## 3. 顺带发现（与本需求无关，但别漏）
- `GET /api/scheduling-jobs/current` 返回 500：路由把 `"current"` 当 `Long id` 解析抛 `NumberFormatException`（`SchedulingController.java` 里 `GET /{id}` 与 `/current` 冲突）。独立小 bug，未修。
- 系统当前「自动排课」= 高级/冻结功能；主线是导入工作簿 → 草稿 → 发布。
- 已上线版本含票 15/16 物理删除：无教室体系、规则目录 18 类、无审批链/修订链、无独立 undo 端点（撤销调代 = 工作台「恢复基线」）。

## 4. 需要 grill 澄清的核心问题（建议 Hark 从这里切入）

> 用户说「在按教师的课表状态下也能排课」，有多种可能语义，**必须先定清楚是哪一种**，否则 spec 会写偏：

1. **「范围」还是「入口」？**
   - A：新增一种**排课范围维度** `teacherIds`（只重排选中教师的课，其余教师的课固定不动）—— 与 `gradeIds` 并列或组合？
   - B：只是想在**教师视角的课表界面**上放一个「开始排课」按钮（点了仍然排全校/按年级）？
   - C：在教师视角里选中某位教师 → 只排这一个人的课？
2. **与 `gradeIds` 的关系**：两者并存时是交集、并集、还是互斥？规格里「发布仍整校一次」是否继续成立？
3. **「其他课固定不动」的语义**：按教师范围时，未选中教师的课是否也要像票 12 那样 `lockedPeriodId` 钉住、并留在同一个 problem 里算共享教师总账？（这是最关键的算法决策）
4. **前置条件**：票 12 要求「未选范围必须已有完整课表」否则 409；按教师是否沿用？若某教师同时教多个年级怎么办？
5. **与「冻结」定位的冲突**：`spec.md:162` 把自动排课列为冻结/高级。新增这个功能是**解冻**它，还是只在草稿编辑器里加一个高级入口、不进主导航？
6. **发布模型**：`spec.md:135`「发布仍整校一次」是否不变？按教师排完的草稿是否仍整校发布？
7. **UI 落点**：加在 `SchedulingView.vue` 的「发起排课任务」卡片里（与年级并列），还是加在草稿编辑器的「按教师」视角工具栏里？两者用户心智不同。
8. **诊断输出**：票 12 有「卡在哪位教师」的 diagnostics；按教师范围是否要复用/扩展？

## 5. 关键文件索引（Hark 可直接读）

| 主题 | 路径 |
|---|---|
| 排课发起页 | `frontend/src/views/SchedulingView.vue` |
| 年级范围选择器 | `frontend/src/components/scheduling/GradeScopePicker.vue` |
| 排课 API 类型 | `frontend/src/api/scheduling.ts` |
| 草稿「按教师」视角 | `frontend/src/components/draft/DraftEditorControls.vue`、`frontend/src/views/DraftEditorView.vue` |
| 排课 Controller | `backend/src/main/java/com/paiban/scheduling/web/SchedulingController.java` |
| 请求 DTO | `backend/src/main/java/com/paiban/scheduling/web/SchedulingDtos.java` |
| 排课服务 | `backend/src/main/java/com/paiban/scheduling/service/SchedulingService.java` |
| 求解器建模（固定课节/总账） | `backend/src/main/java/com/paiban/scheduling/service/TimetableProblemBuilder.java` |
| 按年级票 | `.scratch/product-trim/issues/12-schedule-by-grade.md` |
| 收敛规格 | `.scratch/product-trim/spec.md` |
| 按年级集成测试 | `backend/src/test/java/com/paiban/scheduling/ScheduleByGradeIntegrationTest.java` |
| 按年级 E2E | `frontend/e2e/schedule-by-grade.spec.ts` |
| 全局术语 | `GLOSSARY.md`（行政班、授课任务、基线、调代） |
| 评审标准 | `CODING_STANDARDS.md`、`docs/adr/` |

## 6. 项目硬性约束（写 spec 时要遵守）

- 不引入新概念（spec.md:201）：无课表版本、生效日期、教研组、班主任、活动课、并发型。
- 发布模型不变（整校一次）。
- 求解器只有一条线程（`common/config/AsyncConfig.java`），同一时刻只跑一个任务；取消与排队逻辑绑在这个约束上。
- 求解器改动必须同时考虑：草稿校验、发布校验、调代校验共用同一套约束快照（spec.md:151-156）。
- 新增 Flyway 迁移：先查所有 worktree/分支已占用的最高版本续号（当前最高 **V55**），`scripts/check-migrations.sh` 拦重复。
- 测试接缝：好测试只验外部行为；按年级的 6 个集成用例是现成模板。
- 全量门禁 `scripts/verify.sh --full`；本机无 Docker，真实 PostgreSQL 由 CI `-Dtestcontainers=true` 覆盖。
- 部署跑册 `docs/runbooks/deploy.md`（V55 已在生产执行，Flyway 55）。

## 7. 本次同步的边界

- **只做背景同步**，不写 spec、不改代码、不部署、不连生产数据库。
- 用户接下来会直接与 Hark 做 grill / 写 spec；Hark 的答复/追问按 `consult-hark` 流程走，主代理不代为决策。
- 若 Hark 读写工作区需要授权，会在 Hark 应用里弹窗，由用户批准。
