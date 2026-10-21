# 03: 名称映射与候选查询

**What to build:** 给 AI 导入会话增加映射能力。预览响应带 `issues`（未匹配的名称、行号、字段、候选）；新增候选查询 `GET /api/ai/import/lookup?type=class|course|teacher|room|period&q=`；新增 `POST /api/ai/import/sessions/{id}/mapping`，接收用户选择的映射（源名称 → 系统实体 ID），重新校验并返回预览。可选「确认创建」：对教师/课程/教室/班级，用户显式勾选后才创建；默认不创建。完成后，前端可以逐项解决未匹配名称，全部解决后才能入库。

**Blocked by:** 01, 02

**Status:** done

- [x] `PreviewDto` 增加 `issues`（行号、字段、原值、候选、是否必须解决）
- [x] `GET /lookup`：按类型返回候选（班级限定当前学期；课程/教师/教室全局）
- [x] `POST /sessions/{id}/mapping`：应用映射、重新校验、返回预览
- [x] 显式创建：`create=true` 时创建缺失实体并映射，默认 false
- [x] 权限 `foundation:manage`；课表会话额外 `timetable:edit`
- [x] 测试：未匹配 → 映射 → 入库；不存在的 ID 拒绝；创建开关生效
