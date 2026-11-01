# 03: 空堂越多代价越高，求解与校验一致

Status: done
Priority: P2
**What to build:** 相同规则下，算法能区分空1节与空3节，优先减少可避免空堂。
**Blocked by:** None
**Spec:** ../spec.md
**Gate:** 已实现、集成、复审，并通过冻结版本完整门禁。最终执行结果、跳过项和真实数据库边界见../acceptance.md；未部署。

- [x] 先固定审查证据A2：软权重40时，1个有效空位扣40、3个扣120；旧求解实现不能通过该断言。
- [x] 同班最早与最晚课程之间，只统计真实存在且允许上课的空位置；当天边缘无课不计内部空堂。
- [x] 本票先覆盖现有作息，无新禁排要求时保持既有硬判定；为04接入主动留空提供明确、共用含义。
- [x] 求解器、校验器和调课软增量计算符合相同定义；硬配置仍判断有无违规，未启用不扣分。
- [x] 覆盖多个班级、多天、无空堂、多个空堂及不连续作息编号，避免只测一个分数。
- [x] 定向回归通过，主模型确认评分含义，最终全量由06收口。

## Delivery（历史定向记录；最终收口见06）

- 分支：`codex/weekly-t03`，基点 `2352f04`（含票01晚自习计数的既有修复，未回退）；报告前合入集成 tip `2c54457`（票02 调课环修复），未更新集成分支。改动 `PaibanConstraintProvider.java` + `ConstraintChecker.java` + `EngineConstraintTest.java` + `ConstraintCheckerTest.java`（本票）。
- 根因：求解器 `classGapMinimized` 只用 `periodNos` 排序后按 `sorted[i]-sorted[i-1]-1` 推算空位，再按「班级/星期」整组扣一次权重；作息缺号（真实只有第1、3节）时凭空造出第2节，且空3格仍只扣一份，故审查证据A2 两个输入都是 `0hard/-40soft`。校验器 `checkClassGap`/`softPenalty` 用 `max-min+1-used.size()`，在同一缺号课表上也会虚增一格。
- 修复（求解器）：`TimetableSolution.periods` 已是 `ProblemFactCollectionProperty`；按「班级+星期」聚合已占课节后 `join(PeriodSlot.class, Joiners.equal(当天))`，对首末之间、真实存在且未被占的每一格单独 `penalize(score(control))`（硬=ONE_HARD，软=权重），软分自然按格累加。判断抽到类内私有纯函数 `isInternalGap(usedPeriodNos, slot)`，不再使用节次编号跨度。普通连堂、教师连续、间隔等其它规则未改。
- 修复（校验器）：抽出私有纯函数 `internalGapCount(ctx, classDayRows)` —— 只遍历 `ctx.periods()` 里真实存在、且与首末课程同一天、位于首末之间的 `PeriodView`，未被占才计数；硬分支（有内部空位即报违规）与软分支（`weight × 格数`）共用该纯函数。调课 `SwapPlanner`/`PlanVerifier` 的软增量经 `ConstraintChecker.softPenalty` 自动继承同一含义。
- 硬/软语义与范围：硬模式只保证违规有无一致（求解每格扣 1 hard，校验报 1 条 `CLASS_GAP_MINIMIZED`），不混同硬违规数与软扣分；未启用 `control == null` 时不注册、不扣分；票03 不新增 `CLASS_UNAVAILABLE`，主动留空豁免由票04 接入。
- 既有测试调整：`EngineConstraintTest.classGapIsPenalized` 原先把 fact 列表只给两个已占节次却仍然期待扣分，即依赖被废弃的「按编号跨度虚构缺号」语义；现改为提供周一完整 1..5 作息事实，空堂真实存在。
- TDD 红：先加 `EngineConstraintTest` 与 `ConstraintCheckerTest` 的空堂用例。命令 `C:/Users/liu/AppData/Local/Programs/GitPortable/bin/bash.exe` → `source .tools/env.sh; cd backend; MAVEN_OPTS="-Xmx512m" ./mvnw -B -DforkCount=0 -Dtest=EngineConstraintTest,ConstraintCheckerTest test` → `Tests run: 66, Failures: 5, Errors: 0, Skipped: 0`，退出码 1。失败断言（求解 4 条、校验 1 条）：连续 1..5 作息中课在 1&5 期望 `-120` 实得 `-40`（1&3 的 `-40` 旧实现恰好相同，故此断言未红）；真实作息只有 1&3 期望软 `0` 实得 `-40`；跨班跨天 4 格期望 `-160` 实得 `-120`；硬配置在缺号作息（无真实空位）期望 `0 hard` 实得 `-1 hard`；校验真实作息只有 1&3 期望软 `0` 实得 `40`。与审查证据A2 的旧 `0hard/-40soft` 一致。
- TDD 绿：改 provider + checker 后同一命令 → `Tests run: 66, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，退出码 0（EngineConstraintTest 53、ConstraintCheckerTest 13）。
- 扩展定向回归（同一 provider/checker 公共 seam，非镜像实现）：合入票02 前 `-Dtest=EngineConstraintTest,ConstraintCheckerTest,ConstraintEntriesCheckerTest,ConstraintRequirementControlsCheckerTest,ConstraintScopedThresholdsCheckerTest,ConstraintTargetEntriesCheckerTest,PlanVerifierTest,SubstitutionCliTest` → `Tests run: 130, Failures: 0, Errors: 0, Skipped: 0`，退出码 0（含 `PlanVerifierTest` 8、`SubstitutionCliTest` 16 走调课软增量）；`-Dtest=SwapPlanPropertyTest` → `Tests run: 1, Failures: 0`，退出码 0。
- 合并后复跑（合入 `2c54457`，含票02 `SwapPlanner`）：主命令 `Tests run: 66, Failures: 0, Errors: 0, Skipped: 0`；扩展集（加票02 的 `SwapPlannerTest` 24 项）`Tests run: 154, Failures: 0, Errors: 0, Skipped: 0`；`SwapPlanPropertyTest` `Tests run: 1, Failures: 0`。三条命令退出码均为 0。
- 覆盖：公共 `ScoreManager` 评分（1格扣40、3格扣120、缺号作息不虚构第2节、跨班跨天 4 格 -160、排满一天 0、硬配置仅在有真实空位时阻断、关闭不作用）与公共 `ConstraintChecker`（`softPenalty` 40/120、`checkHard` 真实空位才报违规、缺号作息不报、跨班跨天 160、首末之外不计），无 private 反射。
- 边界与未验证项：本票只跑定向单元/纯 seam 测试，没有执行 HTTP 端点校验与 `ConstraintCatalogueAcceptanceTest` 等 `@SpringBootTest`（`test` profile 是内嵌 `jdbc:h2:mem:paiban_test`，本票未运行该层，不据此推断通过）；该 HTTP 集成层、Playwright 与完整门禁由票06 收口，真实 PostgreSQL 走部署门禁或同提交 CI。票01 的 `EVENING_MAX` 计数谓词未被本票触碰。


2026-11-02最终收口：本票接受项已实现并经复审，冻结版本完整门禁通过；最终结论以[统一验收](../acceptance.md)为准，以上定向与待验描述为当时快照。
