# 12: 导入自动建班把学生人数写成 0，之后班级无法保存

**What to build:** 整本工作簿导入自动创建行政班时 `studentCount=0`；而手工建班/编辑班的接口用 `@Positive Integer studentCount` 校验，**0 会被 400 拒绝**。结果：导入出来的 14 个班，用户在「行政班」页点编辑、什么都不改直接保存就会报「参数校验失败（studentCount 必须大于 0）」。同时 0 人让「教室容量足够」约束恒真、导出名单显示 0 人。

**证据（2026-10-25 云端实测）**：`admin_class.student_count` 14 行全为 0；`PUT /api/classes/1 {"studentCount":0}` → 400 `must be greater than 0`；改 45 后 200。已临时把 14 个班改成 45。

**Blocked by:** None

**Status:** done

- [x] 导入建班使用与手工建班一致的默认人数（45），或在工作簿里读不到人数时留空并沿用默认
- [x] 校验口径统一：要么允许 0（表示未知），要么导入与手工都拒绝 0
- [x] 集成测试：导入 → 编辑班级名称并保存 → 200

**实现说明：**

根因：`ImportMappingService` 在两条建班路径上都主动写入人数。整本工作簿自动建班（`ensureClasses`）硬写 `setStudentCount(0)`；映射「新建班级」（`createClass`）写成 `plan.studentCount() == null ? 0 : ...`。两处都绕过了 `AdminClass` 自带的 `= 45` 初值，也违背了手工接口的 `@Positive` 口径。

改动（缺省值的唯一来源是实体本身，不再复制常量）：

- 两条入口都只在请求**明确给出**人数时 `setStudentCount`；缺省时保留 `AdminClass.studentCount = 45`（与 `V4` 的 `DEFAULT 45`、手工建班 `FoundationService.createClass` 一致）。
- 校验口径：`ImportDtos.CreateItem.studentCount` 加 `@Positive`，`MappingRequest.creates` 改为 `List<@Valid CreateItem>` 级联校验。于是 0 / 负数在建班前就以与手工接口完全相同的形式被拒（400 `参数校验失败`，`fields.*.studentCount` 的消息与手工保存时同一条，测试直接拿手工接口的返回值当基准比对），不会留下半个班级或新年级。
- 前端：映射步骤的人数输入 `min` 由 0 改为 1，清空输入回落到 45（此前清空会提交 `studentCount: 0`，修好校验后会变成必现 400）。

谁读这个值（用于确认修复覆盖到位）：

- `admin_class.student_count`：`FoundationService`（行政班列表/编辑）、`FoundationExcelService` 的基础数据导出（人数列）、`TimetableImportService` 导入时把它快照进 `draft_lesson.student_count`、`TimetableProblemBuilder`（排课求解的 `Lesson.studentCount`，缺班级时兜底 45）、`PlanSnapshot`/`SubstitutionCli`（调代规划快照）。
- `draft_lesson.student_count`：`ConstraintChecker` 的教室容量规则、草稿网格展示、草稿校验。它是导入/求解当刻的快照，因此源头修好后新草稿自然正确。
- `frontend/src/components/aiImport/timetablePreview.ts` 里 `studentCount: 0` 是只读预览临时渲染值，从不落库，按规格不动。

历史数据（迁移 `V25__backfill_class_student_counts.sql`）：`V4` 与 `V7` 的列都是 `NOT NULL DEFAULT 45`、没有 CHECK；而 0 无法由任何合法写入产生（手工接口 `@Positive`、Excel 导入显式拒绝 `<= 0`、实体默认 45、求解器兜底 45），所以库里的 0 只可能表示「导入没给人数」，不存在「用户明确填 0」的情况。

这条迁移的定位是**数据修复，不是改写快照语义**（与本文上一条「已有草稿保留导入当时人数」不冲突）：

- 被修的 0 是缺陷留下的空洞——本该有值却没有，不是「导入当时确实 0 人」的记录；45 是同一时刻手工接口会写下的同一个默认值，所以填 45 是补空缺，而不是改写已记录的人数。
- `admin_class.student_count` 与 `draft_lesson.student_count` 的 0 值各跑一条 UPDATE；**任何非 0 值一律不动**（真实导入/求解快照不被重算）。
- 两条 UPDATE 都只命中 0 值，重复执行安全。
- 迁移注释内附 preflight（`SELECT id, semester_id, grade_id, name FROM admin_class WHERE student_count = 0 ORDER BY id;` 与 `SELECT id, draft_id, admin_class_id FROM draft_lesson WHERE student_count = 0 ORDER BY id;`）、备份建议，以及**按 preflight 记录 id 回滚**的方法（修完以后 0 与 45 无法再区分，不能按值回滚）。
- 选择「迁移里改数据」而不是「只留 SQL 注释」的理由：线上 14 个班已被临时手工改成 45，但任何还有 0 的部署（含本地库、其他环境）都需要同样的修正，放进迁移可以随发布自动执行且可审查。
- 没有加 `CHECK (student_count > 0)`：那会把将来「允许 0 表示未知」的产品选择变成 500，口径应留在 API 层的 400。

测试（先红后绿，均走公开 REST）：

- 映射新建班级不带人数 → `studentCount == 45`，随后 `PUT /api/classes/{id}` 改名保存 200 且人数仍为 45（红：0）。
- 整本工作簿 commit 自动建班 → 两个班 `studentCount == 45`，`PUT /api/classes/{id}` 改名保存 200（红：0）。
- 映射新建班级带 0 / -1 → 400 `参数校验失败`，`fields` 消息与手工 `PUT /api/classes/{id}` 传 0 时逐字相同；且没有建出班级或年级（红：0 被接受并落库）。
- 手工建班不带人数 → 45；手工保存 0 / -1 → 400 且原值不变（回归）。
- 前端：人数输入 `min = 1`，清空后仍提交 45。

遗留风险：`admin_class.student_count` 与 `draft_lesson.student_count` 的快照关系不变——修改班级人数只影响之后的草稿，已有草稿保留导入/求解当时的人数。V25 只填 0 值（缺陷留下的空缺，按班级当前人数补齐，见上），任何有值快照都不改写；若某份草稿仍显示 0 人，说明它的班级行当时也不存在，需要人工重建该草稿（迁移里对不上的行会保留 0，不做猜测）。仍未解决的上游问题：工作簿里若真带「人数」列，当前 `WorkbookPayload.classes` 只传班名，人数不会入库，需另开票。
