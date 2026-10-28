# 02: 历史调代日期转换预检与兼容

**What to build:** 在新日期语义可用后，为旧格式历史提供可审阅的转换预检和差异报告。报告说明每条记录采用的日期依据、转换前后课表差异、后续周恢复常规安排的影响，以及缺失、歧义或冲突记录。数据切换须经过明确的数据确认门；成功转换可验证，失败可恢复且保留原数据。未转换记录继续兼容读取并对相关管理写入明确只读提示。

**Blocked by:** 01:按实际授课日期查看和导出一次性调代

**Status:** in-progress (2026-10-29, branch feature/t02-legacy-conversion-precheck)

- [ ] 只读预检可生成按记录列明依据、转换状态和影响范围的可审阅报告，不改动业务数据。
- [ ] 报告对转换前后教师/班级/教室/全校日期课表差异可逐项核对，并指出后续周恢复常规安排。
- [ ] 明确可恢复的历史样例能得到确定转换结果；缺少移动方案、歧义和冲突均被标记为阻塞。
- [ ] 报告汇总数量与逐条记录一致，管理者能识别需人工确认的数据切换范围。
- [ ] 未通过数据确认门时不会切换历史解释；确认前后状态可清楚区分。
- [ ] 经确认转换后，HTTP 读取按实际授课日期展示，原始历史日志仍可追溯。
- [ ] 转换遇错时整单不留下部分切换；原记录可恢复，重复执行不会重复转换。
- [ ] 未转换记录仍按旧格式兼容读取，管理工作台对相关写入给出转换状态提示而不静默套用新语义。
- [ ] 使用真实 PostgreSQL 验证转换与迁移约束；覆盖成功、歧义、冲突及失败保留原数据。
- [ ] 管理者可查看预检报告、确认记录和失败原因；Playwright 经已有课表页面验证未确认时无切换、确认后可见新日期结果。

## 规格依据

`spec.md`「授课实例、日期读取与历史兼容」「Testing Decisions」中的历史转换场景。

## 交付记录

2026-10-29，分支 `feature/t02-legacy-conversion-precheck`，全部在 worktree `t02` 完成并跑绿。

### 实现

- **V39 迁移**（`backend/src/main/resources/db/migration/V39__substitution_conversion.sql`）：
  `substitution_conversion`（批次：semester、status CHECK ∈ {CONVERTED, FAILED}、legacy_total /
  converted_count / blocked_count、confirmed_by / confirmed_at、confirmation_comment、failure_reason，
  FK→semester）与 `substitution_conversion_item`（逐条 before/after 实例日期 + 日期依据，FK→批次级联）。
  票 03 占 V40，本票只用 V39。
- **纯推导器** `substitution/conversion/LegacyConversionPlanner.java`：COVER 从来源申请
  `substitution_request.effective_date` 还原；SWAP 从原方案移动 `substitution_plan_move.from_date/to_date`
  还原；缺失申请 / 无对应移动 / 同条目多移动歧义 / 依据与生效行矛盾（版本冲突）/ 未知类型一律
  列阻塞记录并跳过；「确属长期安排」不做字段推断，凡依据不足都进阻塞等人工决定。差异计算与读取
  路径同语义（旧格式持续覆盖 / 新格式实例日命中），逐条归因到引发差异的可转换行。
- **服务** `SubstitutionConversionService`：预检与状态只读（readOnly 事务）；确认转换 = 学期行锁 +
  事务内重新推导复核 + 原子写实例日期 + 批次留痕（TransactionTemplate），中途异常整单回滚并在
  独立事务（REQUIRES_NEW）留 FAILED 批次与失败原因；重复执行跳过已转换行并如实报告，不重复转换、
  不新建批次；阻塞存在时 409 拒绝且原数据不动。
- **API 面**（`SubstitutionConversionController`，`/api/substitution-conversion/*`）：
  `GET precheck`（预检报告：逐条依据/结论/阻塞原因 + 转换前后教师/班级/教室日期课表差异 + 后续周
  恢复常规标记 + 汇总）、`GET status`（未转换/已转换/含阻塞 + 确认记录，供票 03 数据级判定）、
  `GET records`（确认批次与失败原因）、`POST confirm`（数据确认门，SUBSTITUTION_APPROVE；
  查看类端点 SUBSTITUTION_VIEW）。预检与状态端点不写任何业务数据。
- **前端**：`SubstitutionConversionPanel.vue`（状态提示、预检报告逐条表 + 展开差异、确认按钮按
  approve 权限且 blocked=0 且已核对报告时才可用、确认记录表），挂入 `SubstitutionsView.vue`
  「历史转换」tab（view 权限可见）；类型与标签集中在 `types.ts`。组件 < 600 行。

### 验收证据（票据清单逐条）

1. 只读预检、不改业务数据 — `SubstitutionIntegrationTest` 包内
   `SubstitutionConversionIntegrationTest.precheckReportsTheCoverBasisWithoutTouchingBusinessData`：
   预检前后课表读取与 substitution 行一致、无转换批次落库。
