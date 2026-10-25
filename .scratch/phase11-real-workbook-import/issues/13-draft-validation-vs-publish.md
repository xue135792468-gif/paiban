# 13: 草稿校验说"通过"，发布却拒绝（教室口径不一致）

**What to build:** `GET /timetable-drafts/{id}/validation` 对 546 节全部无教室的草稿返回 `valid=true, hardConflicts=0`，而 `POST /timetable-drafts/{id}/publish` 返回 400「还有 546 节课未排课或未分配教室」。同一个"能不能发布"的问题有两个答案，用户会先看到绿色通过、再被拒绝。

**证据（2026-10-25 云端实测）**：两份导入草稿 validation 均 `(True, 0)`；对 draft 1 调发布 → 400「还有 546 节课未排课或未分配教室，不能发布」。

**Blocked by:** None（与 07-rooms-before-publish 相关：07 补教室入口，本票统一口径）

**Status:** done

- [x] 草稿校验包含"发布前置条件"（缺课节/缺教室/未排课），或在结果里单独列出 publishable=false 及原因
- [x] 发布与校验共用同一套判定
- [x] 集成测试：无教室草稿 → validation 不再报 valid=true；补教室后 → valid=true 且能发布

**实现说明：**

**根因**：同一个问题写在两处。`DraftValidator.validateDraft` 只跑 24 条 `ConstraintChecker` 规则（排课冲突），而 `TimetablePublishService.publish` 另有一套前置判断（草稿已发布 → 空草稿 → 未排课或缺教室 → 硬冲突 → 学期已有基线）。546 节全缺教室的导入草稿只触发前者，于是页面说通过、发布说不行。

**共享判定入口（同一份代码路径）**：

- `scheduling/service/DraftPublishReadiness.java` 是唯一判定：输入 `Facts(已发布, 课节数, 未排课数, 缺教室数, 引用失效数, 硬冲突数, 学期已有基线)`，输出 `List<Blocker>`。纯函数、无 IO，配 `DraftPublishReadinessTest`。
- `GET /timetable-drafts/{id}/validation`（`DraftValidator.validateDraft`）算出 Facts、返回 blockers。
- `TimetablePublishService.publish` 删掉了自己的全部前置判断，改为调用同一个 `validator.validateDraft(...)`，再用 `refuse(validation)` 把第一条 blocker 映射成 HTTP 结果。两处不可能再给出不同答案。
- 「学期已有基线」是 timetable 模块的事实，用接口倒置避免 scheduling 反向依赖 timetable：`scheduling/service/SemesterBaselineQuery`（scheduling 定义的只读接口）由 `timetable/service/TimetableSemesterBaselineQuery` 用 `TimetablePublishRepository` 实现。

**blocker code 清单**（写进响应即契约，顺序即优先级＝页面从上到下、发布先报的那条）：

| code | 含义 | count | action |
|---|---|---|---|
| `DRAFT_PUBLISHED` | 草稿已发布，不能重复发布 | 1 | `VIEW_TIMETABLE` |
| `DRAFT_EMPTY` | 草稿没有课节 | 0 | `NONE` |
| `INVALID_PERIODS` | 课节的 `periodId` 不属于本学期 period | 受影响课节数 | `EDIT_PERIODS` |
| `UNSCHEDULED_LESSONS` | 课节 `periodId` 为空 | 受影响课节数 | `EDIT_LESSONS` |
| `MISSING_ROOMS` | 课节 `roomId` 为空 | 受影响课节数 | `ASSIGN_ROOMS` |
| `HARD_CONFLICTS` | `ConstraintChecker` 报硬冲突 | 冲突数 | `NONE` |
| `SEMESTER_ALREADY_PUBLISHED` | 学期已有发布基线，基线只读 | 1 | `OPEN_SUBSTITUTIONS` |

message 是人类可读文案且已含条数（API 调用方不必自己拼），action 是稳定标识、不含文案，页面据此选入口。`INVALID_PERIODS` 与 `ConstraintChecker` 的 `PERIOD` 硬冲突是同一事实的两种视角：前者给页面指向作息入口，后者的冲突数照旧计入 `hardConflicts`。

**响应兼容**：`valid`、`hardConflicts`、`conflicts` 字段与语义不变，新增 `publishable`（blockers 为空）与 `blockers`。整草稿检查的 `valid = blockers 为空`（因此缺教室/未排课/引用失效都是 `false`）；`hardConflicts` 仍只数 24 条规则里的排课冲突，缺教室/未排课不进冲突数。

