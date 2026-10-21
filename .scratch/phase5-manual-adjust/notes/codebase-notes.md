# 五期实现：代码库笔记

供实现子代理快速定位，不替代读代码。所有路径相对仓库根。

## 构建与验证

```bash
source /e/排课系统/.tools/env.sh          # worktree 内没有 .tools，必须用主仓库的
cd backend && ./mvnw -Dtest=ClassName test
scripts/verify.sh                          # 本地闸门：后端测试 + 前端 lint/build/unit
```

- 后端测试库是 H2 + Flyway；`ddl-auto=validate`，实体列必须与迁移一致。
- 前端 `npm run lint`、`npm run build`、`npm run test:unit`（Vitest，`src/**/*.spec.ts`）。
- E2E：`cd frontend && npm run test:e2e`（Playwright 自动拉起前后端）。
- worktree 的 `frontend/node_modules` 是指向主仓库的 junction，不要 `npm install`。

## 后端地图

| 关注点 | 位置 |
|---|---|
| 草稿路由 | `scheduling/web/SchedulingController.java`（`/api/timetable-drafts`） |
| 草稿 DTO | `scheduling/web/SchedulingDtos.java`（`ValidateMoveRequest`、`MoveLessonRequest`、`MoveResult`） |
| 校验器 | `scheduling/service/DraftValidator.java`（`validateMove`、`validateDraft`；`snapshot/checkLesson/checkPair/checkGroups` 均 private） |
| 草稿写操作 | `scheduling/service/SchedulingService.java`（`persistDraft`、`moveLesson`） |
| 草稿实体 | `scheduling/domain/DraftLesson.java`（`assignmentId, lessonIndex, courseId, teacherId, adminClassId, studentCount, periodId, roomId`） |
| 授课任务 | `scheduling/domain/Assignment.java`（`semesterId, adminClassId, teachingClassId, courseId, teacherId, weeklyLessons`） |
| 课节 | `foundation/domain/Period.java`（`semesterId, dayOfWeek, periodNo, label, section, startTime, endTime`） |
| 课程 | `foundation/domain/Course.java`（`name, subject, allowConsecutive, requireOutdoor, requiredRoomType, color`） |
| 教师 | `foundation/domain/Teacher.java`（`name, employeeNo, subject, ...`） |
| 行政班 | `foundation/domain/AdminClass.java`（`semesterId, gradeId, name, headTeacherId, studentCount`） |
| 权限常量 | `identity/domain/Permissions.java`（`timetable:edit`、`scheduling:view`、`timetable:publish`） |
| 发布 | `timetable/service/TimetablePublishService.java`（发布后只读；已有发布记录时拒绝再次发布） |

校验规则现状（`DraftValidator`）：

- `PERIOD`、`ROOM_CAPACITY`、`ROOM_TYPE`、`TEACHER_UNAVAILABLE`、`TEACHER_MEETING`、`COURSE_NOT_IN_EVENING`
- 同节次成对：`TEACHER_CONFLICT`、`CLASS_CONFLICT`、`ROOM_CONFLICT`
- `SAME_COURSE_PER_DAY`
- 分组：`TEACHER_MAX_PER_DAY`、`CLASS_MAX_PER_DAY`、`COURSE_MAX_PER_DAY`、`EVENING_MAX`、`SPORT_NOT_FIRST_AFTERNOON`（后几类仅 `hard=true` 才报）
- **只校验草稿内部**，不校验已发布基线与调代叠加层。
- 成对冲突用 `enabled()` 判断，即使配成软约束也会阻止保存。

## 前端地图

| 关注点 | 位置 |
|---|---|
| 草稿编辑器 | `frontend/src/views/DraftEditorView.vue`（771 行，三视角：班级/教师/教室） |
| 拖拽链路 | `onDragStart` 对每个 period 并发 `POST .../validate-move`；`onDrop` 调 `PATCH .../lessons/{lessonId}` |
| 已发布课表 | `frontend/src/views/TimetableView.vue`（四视角，只读） |
| HTTP 客户端 | `frontend/src/api/http.ts`（axios，baseURL `/api`；无领域 API 模块，页面内联调用） |
| 状态 | Pinia 只有 `auth`、`settings`；页面局部 `ref` |
| 组件 | `frontend/src/components/`（PascalCase；目前只有 EmptyStateGuide、ImPlatformCard、ImportDialog、InfoTooltip） |
| 样式 | Element Plus + 全局 CSS 变量 + SFC `<style scoped>`；无 Tailwind/Sass |
| 单测 | 与源码同目录 `*.spec.ts` |
| E2E | `frontend/e2e/scheduling.spec.ts`（拖拽用例 `场景 1b`） |

## 实现约定

- 标识符英文、用户可见文案中文；测试方法名英文。
- 管理端点用 `Permissions` 常量，禁止角色名字符串。
- 一个业务动作写多张表时放在一个事务里。
- 单个 SFC 超过约 600 行优先拆分。
- 新增行为前先看 `docs/adr/`；五期相关：ADR-0008（发布后只读 + 调代叠加）、ADR-0022（草稿教师以快照为准）。
