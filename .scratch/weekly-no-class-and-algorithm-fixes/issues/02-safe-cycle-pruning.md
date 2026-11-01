# 02: 不提前漏掉合法的三课置换环

Status: in-progress
Priority: P1
**What to build:** 合法的多人调课不会因为尚未完成的中间状态被误报无解。
**Blocked by:** None
**Spec:** ../spec.md
**Gate:** 规格与拆票已确认；已由 `codex/weekly-t02` 认领，定向回归完成，待最终集成验收。

- [x] 将审查证据A3做成回归：独立校验接受已知三课环，而现有搜索找不到；修复后产生满足该目标的合法候选。
- [x] 提前排除只使用对后续扩展安全的条件；每个候选同时应用全部移动后检查最终硬约束。
- [x] 教师/班级/教室冲突、同课程按天限制、冻结槽、显式目标日期、窗口、既有规模和预算边界保持有效。
- [x] 现有200-seed属性测试继续通过；再补不同课程/日期布局的有界小样本，对照独立枚举或手工已知可行环，验证不是仅改到通过单个反例。
- [x] 预算内未搜全与确实没有候选的说明保持诚实，不扩大成无界搜索；已有可用候选不必等待穷举证明全局最优。
- [x] 主模型检查搜索安全性及反例证据；定向回归结果落票，最终全量由06收口。

## 实现记录（2026-11-01，分支 `codex/weekly-t02`）

- 根因修复：`SwapPlanner.placeable` 删除 `SAME_COURSE_ONCE_PER_DAY` 的中间状态提前排除。该条件不安全：目标日另一节同课程可以随后续环扩展移出。最终候选仍由 `score` 在整组移动后调用 `ConstraintChecker.checkHardFast` / `softPenalty`，测试再用 `PlanVerifier` 独立确认；没有放松硬规则。
- 预算文案：`timedOut && top.isEmpty()` 时不再追加 `NO_SOLUTION`，只给 `SEARCH_BUDGET`：「搜索超出预算，窗口内结果未完整检查；不能据此判定无解」。搜索仍受 `nodeBudget` / `timeBudgetMs` 和 `maxTeachers` 等既有边界限制，未扩大成无界搜索。
- 回归覆盖：A3 手工已知三课环先由 `PlanVerifier` 接受，再断言搜索能找到相同 entry；另加 3 个有界小布局参数样本（周三→周五、周一→周三、周二→周四的手工已知环），以及一个最终仍留下同课程同日冲突、必须拒绝的反例。样本只覆盖手工可行环与最终硬规则拒绝，不扩大搜索预算，也不调用被测搜索来生成期望值。

### 定向回归证据

- 红：`C:\Users\liu\AppData\Local\Programs\GitPortable\bin\bash.exe -lc "source .tools/env.sh; cd backend; MAVEN_OPTS='-Xmx512m' ./mvnw -B -DforkCount=0 -Dtest=SwapPlannerTest,PlanVerifierTest,SwapPlanPropertyTest test > ../t02-red.log 2>&1"`；结果 `BUILD FAILURE`，`Tests run: 29, Failures: 2, Errors: 0, Skipped: 0`，失败为 A3 搜索和预算文案。
- 绿：同一命令输出到 `../t02-green-review.log`；结果 `exit=0`，`BUILD SUCCESS`，`Tests run: 33, Failures: 0, Errors: 0, Skipped: 0`（`PlanVerifierTest` 8、`SwapPlannerTest` 24、`SwapPlanPropertyTest` 1；200 个固定 seed 属性测试通过）。
- 临时日志仅在当前 worktree，提交前清理；未跑全量 / e2e / 生产 / 外网。