**局部验证不被阻断**：`validate-move`、`validate-moves`、`validate-changes`、swap 场景仍只检查受影响课节，返回 `blockers=[]`、`publishable` 与其 `valid` 相同。专门测试：存在 1 节缺教室的草稿上对另一节 `validate-move` → `valid=true, hardConflicts=0, conflicts=[]`（这个用例在我把判定误接到局部路径上时会立刻红，见 `validate-move` 省略 roomId 的既有用例）。

**HTTP 语义（未改动既有状态码）**：409 → `DRAFT_PUBLISHED` / `SEMESTER_ALREADY_PUBLISHED` / `HARD_CONFLICTS`（硬冲突保留原「前 3 条原因」文案）；400 → `DRAFT_EMPTY` / `INVALID_PERIODS` / `UNSCHEDULED_LESSONS` / `MISSING_ROOMS`（带条数）。没有放宽教室要求，也没有为了测试变绿去掉任何前置。

**前端**：整草稿检查结果由 `components/draft/DraftPublishBlockers.vue` 渲染——每个 blocker 一条 `el-alert`（`data-code` 为 code，message 内含条数），下面挂动作入口；动作由后端 `action` 经 `components/draft/publishBlockers.ts` 映射：`ASSIGN_ROOMS` → 教室管理 `/admin/rooms`、`EDIT_LESSONS` → 本页未排课抽屉、`EDIT_PERIODS` → 学期与作息 `/admin/semesters`、`OPEN_SUBSTITUTIONS` → `/substitutions`、`VIEW_TIMETABLE` → `/timetable`、`NONE`/未知不给按钮。`publishable=false` 时「发布课表」按钮禁用，用户不会再点了才被拒（服务端仍是唯一裁判）。本票**不做**按行政班一键补教室（票 07），`ASSIGN_ROOMS` 目前只指向既有的教室管理入口。为了不越过 600 行上限，统计条抽到 `components/draft/DraftStats.vue`，`DraftEditorView.vue` 599 → 575 行（`scripts/check-sfc-size.sh` 通过，未进 baseline）。

**测试（先红后绿，均走公开 REST）**：

- `DraftPublishReadinessIntegrationTest`（5）：① 求解后的草稿逐节 PATCH 清空 `roomId` → `valid=false`、`publishable=false`、`hardConflicts=0`、`conflicts=[]`、`MISSING_ROOMS.count=12`，发布仍 400 且消息含条数（**红：改动前 `valid=true`**）；② 逐节 PATCH 回房间 → `valid=true`、`publishable=true`、`blockers=[]`，发布 200；③ 1 节缺教室时对另一节 `validate-move` → `valid=true`；④ 空草稿 → 400 且 blockers 有 `DRAFT_EMPTY`（红：之前 `valid=true`）；⑤ 学期已发布后第二份草稿 → blockers 有 `SEMESTER_ALREADY_PUBLISHED`、发布 409（红：之前 `valid=true`）。
- `DraftPublishReadinessTest`（4）：纯函数——完整草稿无 blocker、缺教室与未排课分别计数、空草稿 count=0、七种 blocker 的固定顺序。
- `DraftValidatorTest`（+6）：整草稿缺教室只出 blocker 不进冲突数、未排课与引用失效分开计数、干净草稿 valid=publishable=true、已发布草稿、学期已有基线、局部 validate-moves 不被缺教室课节阻断。
- 前端：`publishBlockers.spec.ts`（7）、`DraftPublishBlockers.spec.ts`（6）覆盖动作映射与渲染（缺教室/未排课/引用失效/硬冲突无按钮）。
- 既有 `TimetablePublishIntegrationTest`（硬冲突 409、已发布 409、发布后可查四视角）未改动且全绿。

**遗留风险**：`ASSIGN_ROOMS` 只有一个跳转，批量补教室仍要等票 07；`publishable` 依赖一次 `TimetablePublishRepository` 查询（每次整草稿检查多一次读，局部验证不查）；`valid` 对已发布草稿现在返回 `false`（此前为 `true`），语义更准但如果将来有调用方把 `valid` 当作「没有排课冲突」会读到不同结果——冲突数请用 `hardConflicts`。
