# 票据 05: 固定教室缺失/冲突时给出可见提示

Status: needs-triage
**Spec:** ../spec.md
**来源:** 2026-10-31 code-review Spec 缺失项

## What

`RoomFillPlanner.fixedRooms` 在以下情况会跳过该班，退化为自由分房：班级人数为 0、找不到同名固定教室、房间容量不足、同一间房被两个班认领。跳过是静默的：求解器仍会给课节分配任意教室，草稿校验也不会提示「这个班没有固定教室/用了多间教室」。

另外，课程配置了 `COURSE_REQUIRED_ROOM_TYPE`（约束条目，而非课程元数据）时，builder 只按课程元数据判断专用教室，可能把课钉在本班普通教室，同时约束又要求专用类型，留下无法同时满足的硬冲突。

## Why

用户要的是「每个班一间本班教室」。坏数据下系统悄悄退回旧行为，课表看上去正常（教室已隐藏），但实际不是每班一间；配置型教室要求还可能制造无解。需要在排课前后都能看见。

## Acceptance

- [ ] 排课前/草稿校验能列出：哪些班没有可用的固定教室、原因（缺失/人数/容量/共用）、以及该班当前用了几间教室。
- [ ] 决定它是发布 blocker 还是警告；若为警告，课表页/草稿页有可见提示。
- [ ] `TimetableProblemBuilder` 判断专用教室时同时读课程元数据与已配置的 `COURSE_REQUIRED_ROOM_TYPE` 条目，避免固定教室与配置要求互斥。
- [ ] `RoomFillService.needsSpecialRoom` 的 `requireOutdoor` 分支与 builder 的 `RoomRequirement.isSpecial` 口径统一（或明确两者语义不同）。
- [ ] 测试覆盖上述四类坏数据与配置型教室要求。