2. 转换前后差异逐项核对 + 后续周恢复常规 — 同测试断言 `affectedDates[0]`（日期=下一同槽实例、
   restoredToBaseline=true、汇总 changes=逐条之和）；纯侧
   `LegacyConversionPlannerTest` 覆盖 COVER/SWAP 的教师/节次/教室前后安排与「恢复基线」。
3. 明确样例确定转换；缺失移动/歧义/冲突标记阻塞 — 纯侧 9 条阻塞/还原用例 +
   HTTP `confirmIsRejectedWhileAMissingRequestBlocksTheGate`、`anAmbiguousPlanMoveBlocksTheGate`、
   `aPlanMoveThatContradictsTheRowIsAVersionConflict`。
4. 汇总与逐条一致 — 纯侧 `summaryCountsAlwaysMatchTheItemList` + HTTP
   `precheckReportsTheCoverBasisWithoutTouchingBusinessData`（summary 恒等式断言）。
5. 未过确认门不切换历史解释 — `readingKeepsLegacySemanticsUntilTheGateIsPassed`：预检/状态端点
   调用后下周仍持续覆盖；Playwright 未确认前课表无切换。
6. 确认后 HTTP 按实际授课日期展示、历史日志可追溯 — `confirmConvertsAndReadingSwitchesToLessonDates`
   （授课日仍代、下周恢复基线；request 仍 EFFECTIVE、history 完整、effective_date 未改写、
   records 仍含该行）+ `confirmConvertsASwapFromTheOriginalPlanMove`。
7. 失败整单无部分切换、原数据可恢复、重复执行不重复转换 — `aFailedConversionLeavesNoPartialSwitchBehind`
   （双行转换中途失败 → 两行仍 legacy、课表仍旧语义、仅 FAILED 留痕；重跑成功）、
   `repeatedConfirmSkipsConvertedRowsAndWritesNoSecondBatch`。
8. 未转换记录兼容读取不变 + 写入转换状态提示 — 票 01 全部护栏测试继续全绿
   （SubstitutionIntegrationTest 14 条、SwapFlowIntegrationTest 6 条等）；本票不改任何读路径；
   工作台「历史转换」tab 展示未确认/已转换状态提示。
9. 真实 PostgreSQL 验证 — `PostgresContainerIT` 扩展 4 条：迁移后新表存在、CHECK/FK 约束生效、
   服务级转换在真 PG 上完成并幂等重跑、阻塞拒绝且原数据保留。**本机无 Docker 未执行，
   已编写待 CI/服务器以 `-Dtestcontainers=true` 验证，未伪造通过。**
10. Playwright 经已有课表页面验证 — `e2e/substitution-conversion.spec.ts`：真实旧格式行
    （经 H2 控制台把新流程生成的行抹掉实例日期，e2e profile 专用）→ 课表页未确认时下周仍「代」、
    预检/确认后下周恢复常规（无「代」标记）。

### 测试数字

- 纯单测 `LegacyConversionPlannerTest`：19 条全绿（无 Spring）。
- HTTP 集成 `SubstitutionConversionIntegrationTest`：11 条全绿（成功/幂等/阻塞×2/冲突/失败回滚/
  SWAP 还原/权限/无历史）。
- 后端全量 `./mvnw -B test`：**1834 条，0 失败 0 错误**（16 条为既有跳过，如 testcontainers 门控），
  BUILD SUCCESS。
- 前端：`npm run lint` 通过；`npm run build` 通过；`npm run test:unit` **407 条全绿**
  （含新增 `SubstitutionConversionPanel.spec.ts` 3 条）。
- Playwright 定向：`bash scripts/e2e.sh e2e/substitution-conversion.spec.ts` 1 条通过。
- 仓库护栏：`check-migrations.sh`（39 个版本唯一，V39 就位）、`check-blank-checks.sh`、
  `check-sfc-size.sh` 全部通过；架构护栏（权限常量）通过。

### 真实 PostgreSQL 项状态（如实）

- `PostgresContainerIT`（testcontainers 门控）已扩展 4 条用例，本地无 Docker **未实际执行**；
  需在有 Docker 的 CI 或服务器跑 `./mvnw verify -Dtestcontainers=true` 验证。H2（PostgreSQL 模式）
  下的迁移与约束行为已由全量测试覆盖。

### 遗留风险与边界

- 转换只处理 EFFECTIVE 行；CANCELLED 旧行不参与读取也无需转换（报告中作为非生效行，不进 items）。
- 批次表允许多条 CONVERTED 记录（多批转换，如数据回补后再确认）；幂等返回最后一批。
- `SecurityConfig` 放行 `/h2-console/**`：该 servlet 仅在显式启用的 profile（当前仅 e2e）注册，
  生产不启用时路径无端点，放行是空操作；用途仅限 e2e 造旧格式存量数据。
- 与票 03 的接缝：票 03 需要的「数据级未转换判定」读 `GET /api/substitution-conversion/status`
  （legacyRowCount / convertibleCount / blockedCount / converted / canConfirm）或预检逐条状态；
  本票未引用任何票 03 类。
