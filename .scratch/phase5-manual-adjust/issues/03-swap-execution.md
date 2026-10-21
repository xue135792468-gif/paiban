# 03: 换课执行 API

**What to build:** 新增 `POST /api/timetable-drafts/{id}/swap`，请求 `{ lessonId, targetLessonId, swapRooms }`。服务端按两个课节的班级关系推导类型：同班走「同班换位」（交换节次，可选同时交换教室），不同班走「跨班换人」（交换任课教师，节次/课程/教室不动）。整个动作在一个事务里；任一硬冲突则两条课节都不落库。跨班换人强校验课程同科目，并在教师学科与对方课程学科都非空时校验一致。写入 ADR-0022。

**Blocked by:** 01

**Status:** done

- [x] `POST /timetable-drafts/{id}/swap`（权限 `timetable:edit`）
- [x] 服务端推导类型，忽略客户端传入的类型
- [x] 同班换位：交换 periodId；`swapRooms=true` 时同时交换 roomId
- [x] 跨班换人：交换 teacherId；periodId/roomId/courseId/adminClassId 不变
- [x] 科目校验：`Course.subject` 必须相等；`Teacher.subject` 与对方课程学科都非空时必须一致
- [x] 原子性：先校验后双写，失败整体不落库
- [x] `scoreHard` 按本次操作硬冲突数更新
- [x] 实现与 ADR-0022 一致：草稿教师以 `draft_lesson.teacher_id` 为准
- [x] `SwapIntegrationTest`：两种换课、原子性、科目不匹配、权限、发布后教师变化正确
