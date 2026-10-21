# 02: 课表入库服务

**What to build:** 新增 `TimetableImportService`，把课表目标的行（班级/星期/节次/课程/教师/教室）落成一份 `DRAFT` 草稿。按名称匹配行政班、课程、教师、节次、教室；未匹配返回行级错误，整批不落库。按 `(班级, 课程)` 查找或创建授课任务（`weeklyLessons` 取该组合的课节数，教师以导入行为准），创建 `TimetableDraft` 与 `DraftLesson`（`assignmentId` 非空、`lessonIndex` 递增）。整个入库一个事务。硬冲突允许生成草稿，由现有 `DraftValidator` 在发布时拦截。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] `TimetableImportService`：行解析（星期/节次归一化）与名称匹配
- [ ] 未匹配 → `RowErrorDto`（1 基行号 + 字段 + 原因 + 候选），整批不落库
- [ ] `(班级, 课程)` 复用或创建 `Assignment`；`TeachingClass` 缺失时创建
- [ ] 创建 `TimetableDraft`（DRAFT）+ `DraftLesson`，`lessonIndex` 按任务递增
- [ ] 同一 `(班级, 课程)` 多个教师时：授课任务取首个教师，课节保留各自教师并给警告（ADR-0022）
- [ ] 事务回滚测试
- [ ] `TimetableImportServiceTest`：正常、未匹配、复用/创建任务、重复节次允许生成草稿
