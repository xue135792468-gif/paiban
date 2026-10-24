# 02: 调课申请与同日方案预演

**What to build:** 教师能提交一笔「调课」：选日期、选自己当天的一节课、选希望换到的目标节次、填原因。系统生成同一天内的置换环候选方案（2–6 位教师），教务在「待审批」看到最多 3 张方案卡，包含每条移动、影响天数、影响教师数和软约束扣分。申请与方案落库，但还不能批准生效。

**Blocked by:** 01

**Status:** done

- [x] 教师只能对自己的课节提交调课；所选日期的星期必须与课节一致；日期在学期内；同一课节同一天不能重复申请
- [x] 系统生成同日 2 人、3 人、4 人环方案；影响天数固定为 1；影响教师数按去重后的教师计；软约束扣分按现有权重计算
- [x] 每笔请求最多保留 3 个方案，按默认「最小影响」排序（影响天数 → 同周 → 影响教师数 → 软分）
- [x] 无解时返回结构化原因：哪条边被剪掉、缺的是教师空闲还是教室/场地
- [x] 前端「我的调代」增加类型切换（调课/代课）、目标节次选择、我的申请里显示方案摘要
- [x] 「待审批」方案卡显示移动列表、影响天数、影响教师数、软约束扣分
- [x] 纯求解器单元测试覆盖 2 人环、3 人环、无解三种情况；HTTP 集成测试覆盖提交 → 生成方案 → 列表

**实现说明：** V22 增加 `substitution_request.type/target_period_id`、`substitution.type/plan_id`、`substitution_plan`、`substitution_plan_move`。纯求解器在 `substitution/plan`（`SwapPlanner`、`RankingPreset`、`CandidatePlan`），不依赖 Spring/DB/AI。接口：`POST /substitution-requests`（type/targetPeriodId）、`POST|GET /substitution-requests/{id}/plans`、`GET /substitution-swap-targets`（教师可选目标节次，不暴露整班课表）。前端拆成 `components/substitution/*`，原 720 行视图降到 119 行。测试：`SwapPlannerTest`（2 人环、3 人环、无解、跨天、窗口、上限、预设）、`SwapFlowIntegrationTest`。
