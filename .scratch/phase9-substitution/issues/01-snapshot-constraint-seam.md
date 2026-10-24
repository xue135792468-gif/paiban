# 01: 发布后课表快照与约束校验缝（含 ADR-0025）

**What to build:** 一份 ADR 记录「发布后调课 = 同班置换环」这个决定；把现有 24 类约束抽成一个不依赖草稿的纯校验缝，让已发布课表也能用同一套硬/软规则判定。草稿期的换课与校验行为完全不变。没有 UI，但这是后面所有票的地基。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] ADR-0025 落盘，记录：调课 = 同班置换环、代课 = 单向补位、同日优先与跨天窗口、三个排序预设、确定性搜索、独立校验器，并明确推翻 ADR-0002 的「发布后只支持单节课变更」边界
- [x] 已发布课表能以只读快照形式交给同一套约束判定，快照包含课节、教师、班级、教室、节次、该日期段已生效调代
- [x] 24 类约束的硬/软标志与权重只有一处定义，草稿与发布后共用，没有复制第二份规则
- [x] 草稿期现有换课、校验、发布流程全部回归通过
- [x] 单元测试覆盖快照适配层与至少 4 类硬约束（教师冲突、班级冲突、教室冲突、场地类型）

**实现说明：** ADR 落盘为 `docs/adr/0025-published-swap-cycles-and-verifier.md`（编号按推送后的规格使用 0025）。24 类规则收敛到 `scheduling/constraint/ConstraintChecker`，草稿 `DraftValidator` 改为委托、行为不变；`PlanSnapshot` + `PlanSnapshotFactory` 是发布后课表的只读快照适配层，已生效调代按日期叠加。测试：`ConstraintCheckerTest`（教师/班级/教室/场地类型四类硬约束）、`PlanSnapshotFactoryTest`（快照与叠加映射）。
