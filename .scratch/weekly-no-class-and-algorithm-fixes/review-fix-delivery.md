# 集中 review-fix 交付记录（review-round1 接受项）

分支 `codex/weekly-review-fix`，工作目录 `.worktrees/weekly-review-fix`，基线 `1acf0e3`。
完成前已 fast-forward 合并集成分支 `codex/weekly-no-class`（`a2cf410` → `4753fc0`，只含主线程文档与验收截图，无产品代码变化），因此本提交之后回到集成分支无需再解冲突。

只落实 `review-round1.md` 的**接受项**。拒绝的设计（provider/checker 各自遍历真实空位、preview 的 planned→entity 适配）按原样保留；gap 数学与解算器逻辑未改；算法最终判断仍归主模型。

## 接受项与落地位置

1. **容量排队测试改为受控交错**（`backend/.../ClassCapacityIntegrationTest.java`）
   `@MockitoBean(name = "schedulingExecutor")` 捕获 `execute(Runnable)`，HTTP 提交得到 `PENDING` → HTTP 改成容量不足 → 明确执行捕获的 runnable → HTTP 断言 `FAILED`/缺口文案/无 draft/`assignments` 不变；`preview` 警告与 `start` 400 无 job 两条 HTTP 不变量原样保留；`smallJob`/`awaitTerminal`/`awaitSettled`/`cancelQuietly`/`statusQuietly` 与睡眠、轮询 helper 全部删除。生产 `AsyncConfig` 未改。
2. **草稿 `blockedSlots.reason` 按实际有效来源**（`backend/.../SchedulingService.java`）
   新增私有 `blockedSlotReason`，只经 `settings.entries(CLASS_UNAVAILABLE)` + `ConstraintEntryMatcher.classSlot`（用 `null` 班级表达「全校条目」）判断，输出 `全校规则` / `指定班级规则（本班）`，重叠时 `全校规则、指定班级规则（本班）`；暂停的来源自动消失。DTO 仍是 `reason` string，前端灰格/title 直接可用。
3. **前端集中身份判断与草稿边界**（`frontend/src/components/constraints/`）
   `weeklyScope.ts` 新增 `isWeeklyNoClass({code})` 并替换生产代码里散落的 `CLASS_UNAVAILABLE` 字面量（`entryModel`/`entryChecks`/`entrySummary`/`useConstraintDraft`/`ConstraintEntryCard`）；`paramValues` 的通用 `classId` 处理未动。`weeklyClasses`：显式 `SELECTED` 只认班级列表（缺失/空都返回 `[]`），不再回落全校；无显式范围的旧标量 `classId` 与 `ALL` 行为保留。`plannedEntries` 对「待选班级」不再产出无 `classId` 的条目，保存 payload 不会变成全校禁排。`entryChecks.signatureOf` 的 group 签名改为 `groupKey ?? ''`，与「服务端把空分组当同一组」一致。
4. **warm-start 重载显式化**（`backend/.../TimetableProblemBuilder.java`）
   删除隐式读 ThreadLocal 的 `buildWithWarmStart(Long)`；生产调用一直显式传 `settings`。既有 `build(Long)` 包装保留给独立性能基准，改为显式 `ConstraintSettings.defaults()` 并在 javadoc 注明；3 个 `TimetableProblemBuilderTest` 调用改为显式 `defaults()`。
5. **新规则身份统一**（`ConstraintChecker`、`SchedulingService.force` 保护、`SwapPlanner` 诊断）
   关键码改用 `ConstraintType.CLASS_UNAVAILABLE.name()`；旧 25 类诊断 wire 协议与 `ConstraintViolation` 形状未动。
6. **删除无调用方成员**：`ConstraintPreviewDto` 两参构造、`DraftDetailDto` 四参构造、`ClassCapacityPlanner.availablePositions(List)` public 重载（先 `rg` 确认无调用，canonical JSON 字段与私有 `Set` helper 保留）。
7. **CapacityNotice 状态移入 composable**（新增 `frontend/src/components/constraints/useConstraintCapacity.ts`）
   watch/防抖/请求代次/卸载清理/warnings/error 全在 composable，SFC 只保留 props 与渲染。
