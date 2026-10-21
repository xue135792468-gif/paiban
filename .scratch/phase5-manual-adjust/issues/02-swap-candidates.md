# 02: 换课候选查询 API

**What to build:** 新增 `GET /api/timetable-drafts/{id}/lessons/{lessonId}/swap-candidates`，返回所有能与目标课节合法换课的候选，按 `INTRA_CLASS`（同班换位）与 `CROSS_CLASS`（跨班换人）分组。支持 `courseId`、`gradeId`、`classId`、`teacherId` 筛选与 `includeUnavailable` 开关。默认只返回硬校验通过的候选；不可用候选带冲突原因。完成后，前端「相关老师」面板只靠这一个接口就能出列表。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 候选查询服务：同班换位候选（同班其它课节 + 双方空闲）
- [ ] 跨班换人候选（同科目、不同班、双方在对方原课节无禁排/教研）
- [ ] 筛选参数：科目、年级、班级、教师
- [ ] 可用性用 01 的批量校验计算，不另写一套规则
- [ ] 响应包含对方教师、班级、年级、课程、节次、教室与冲突原因
- [ ] 性能：一个 40 班 × 40 节的草稿在 1 秒内返回（内存过滤）
- [ ] `SwapCandidateIntegrationTest`：两种候选、筛选、不可用原因
