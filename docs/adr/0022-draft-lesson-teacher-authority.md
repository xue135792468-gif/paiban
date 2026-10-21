# 草稿课节的任课教师以快照为准

跨班换人需要在不改变授课任务的前提下，把某一条任务下的某一节课换给另一位同科目教师。`Assignment` 的教师是任务级属性（`uk_assignment_class_course` 只约束教学班与课程，教师不在唯一键内），同一条任务下的所有课节共享一个教师，无法表达「这一节换人」。

## 决定

- `draft_lesson.teacher_id` 是草稿的权威值；`assignment_id` 降级为来源标记。
- 发布时继续以 `draft_lesson` 写 `timetable_entry`，不 join `Assignment` 取教师。
- 跨班换人不修改 `Assignment`，也不修改 `weekly_lessons`。

## Consequences

- 跨班换人后 `draft_lesson.teacher_id` 可能与 `assignment.teacher_id` 不一致，这是预期状态，不是数据损坏。
- 所有读取草稿教师的地方必须读 `draft_lesson.teacher_id`；禁止 join `Assignment` 取教师。
- 将来若做教师工作量/课时统计，不能直接按 `Assignment` 汇总，必须按草稿或已发布课表汇总。
- 调代仍是发布后改课的唯一途径（ADR-0008）；本决定只影响发布前的草稿。
- `AssignmentService.update` 修改教师不会回溯已有草稿——这一既有行为保持不变，现在有了明确的语义解释。
