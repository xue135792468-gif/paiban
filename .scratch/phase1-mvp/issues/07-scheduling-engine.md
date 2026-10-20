# 07: Timefold 排课引擎集成

**What to build:** 集成 Timefold Solver 实现完整排课能力：Planning Entity（Lesson）建模、Constraint Streams 实现所有预置约束、SolverManager 异步求解、任务状态轮询接口、增量校验接口。完成后，通过 REST API 能提交排课任务、轮询进度、获取结果草稿；通过增量校验接口能在毫秒级判断一次课节移动是否合法。本票无 UI，纯后端。

**Blocked by:** 06

**Status:** done

- [ ] Lesson 类标记 `@PlanningEntity`，包含 `@PlanningVariable` 的 timeslot 与 room
- [ ] Constraint Streams 实现所有 06 票定义的预置约束（每条约束一个 Constraint 方法）
- [ ] 硬约束与软约束分离，权重映射到 Timefold 的 HardSoftScore
- [ ] POST `/api/scheduling-jobs` 提交排课任务，返回 jobId；同一学期同一时刻只允许一个 running 任务（ADR-0013）
- [ ] GET `/api/scheduling-jobs/{id}` 查询任务状态（pending/running/completed/failed/cancelled）与进度（当前得分、已耗时）
- [ ] DELETE `/api/scheduling-jobs/{id}` 取消任务
- [ ] POST `/api/timetable-drafts/{id}/validate-move` 增量校验接口，接收 lesson 与目标 timeslot/room，返回冲突列表（ADR-0013 轻量接口）
- [ ] 排课失败时返回清晰的失败原因（哪条约束无解）
- [ ] 求解结果持久化为草稿（关联学期与任务）
- [ ] Timefold 引擎接缝测试：每条预置约束至少一个单元测试（构造输入 → 求解 → 断言结果满足约束）
- [ ] 集成测试：构造 3 个班 10 个授课任务，提交排课，轮询到完成，断言所有硬约束满足
- [ ] 性能验证：100 班规模下完整求解 < 5 分钟（ADR-0017）
