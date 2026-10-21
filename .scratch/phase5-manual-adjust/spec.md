# 排课系统五期规格：手工调课（换课）

Status: ready-for-agent

---

## Problem Statement

自动排课结果基本可用，发布前的手工微调是当前最痛的环节。用户原话：

> 自动的规则排课应该没问题，主要是手动调课，要能随时看到调课和被调老师的课表信息，不能调好一个老师的课表把另外一个老师的课表弄乱，这个操作流程的设计需要优化。

现状核查：

- 拖拽只能落到空位。目标格被本班其它课占着时，`validate-move` 返回 `CLASS_CONFLICT`，前端弹冲突、不落库（`frontend/src/views/DraftEditorView.vue` 的 `onDrop`、`DraftValidator.java:198-220`）——没有换课能力。
- 后端只有单课节移动 `PATCH /api/timetable-drafts/{id}/lessons/{lessonId}`，没有对调、没有批量校验（`SchedulingService.moveLesson`）。
- 调整时看不到对方教师的课表；拖拽即落库，没有预览、没有撤销。
- 拖拽开始会对每个节次各发一次 `validate-move`（5 天 × 8 节 = 40 个请求，`DraftEditorView.onDragStart`）。
- `DraftEditorView.vue` 771 行，超过 `CODING_STANDARDS.md` 的 600 行拆分阈值。

## Solution

以「选中一节课 → 看相关老师 → 双课表预览 → 原子执行 → 可撤销」为主线，补齐两种换课语义：

- **A 同班换位**：同一行政班的两节课交换节次。
- **B 跨班换人**：不同行政班的两节课交换任课教师，节次与课程不动。

拖到空位仍然是普通移动，但同样接入预览与撤销。

## 核心语义

设目标课节 A：教师 `T_A`、行政班 `C_A`、课程 `K_A`、课节 `S_A`、教室 `R_A`。
候选课节 B：教师 `T_B`、行政班 `C_B`、课程 `K_B`、课节 `S_B`、教室 `R_B`。

前置条件：只有 `DRAFT` 状态的草稿可以换课，`PUBLISHED`/`ARCHIVED` 拒绝；参与换课的两个课节都必须已排入节次。

### A 同班换位（`C_A == C_B`）

交换 `S_A` 与 `S_B`；教师、班级、课程不变。

| 对象 | 变化 |
|---|---|
| 班级课表 | 科目集合不变，节次顺序变 |
| 教师课表 | `T_A`、`T_B` 各变一格 |
| 教室 | 默认随课节移动（`roomId` 不变）；若只有同时交换教室才能成立，候选标记 `roomSwapRequired` 并由界面自动勾选「同时交换教室」 |
| 授课任务 | 不变 |

硬校验：双方在对方原课节无禁排/教研；教室容量与类型；教师/班级/教室冲突；分组上限；同课程一天多节。
同班同教师的两节课也允许（等价于这位老师自己两节课换时间），此时无教师冲突。

### B 跨班换人（`C_A != C_B`）

交换 `T_A` 与 `T_B`；课节、班级、课程、教室全部不变。

| 对象 | 变化 |
|---|---|
| 班级课表 | 节次与课程不变，任课教师变 |
| 教师课表 | `T_A` 去 `S_B` 上 `C_B` 的 `K_B`；`T_B` 来 `S_A` 上 `C_A` 的 `K_A` |
| 教室 | 不变（教室跟着课节走） |
| 授课任务 | 不变（见 ADR-0022） |

硬校验：`K_A` 与 `K_B` 同科目（`Course.subject` 相等；任一为空时放行并给警告）；双方在对方原课节无禁排/教研；教师学科（`Teacher.subject`）与对方课程学科一致（两者都非空时强校验，任一为空时放行并给警告）。
教师日上限、班级上限、课程上限都不变（只是换人，不改变节次分布）。

### 为什么不做「跨班换位」

给定目标课节 A 与目标时段 `S_B`：若 `C_A` 在 `S_B` 已有课，那节课按定义就属于 `C_A`，即 `C_B == C_A`，落回 A；若 `C_A` 在 `S_B` 没有课，那是普通移动。**「跨班换节次」在给定目标时段时根本不是一个良定义的候选**——要让它成立，必须把 `C_A` 在 `S_B` 的那节课也挪走，形成多步链式重排。

因此跨班只提供「换人」。系统遇到「想挪到别班占着的格子」时明确提示：该节次属于其他班级的课，跨班只能换老师、不能换节次。

### 相关老师（RelatedTeacher）

给定目标课节 A，所有能与 A 合法换课的课节 B 的任课教师 `T_B`，分两组：

- **同班换位候选**：`C_B == C_A` 且 `B != A`，且 `T_A` 在 `S_B` 空闲、`T_B` 在 `S_A` 空闲。
- **跨班换人候选**：`C_B != C_A` 且 `K_B.subject == K_A.subject`，且双方在对方原课节无禁排/教研。

筛选维度：科目（`K_B.subject`）、年级（`C_B.gradeId`）、班级（`C_B`）、教师（`T_B`）。
默认只返回硬校验通过的候选；可开关「显示不可用候选及原因」。

## User Stories

