# 01: 批量校验与 DraftValidator 重构

**What to build:** 引入 `LessonOverride(lessonId, periodId, roomId, teacherId)` 覆盖模型，新增 `POST /api/timetable-drafts/{id}/validate-moves`，请求 `{ moves: LessonOverride[] }`。把 `DraftValidator` 重构成「先把全部覆盖应用到内存快照，再只校验受影响的课节、它们与其他课节的成对冲突、以及分组统计」；现有 `validate-move` 改为它的单条特例，行为不变。完成后，前端一次请求就能拿到一节课拖到所有目标节次的校验结果，换课的双写也能原子校验。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 新增 `LessonOverride` DTO（`scheduling/web/SchedulingDtos.java`）
- [ ] `DraftValidator` 抽出 `applyOverrides(snapshot, overrides)` 与 `checkAffected(snapshot, affectedLessonIds)`
- [ ] `validateMove`、`validateDraft` 改为复用同一套规则，消除重复实现
- [ ] 新增 `POST /timetable-drafts/{id}/validate-moves`（权限 `timetable:edit` / `scheduling:view` / `timetable:publish`）
- [ ] `validate-move` 改为单条特例，响应结构与现有前端兼容
- [ ] `DraftValidatorTest`：批量覆盖、冲突归属、空覆盖、重复 lessonId
- [ ] `SchedulingIntegrationTest` 增加批量校验用例
- [ ] 确认 ADR-0017 的 `PerformanceBenchmarkIT` 仍达标