8. **ADR-0025 补充 CLI 预算耗尽契约**（`docs/adr/0025-published-swap-cycles-and-verifier.md`）
   `--node-budget` 默认 50000、时间预算 200ms 未放宽；预算耗尽 → `SEARCH_BUDGET`/退出码 3，完整搜索无解 → `NO_SOLUTION`/退出码 2，与 README 同一条契约。

## 真实红绿与计数

| 步骤 | 命令 | 结果 |
|---|---|---|
| RED（前端 scope/空选择） | `npx vitest run --no-file-parallelism src/components/constraints/weeklyScope.spec.ts src/components/constraints/entryChecks.spec.ts` | exit **1**，`Test Files 2 failed (2)`，`Tests 4 failed \| 15 passed (19)` |
| GREEN（同一命令） | 同上 | exit **0**，`Test Files 2 passed (2)`，`Tests 19 passed (19)` |
| 前端定向 | `npx vitest run --no-file-parallelism src/components/constraints src/components/draft` | exit **0**，`Test Files 29 passed (29)`，`Tests 213 passed (213)` |
| 前端 lint / build | `npm run lint` / `npm run build` | 均 exit **0**（eslint `--max-warnings 0`；vue-tsc + vite build 通过，仅既有 chunk size 警告） |
| 仓库存量检查 | `scripts/check-sfc-size.sh`、`check-blank-checks.sh`、`check-module-boundaries.sh`、`check-ticket-status.sh`、`check-local-dates.sh`、`check-e2e-helpers.sh` | 全部 exit **0** |
| 后端编译 | `./mvnw -B -DskipTests compile` | exit **0** |
| 后端定向 | `MAVEN_OPTS="-Xmx768m -Djdk.attach.allowAttachSelf=true" ./mvnw -B -DforkCount=0 -Dtest=ClassCapacityIntegrationTest,WeeklyNoClassIntegrationTest,WeeklyNoClassSchoolScopeIntegrationTest,WeeklyNoClassPublishedAcceptanceTest,TimetableProblemBuilderTest,WeeklyNoClassEngineTest test` | exit **0**，`BUILD SUCCESS`，合计 `Tests run: 31, Failures: 0, Errors: 0, Skipped: 0`，耗时 65s |
| E2E 加载校验（不跑浏览器） | `npx playwright test --list e2e/constraints-weekly-no-class.spec.ts` | exit **0**，列出 3 个场景 |

后端定向逐类计数：`ClassCapacityIntegrationTest` 3、`TimetableProblemBuilderTest` 4、`WeeklyNoClassEngineTest` 4、`WeeklyNoClassIntegrationTest` 5、`WeeklyNoClassPublishedAcceptanceTest` 2、`WeeklyNoClassSchoolScopeIntegrationTest` 13（含新增的来源重叠/暂停用例）。

第一次后端定向以 **测试编译错误** 结束（`ValidatableResponse` 不能赋给 `Response`），属编译失败而非行为红灯；改正后重跑为上面这组结果。

## 边界与限制

- 未跑全量 `scripts/verify.sh`、Playwright 浏览器用例与真实 PG：按分工留给主线程合并后的一次性门禁。
- E2E 只做了「加载校验」，`constraints-weekly-no-class.spec.ts` 的 3 个场景只追加 reason/title 范围断言，基本流程未改，尚未执行。
- 后端定向用 `-DforkCount=0` + 768m 堆（本机内存约束），与 CI 全量路径不同；`@MockitoBean` 覆盖只作用于 `ClassCapacityIntegrationTest` 自己的 context，其他用例仍走真实调度器。
- 前端定向覆盖 constraints/draft 相关 spec；未跑其余前端目录（改动不触及）。
