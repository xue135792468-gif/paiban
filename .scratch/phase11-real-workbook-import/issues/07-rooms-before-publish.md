# 07: 导入课表没有教室时，发布前给出补教室入口

**What to build:** 十一期验收发现：真实教务工作簿没有教室列（自动创建教室在本期范围外），导入的 546 节课全部 `room_id` 为空，发布基线时报「还有 546 节课未排课或未分配教室」，流程走到发布就断掉。需要给这类课表一个可执行的出口，而不是让用户逐节去点。

**Blocked by:** None (发现于 2026-10-25 云端验收)

**Status:** done

- [x] 课表草稿有课节缺教室时，页面给出明确提示与一键入口（例如「按行政班各分配一间固定教室」或「指定一间临时教室」）
- [x] 一键补教室走服务层写入，复用现有的课节移动/校验语义，不直接改库
- [x] 补完后发布校验通过，且不产生未预期的教室冲突
- [x] 集成测试覆盖：缺教室的导入草稿 → 一键补 → 发布成功

**实现说明：** 验收时的临时绕行：建 14 间「八（x）教室」并按班级逐条 PATCH `roomId`（`force`）后发布成功。真实需求应把这个绕行变成页面上的一个动作。

**背景：** `docs/adr/0026-real-workbook-import.md`（教室自动创建明确在范围外）；2026-10-25 云端验收记录见当天日志。

## 实现说明（2026-10-25）

### 接口

两步，同一份计划函数（`RoomFillPlanner.plan`，纯函数，无 IO）：

| 方法 | 路径 | 权限 | 行为 |
|---|---|---|---|
| `GET` | `/api/timetable-drafts/{id}/room-fill/plan` | `timetable:edit` 且 `foundation:manage` | 只读预览 |
| `POST` | `/api/timetable-drafts/{id}/room-fill` | 同上（两个都要） | 单事务应用 |

响应形状（预览与应用共用 `RoomFillPlanDto`）：

- `applied`：只有成功应用才为 `true`；预览恒为 `false`。
- `applicable`：前端「确认」按钮是否可用（有人工项、学期已发基线、会新增冲突时为 `false`）。
- `createdRooms` / `reusedRooms` / `assignedLessons`：预览给计划数，应用给实际数（第二次调用没有新的空教室时 `assignedLessons` 为 0）。
- `classes[]`：`classId`、`className`、`studentCount`（容量依据）、`requiredCapacity`（最终座位数）、`lessonCount`、`roomName`、`roomId`（预览中新建教室为 `null`）、`roomCapacity`、`action`（`CREATE` / `REUSE`）。
- `manualItems[]`：`classId`、`className`、`code`、`message`。`code` 属于 REST 契约：`CLASS_NOT_FOUND`、`CLASS_SIZE_UNKNOWN`、`COURSE_NOT_FOUND`、`SPECIAL_ROOM_REQUIRED`、`ROOM_NAME_TOO_LONG`、`ROOM_NAME_CONFLICT`、`NEW_CONFLICT`、`DRAFT_PUBLISHED`、`SEMESTER_ALREADY_PUBLISHED`。
- `blockers[]`：补完之后**仍然**阻止发布的项（票 13 的 `DraftPublishReadiness`），不复用「补教室已解决一切」的说法。

拒绝时返回 **409** 并带同一形状（`applied=false` + `manualItems`）；学期已有发布基线、草稿已发布、任何拟变更不安全都属于这一类。草稿不存在为 404，缺任一权限为 403。

### 命名规则与复用判据

`room` 表是全库共享、没有 `semesterId`，所以只用可推导的固定名做关联：

```
{学期名}-{行政班名}-固定教室        （长度必须 ≤ 100，超长转人工，不截断）
```

- 名字不存在 → `CREATE`，`capacity = max(班级 studentCount, 该班拟补课节里最大的 studentCount)`。
- 名字存在 → 仅当 **类型 `NORMAL` 且容量 ≥ 所需容量** 才 `REUSE`；否则 `ROOM_NAME_CONFLICT` 转人工，**不扩容、不冒用、不建同名第二间**。
- 幂等：第二次调用复用本动作建的同一间，教室总数不变。

### 其它行为

- 只填 `roomId == null` 的课节；人工填过的一律原样保留。同一班本次补齐的课节共用同一间固定教室。
- `studentCount <= 0` 的班级列入人工项，绝不按 0 人建室。
- 课程 `requiredRoomType` 非 `NORMAL` 或 `requireOutdoor = true` → 该班人工项（不自动分配专用教室）。
- 写入前用发布同源检查（`DraftValidator.previewRoomAssignment`）比对「补前 vs 拟补后」：只拒绝**本次新增**的冲突（`NEW_CONFLICT`），既有无关冲突照旧显示在 `blockers` 里。
- 一个事务、全或无：先算完计划与冲突比照，再 `FoundationService.createRoom` 建室、`SchedulingService.fillMissingRooms` 写课节；任一步失败整次回滚（不建室、不改课节）。

### 前端

- `MISSING_ROOMS` 的入口从「去教室管理」改为 `RoomFillDialog`（`frontend/src/components/draft/RoomFillDialog.vue`）：打开即读预览，列出班级 / 教室名 / 新建或复用 / 人数依据 / 课节数；有人工项时禁用确认并展示原因；成功后 `applied` 通知页面刷新草稿详情与 validation。
- 只有同时具备 `timetable:edit` 与 `foundation:manage` 时按钮才打开确认框，否则退回教室管理链接（`publishBlockers.ts` 的 `blockerTarget(action, { roomFill })`）。

### 测试

- `backend/src/test/java/com/paiban/scheduling/DraftRoomFillIntegrationTest.java`（7 个，走公开 REST）：两班预览→应用→2 间 NORMAL 教室、容量 ≥ 各班人数、课节全有教室、`publish` 200；手工填的教室不动；再次调用复用不新建；专用教室整次拒绝且零写入；同名但容量不足拒绝且不扩容不重复；单一权限 403；学期已发基线 409。
- `backend/src/test/java/com/paiban/scheduling/service/RoomFillPlannerTest.java`（11 个纯函数用例）。
- `frontend/src/components/draft/RoomFillDialog.spec.ts`（5 个）：预览渲染、人工项禁用确认、剩余 blocker 照实展示、成功后 `applied`、服务端拒绝时展示人工项。