1. 作为教务管理员，我选中一节课后能看到所有相关老师，按同班换位/跨班换人分组，并能按科目、年级、班级筛选。
2. 作为教务管理员，我选一个候选后能并排看到两位老师的课表，高亮受影响的两格，并在「调整前/调整后」之间切换。
3. 作为教务管理员，我确认换课后系统原子执行；有任何硬冲突就拒绝并说明是哪一条。
4. 作为教务管理员，我能撤销最近 20 次调整，撤销后课表逐格回到操作前。
5. 作为教务管理员，我把课拖到空位时也能看到影响预览并撤销。
6. 作为教务管理员，我能在教师视角下发起换课，而不必先切回班级视角。
7. 作为教务管理员，我拖拽时页面只发一次校验请求，而不是每个节次各发一次。

## Implementation Decisions

### 后端

1. **统一「课节覆盖」模型**：`LessonOverride(lessonId, periodId, roomId, teacherId)`。批量校验先把全部覆盖应用到内存快照，再校验受影响的课节、它们与其他课节的成对冲突、以及分组统计。
2. `POST /api/timetable-drafts/{id}/validate-moves`，请求 `{ moves: LessonOverride[] }`，响应沿用 `ValidationResult`。现有 `validate-move` 改为它的单条特例，行为不变。
3. `GET /api/timetable-drafts/{id}/lessons/{lessonId}/swap-candidates`，参数 `type`（`ALL|INTRA_CLASS|CROSS_CLASS`）、`courseId`、`gradeId`、`classId`、`teacherId`、`includeUnavailable`。返回候选课节、对方教师、对方班级/年级、节次、可用性与冲突原因。
4. `GET /api/timetable-drafts/{id}/lessons/{lessonId}/move-targets`，返回该课节移到每个目标节次的独立校验结果（教室保持不变）。拖拽预览用它，一次请求替代原来的 40 个 `validate-move`。与 `validate-moves` 的区别：后者是「多个移动同时生效」，前者是「同一课节分别尝试每个目标」。
5. `POST /api/timetable-drafts/{id}/swap`，请求 `{ lessonId, targetLessonId, swapRooms }`。类型由服务端按两个课节的班级关系推导，不信任客户端。`@Transactional` 内双写；任一硬冲突则整体不落库。
6. `DraftValidator` 重构：抽出 `applyOverrides(snapshot, overrides)` 与 `checkAffected(snapshot, affectedLessonIds)`，`validateMove`、`validateMoves`、`validateDraft` 共用同一套规则，消除三套实现漂移。
7. 权限复用 `timetable:edit`，不新增权限点。访问他人记录时仍按 `CODING_STANDARDS.md` 的最小权限原则。
8. `scoreHard` 保持现有语义：本次操作的硬冲突数取负。

### 数据语义

`draft_lesson.teacher_id` 是草稿的权威值，`assignment_id` 降级为来源标记。跨班换人后二者可能不一致，这是必要的——`Assignment` 的教师是任务级属性，无法表达「同一条任务的某一节换老师」。写入 ADR-0022，并在发布时继续以 `draft_lesson` 为准。

### 前端

1. 拆分 `DraftEditorView.vue`：
   - `DraftEditorView.vue`：编排、工具栏、视角切换（≤600 行）
   - `components/draft/DraftGrid.vue`：网格渲染、拖拽、单元格状态
   - `components/draft/SwapCandidatePanel.vue`：候选列表与筛选
   - `components/draft/DualTimetablePreview.vue`：并排双课表与影响高亮
   - `composables/useDraftAdjust.ts`：接口调用、撤销栈、统一错误提示
2. 拖到已占格：打开候选面板并预选同班换位，而不是只弹冲突。
3. 拖拽开始：一次 `validate-moves` 覆盖所有目标节次，替代 40 个并发请求。
4. 撤销：会话内栈，最多 20 步，按钮 + `Ctrl+Z`；移动的逆操作是移回，换课的逆操作是再换一次。刷新后清空（已知限制）。
5. 双课表预览复用 `DraftGrid` 的教师视角，不新写一套网格。

### 文档

- `GLOSSARY.md` 增加：换课（LessonExchange）、同班换位（IntraClassSwap）、跨班换人（CrossClassTeacherExchange）、相关老师（RelatedTeacher）。
- `docs/adr/0022-draft-lesson-teacher-authority.md`：草稿课节的教师以快照为准。
- 帮助中心补一篇「手工调课」场景文档。

## Testing Decisions

1. `DraftValidatorTest`（新建）：批量覆盖、同班换位、跨班换人、冲突归属到具体课节。
2. `SwapIntegrationTest`（新建）：原子性（失败时两条课节都不变）、权限、科目不匹配、`scoreHard`。
3. 前端 `useDraftAdjust.spec.ts`：撤销栈入栈/出栈、上限 20、失败时不弹栈。
4. Playwright `swap.spec.ts`：拖到已占格 → 候选面板 → 换课 → 两张课表正确 → 撤销；跨班换人后班级课表教师变化、节次不变。

## Out of Scope

- AI 白话解析调课指令（下一轮）
- 发布后调整（只读基线，走调代，ADR-0008）
- 跨班换位、多步链式重排
- 服务端撤销持久化、多人并发冲突检测（当前无乐观锁）
- 按教师整体平移课表
- 课时统计重算（跨班换人不改授课任务）

## Resolved Questions

1. **B 的语义**：已确认采用「跨班换人」。跨班换节次不做——它在给定目标时段时不是良定义，强行实现只会制造班级冲突。
