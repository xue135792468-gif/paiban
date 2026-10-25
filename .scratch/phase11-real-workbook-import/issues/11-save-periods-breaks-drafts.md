# 11: 保存作息表会让已有草稿静默失效（课节 id 变了，草稿无提示）

**What to build:** 「学期与作息 → 作息表 → 保存」走的是 `replacePeriods`：删掉本学期全部 `period` 行再重建，**id 全变**。而 `draft_lesson.period_id` 没有外键（只有 `fk_lesson_draft`），于是所有已存在的草稿都指向不存在的课节，没有任何报错或提示；之后打开草稿校验会显示「所选课节不存在」，排课、发布、调代全部无法继续。

**证据（2026-10-25 云端实测）**：对 `semesters/1/periods` 执行一次保存后，`period` 变成新 id 46–85，`draft_lesson` 里 **1092 行（2 份草稿 × 546）全部悬空**，`GET /timetable-drafts/1/validation` 随之失效。已人工把 `period_id` 平移回新 id 修复，悬空归 0。

**Blocked by:** None

**Status:** ready-for-agent

- [ ] 保存作息表前检测：本学期已有草稿引用课节时，要么拒绝并说明原因，要么在事务内按 (day, periodNo) 迁移 `draft_lesson.period_id`
- [ ] 给 `draft_lesson.period_id` 加外键（或至少加一致性校验任务），让"草稿指向不存在课节"在写库时就被拦住
- [ ] 已发布课表的学期仍然拒绝改作息（现有语义保留）
- [ ] 集成测试：导入 → 保存作息 → 草稿校验仍通过且课节仍指向存在的记录

**实现说明：** （待填写）

**同类风险**：`period` 行被重建后，任何按 period_id 引用它的历史数据（草稿、调代记录）都可能悬空，需要一次全面排查。
