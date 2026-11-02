# 04 草稿联动方案搜索、独立复核与原子应用

Status: done
Type: task
Blocked by: 01、02、03
Owner: worker
日期：2026-11-02

## 目标与输入

01–03合入版本、contracts.md；现有SwapPlanner/PlanVerifier仅作可复用机制参考，主模型负责算法设计与裁决。
前置票必须验收并合入后才能认领；ready-for-agent表示规格已就绪，不表示依赖已完成。
共同约束见[规格](../spec.md)、[接口契约](../contracts.md)、[执行路线](../ticket-plan.md)。

## 写入范围与边界

scheduling内新增周期换位规划/快照/独立验证/预览应用服务和端点；最小化控制器DTO适配；对应后端测试。可抽共享纯策略，不把调代日期服务直接拖入scheduling。
只修改本票必要文件；发现契约冲突先由统筹统一更正，不能让多个worker各自猜接口。不得改生产数据或部署。

## 实施步骤

1. 以sourceLessonId及指定targetLessonId锚定搜索，先尝试直接合法交换；不合法时搜索同班本周闭环，来源课必须到落点，锁定来源/目标直接拒绝、锁定中间课排除。
2. 本班外授课加入冲突上下文但不可移动；保留课程/教师/班级集合，使用共同课节上限与排序，最多返回三个不同方案。
3. 建立独立于搜索剪枝的全方案复核，复用基础ConstraintChecker而不复用placeable裁剪判定作为安全依据；不得重现先前漏搜合法三课环的问题。
4. 保留有界节点/时间预算和诚实状态；超限找到候选可展示验证后候选但标未搜完，未找到不能说全局无解。
5. 预览绑定课表/规则/设置/锁定与授课任务指纹；应用时在事务一致性边界内重新核对并调用02整单写入，过期返回冲突并零写入。

## 验收清单

- [x] 构造A/B直换失败而三课闭环合法的案例，必须找到并可原子应用，主模型亲自审查反例和搜索正确性。
- [x] 给定同一快照和完整预算输出稳定、无重复、遵循三种排序；上限和去重教师数分别正确。
- [x] 锁定中间课/全班锁定、禁排/晚自习规则和其他班教师占用均正确，未搜完与限额内无方案不同。
- [x] 课表、规则、设置、锁定或授课任务任一变化均不能应用旧预览；独立复核能拒绝故意伪造/漏课/重复课的方案。

## 验证与交付

纯规划与独立验证反例单测、服务/HTTP应用集成与交错并发测试；严格性能基准与功能正确性分开，禁止用一次毫秒数冒充性能保证。
每票提交Delivery：实际改动文件、分支/基点/提交、真实命令与退出码、剩余问题；全量门禁统一由09执行。
任务前检查资源；实际执行默认group/deepseek、high；机械搜证仅group/gemini3.8；算法结论由主模型负责。
遵守AGENTS/CODING_STANDARDS，不同worktree隔离；结束清理本票临时文件与进程。

## Delivery

**实现分支**：`codex/t04-cycle-plans`（worktree `E:/paiban-wt/t04-cycles`）
**基点**：`codex/draft-swap-locks`（含 01/02/03，HEAD `c893f7e`）
**提交**：`feat(scheduling): search and atomically apply draft cycle plans`

### 改动文件

新增：

- `backend/src/main/java/com/paiban/scheduling/plan/DraftCyclePlanner.java`（纯静态 DFS 闭环搜索、必要剪枝、节点/时间预算、排序去重）
- `backend/src/main/java/com/paiban/scheduling/domain/DraftCyclePlanToken.java`
- `backend/src/main/java/com/paiban/scheduling/domain/DraftCyclePlanTokenRepository.java`
- `backend/src/main/resources/db/migration/V46__draft_cycle_plan_tokens.sql`
- `backend/src/main/java/com/paiban/scheduling/service/DraftCyclePlanService.java`（search/apply/指纹/一次性 token）
- `backend/src/main/java/com/paiban/scheduling/web/DraftCyclePlanController.java`
- `backend/src/main/java/com/paiban/scheduling/web/DraftCyclePlanDtos.java`
- `backend/src/test/java/com/paiban/scheduling/plan/DraftCyclePlannerTest.java`
- `backend/src/test/java/com/paiban/scheduling/DraftCyclePlanIntegrationTest.java`

修改：

- `backend/src/main/java/com/paiban/scheduling/service/DraftValidator.java`（新增 `PlanLesson`/`PlanningSnapshot`/`planningSnapshot`/`assess`，`assessChanges` 复用同一核心；`L` 增加 `lessonIndex`/`locked`）
- `backend/src/main/java/com/paiban/scheduling/service/DraftWriteGuard.java`（抽出只读 `requireEditable`，`lockEditable` 行为不变）
- `.scratch/draft-swap-locks/contracts.md`（回写 §4.1 实际契约）
- 本票面

### 新增接口

- `POST /api/timetable-drafts/{id}/cycle-plans/search`（`timetable:edit`）：返回 `FOUND`/`NONE`/`BUDGET_EXHAUSTED`，最多 3 个方案与一次性 token；不改课表、锁定或 `version`。
- `POST /api/timetable-drafts/{id}/cycle-plans/apply`（`timetable:edit`）：token 一次性、10 分钟有效，事务内重算指纹、整表独立复核后走 02 的 `DraftChangeService.apply` 原子写入。

### 验证命令与真实退出码

```bash
cd /e/paiban-wt/t04-cycles
source .tools/env.sh
cd backend
./mvnw -B -q -DskipTests compile
# EXIT=0

./mvnw -B -DforkCount=0 -Dtest=DraftCyclePlannerTest,DraftCyclePlanIntegrationTest,DraftChangeIntegrationTest,DraftValidatorChangeAssessmentTest,SwapPlannerTest test
# EXIT=0；Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
```

- 纯规划反例：`DraftCyclePlannerTest` 8/8（直换、三课环、稳定性、上限、锁中间课、其他班教师剪枝+伪造复核、`nodeBudget=1`、去重教师数）EXIT=0。
- HTTP 全链路：`DraftCyclePlanIntegrationTest` 8/8（搜索零副作用、原子应用、重放/过期/伪造、他人移动/规则/设置失效、锁/跨班/同格多课、权限/已发布、并发同旧版本、三课环 validation 无新增硬冲突）EXIT=0。
- 仓库检查：`scripts/check-module-boundaries.sh` EXIT=0；`scripts/check-blank-checks.sh` EXIT=0；`scripts/check-migrations.sh` EXIT=0（迁移版本唯一）。
- 按票面要求未运行全量 `verify.sh`、未起 Docker。

### 剩余问题

- 真实 PostgreSQL + Flyway `V46` 的落库验证留给 09 的全量门禁；本机只用 H2 测试档。
- token 清理为搜索时顺手 `deleteByExpiresAtBefore(now)`，没有独立定时任务；过期 token 仍会在表内短暂存在，但 apply 会拒绝。
- 并发只覆盖「两个请求同持旧版本」的 HTTP 不变量（后到者 409、只落一次变更），未做真实压测；预算是搜索上限，不作为性能 SLA 证明。
- 搜索只移动课节的 `periodId`，教师/教室随课保留；不做教室换位（符合规格“不盲目交换教室”）。
- 全量门禁、合并与发布由 09 统一执行。

## Comments
