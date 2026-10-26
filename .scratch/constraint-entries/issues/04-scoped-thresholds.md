# 04: 五类阈值支持全局默认和具体对象例外

**What to build:** 用户能设置全校默认上限，同时为个别适用对象设置不同上限；未被覆盖的对象继续使用默认。
**Blocked by:** 02
**Status:** done（未部署，2026-10-26）
**Spec:** ../spec.md

- [x] 五类分别支持：教师每日/连续上课→教师，班级每日/晚自习→行政班，课程每日→课程；界面只提供适用对象。
- [x] 全局默认＋对象例外有明确摘要、编辑和删除入口。同类型启用/软硬/权重仍共用。
- [x] 对象例外替代该对象全局阈值；例外删除恢复全局。无全局值时不对未覆盖对象凭空启用默认规则。
- [x] 同一对象或全局重复阈值拒绝；上下界和行政班学期校验仍有效，失败原子性不变。
- [x] 具体例：全局 6、教师甲 3，甲第 4 节触发，乙第 4 节不触发；按原计数桶验证另外四类。
- [x] 生成调代方案后改变对象例外/全局阈值，审批按当前规则重验；删除例外恢复全局，关闭类型关闭全部条目。
- [x] 求解、草稿、调代硬检查/软评分与 CLI 使用相同选择策略；先红后绿，独立双轴评审、完整闸门与页面证据。

**顺序说明：** 与 03 共享基础但无硬依赖；用户可按 03→04 顺序集成，避免并行改同一规则模块。

## Comments

### 实现说明（后端，2026-10-26）

**选择策略只有一处**
- `ConstraintType.thresholdScope()` 声明每类阈值的两个字段（对象字段 + 数值字段），
  `THRESHOLDS` 表就是唯一登记点：
  `TEACHER_MAX_PER_DAY`/`TEACHER_MAX_CONSECUTIVE` → `teacherId`，
  `CLASS_MAX_PER_DAY`/`EVENING_MAX` → `classId`，`COURSE_MAX_PER_DAY` → `courseId`；
  数值字段 `maxPerDay` / `maxConsecutive`。
- `ConstraintSettings.threshold(type, objectId)` 是唯一的解析实现（伪码）：
  ```
  全局 = null
  for 条目 in entries(type):
      值 = 条目的 valueField；缺失则跳过
      对象 = 条目的 objectField
      若 对象为空: 全局 = 值
      否则若 对象 == objectId: return 值        // 例外优先，立即命中
  return 全局                                   // 可能为 null＝该对象不设上限
  ```
  「例外替代该对象全局值、未列出对象继续用全局、无全局则无上限」全部落在这 8 行里，
  没有任何调用方自己再拼一遍。
- 求解、草稿校验、发布、调代硬检查/软评分、只读 CLI 都经 `ConstraintService.settings(semesterId)`
  取同一份快照后再调 `threshold(...)`：
  `ConstraintChecker.checkGroups` / `checkOptionalHard` / `softPenalty`、
  `PaibanConstraintProvider`（5 个约束各自按对象解析上限，未覆盖对象返回
  `Integer.MAX_VALUE`，即桶计数永远超不过）、`SwapPlanner`/`PlanVerifier`（走
  `checkHardFast`+`softPenalty`，不复制规则）、`SubstitutionCli`（`ConstraintSettings.from` 同一构造）。
- 软分不放大：`excess(counts, limitOf)` 对每个计数桶解析一次上限，一个桶只按超出的部分计一次；
  `TEACHER_MAX_CONSECUTIVE` 的软分也按教师逐个解析，一条例外不会改变其他教师的惩罚。

**计数桶（逐类确认，与旧实现相同）**
| 类型 | 对象（条目字段） | 计数桶 |
|---|---|---|
| `TEACHER_MAX_PER_DAY` | 教师 | `(教师, 星期)` |
| `CLASS_MAX_PER_DAY` | 行政班 | `(行政班, 星期)` |
| `COURSE_MAX_PER_DAY` | 课程 | `(行政班, 课程, 星期)`，上限按**课程**解析 |
| `EVENING_MAX` | 行政班 | `(行政班, 星期)` 且只数 `EVENING` 节次 |
| `TEACHER_MAX_CONSECUTIVE` | 教师 | `(教师, 星期)` 的最长连续段 |

**注册与契约**
- 五类加入 `ConstraintType.ENTRY_BACKED`（13 → 18 类），因此 `params_json` 不再参与评估。
- 目录参数改为「可选对象 + 必填整数」（如 `teacherId` + `maxPerDay 1–12 默认 4`），
  `allowsEmptyTarget()` 因整数必填而为 false：空条目不是合法阈值。
- `GET /api/constraint-types` 新增 `entryBacked` / `thresholdObjectField` / `thresholdValueField`，
  前端不再从「参数个数」猜形态（03 遗留风险已消化）。
- `GET/PUT /api/constraints` 里阈值走 `entries`（与 13 类一致），`params` 仍回传第一条（全局默认）。

**保存校验（仍在删除/写入之前，任一错误 400 且零写入）**
1. 上下界沿用目录 schema（1–12 / 1–8 / 0–6 / 1–8）；权重 0–100 不变。
2. 对象域：`teacherId` 必须是教师、`courseId` 必须是课程、`classId` 必须**属于该学期**
   （`SemesterScope` 现在同时持有作息槽位与学期班级集合）。
3. **同一作用域最多一条**：两条全局默认、同一对象两条 → `第N条: 与第M条针对同一作用域（全局默认 / 对象「教师#3」），同一作用域最多一条阈值`。
   完全相同条目仍由既有重复检测先报（措辞不变）。
4. 缺少数值的条目只报「参数不能为空」，不再叠加一条作用域冲突（避免误导）。

**迁移 V28（`V28__constraint_entry_threshold_scope.sql`）**
- 五类各把旧 `params_json` 转成**一条**条目：值原样复制（`{"maxPerDay":6}`），
  旧值里若带对象字段则自然成为该对象的例外（复制后由选择策略识别，无需额外分支）。
- 旧值为 `{}`/NULL 但类型已配置时，写入该类目录默认值（4 / 8 / 2 / 2 / 3）——
  这是升级前 `intParam == null → fallback` 的实际行为，不写就会静默丢掉限制。
- 没有 `constraint_config` 行的类型不补（「没配过」仍是没配过）；`NOT EXISTS` 保证可重复执行。

**测试证据**
- `ConstraintScopedThresholdsCheckerTest`（11 条，纯 checker）：注册表 18 类与 5 个作用域字段；
  全局 6 + 甲 3 → 甲 4 节违反、乙 4 节不违反；删例外恢复 6；只有例外时未列出对象无上限；
  班级每日、课程每日（按班+课程计、按课程解析）、晚自习、连续上课各一类；
  关闭类型全部条目失效；软分 3 条目 1 个溢出桶 = 1 份权重；旧 `params_json` 不再生效。
- `ConstraintScopedThresholdsIntegrationTest`（7 条，公开 REST）：全局+例外落库并读回（含 `params` 仍是全局值）、
  删例外恢复全局、只有例外、两条全局/同教师两条 → 400 且原配置不变、越界 13 与跨学期班级与未知课程 → 400、
  其余四类各自对象字段往返 + 课程桶计数、关闭类型不生效。草稿校验用
  `/api/timetable-drafts/{id}/validation` 断言，发布 409。
- `ConstraintEntryMigrationTest`：V27→写旧值→V28 后五类各一条条目（含 `{}`→目录默认、带对象字段→例外、
  NULL→默认），未配置类型为空，重复执行不新增。
- `SubstitutionCliTest`：新快照里全局默认条目照常生效（plan 仍 3 环、verify PASS）；
  连续上课例外让 `verify --before … --after after-threshold.json` FAIL，去掉例外改回全局 3 → PASS。
- `SwapApprovalReverificationIntegrationTest`：生成方案后加「教师 A 连续上课 ≤1」例外 → 审批 409、
  无替换记录；改成对教师 C 的例外 → 审批 200（未列出对象仍用全局 3）。
- 既有测试更新：`presetCatalogueHasAtLeastTwentyConstraintsWithSchemas`（阈值参数顺序与新增字段）、
  `entryBackedTypeWithOnlyLegacyParamsIsNotConfigured` + `teacherMaxPerDayReadsItsGlobalEntryOnly`（阈值不再回退旧 params）、
  `registryMarksTheEighteenEntryBackedTypes`（13 → 18）。
- 移除死代码：`ConstraintSettings.params/longParam/intParam/textParam`（24 类已全部条目化或开关型，没有单值读者）。

**未做项与风险**
- 调代**方案审批**（`PlanVerifier`）是「不得变得更差」的差分判定，所以一条本来就已违反的日级阈值不会被审批拦下；
  本票的审批回归因此构造了「方案本身把某教师两节课变成相邻」的场景。若产品要求「审批按绝对规则全量重验」，
  需要单独改 PlanVerifier 语义（会影响既有已违规学期的历史方案），不在本票范围。
- V28 的默认值以 SQL 字面量写了 4/8/2/2/3，与 `ConstraintType` 的 `defaultValue` 是两份；
  改目录默认值时必须同步迁移（迁移测试断言了这五个数字，可作为防线）。
- 阈值条目的 `id` 曾是整学期替换式回传（与 02/03 相同），不做按条增量更新 —— **该限制已在下方「评审修复」中消除**。
- 真实 PostgreSQL 上 V28 尚未实跑（本地 H2 覆盖），部署时按 runbook 先备份。

### 实现说明（前端，2026-10-26）

**形态判定改为读目录事实**
- `entryModel.typeShape`：无参数＝`SWITCH`；目录声明了 `thresholdObjectField`＝`THRESHOLD`；其余＝`ENTRY`。
  不再用「单个 INT 参数」猜测（03 遗留风险）。
- 计数口径收敛到一处：`draftRules.entryCountOf` 导出后同时供顶部汇总带、左侧导航与分组头使用
  （原先导航直接调 `draftEntryCount`，阈值扩成多条目后会显示 0 条——本轮修掉）。
- 阈值每条条目计 1（全局默认 1 + 每个完整例外 1），空行、缺对象或缺数值的例外都不计。

**新增/改动的文件**
| 文件 | 职责 |
|---|---|
| `thresholdModel.ts`（新） | 纯函数：作用域解析、条目（position/cardIndex/params）、计数、缺项提示、重复作用域检测 |
| `ConstraintThresholdPanel.vue`（新） | 全局默认一行 + 例外列表 + 「添加例外」+ 无全局提示 + 空状态 + 服务端错误定位 |
| `types.ts` | `ThresholdDraft`/`ThresholdExceptionDraft`、目录新增三个字段；删掉单值遗留的 `values`/`touched` |
| `useConstraintDraft.ts` | 阈值 hydrate（条目 → 全局/例外；只有 `params` 的旧响应读作全局）与 5 个 mutator |
| `draftRules.ts` | 阈值发 `entries`；关闭且无条目的阈值不发 |
| `ConstraintTypePanel.vue` | 三种形态分派 + 只把该类适用的对象交给阈值面板 |
| `ConstraintsView.vue` | 载入当前学期行政班（`/classes?semesterId=`）并作为阈值对象来源 |
| 删除 `ConstraintParamForm.vue` | 24 类已无单值表单使用者 |

**payload 形状**
```json
{ "type": "TEACHER_MAX_PER_DAY", "weight": 60, "hard": false, "enabled": true,
  "entries": [ { "id": 1, "params": { "maxPerDay": 6 } },
               { "id": 2, "params": { "teacherId": 3, "maxPerDay": 3 } } ] }
```
- 全局默认条目**没有**对象字段；例外的对象字段就是作用域。`entries: []` ＝该类型未配置。

**面板语义**
- 无全局默认时明确写「没有全局默认时，未在下面列出的教师不设上限」，且例外行标「仅该教师」；
  有全局时例外行标「覆盖全局 6」。
- 删除最后一条例外回到「还没有对象例外」的空状态；全局也删掉则回到「未设置全局默认 + 不设上限」。
- 重复作用域在保存前就被拦下（顶部预检条 + 不发请求），与 03 的互斥预检同一路径。

**单测与 E2E**
- 组件单测：`thresholdModel.spec.ts`（8）、`ConstraintThresholdPanel.spec.ts`（7），并更新
  `entryModel` / `draftRules` / `entryChecks` / `useConstraintDraft` / `ConstraintTypePanel` 的既有用例。
  全仓 `npm run test:unit` 43 文件 / 346 用例。
- Playwright `e2e/constraints-threshold-scopes.spec.ts`（4 条串行，共用一学期）：
  1. 设全局 6 + 给张伟设 3 → 显示「覆盖全局 6」、左栏 2 条 → 保存 → 接口两条条目 → 刷新后全局 6 / 一行例外 / 覆盖全局 6；
  2. 删例外回到全局 6，再删全局回到空状态并提示不设上限；
  3. 同一教师两条例外 → 保存前被拦下、不发请求、服务端无变化；
  4. 五类逐一打开并截图（`E:/paiban-wt/screenshots/phase11/constraint-threshold-<码>.png`）。
- 既有约束 E2E（`constraints-entries`、`constraints-target-entries`、`zz-junior-mode`）一并复跑通过。

### 评审修复（2026-10-26，集成分支 `feature/constraint-phase`）

双轴评审对 04–06 的发现里有四条落在本票。行为与文档各半：

**行为**
- 「无上限」只有一个表达。`ConstraintSettings.threshold(...)` 改名为 `limitOrNull(...)`，并新增 `withinLimit(type, objectId, count)`；`ConstraintChecker`（null → 跳过）与 `PaibanConstraintProvider` 都调这两个方法，provider 里的 `Integer.MAX_VALUE` 哨兵与那层「额外 filter」删掉。判定语义没变，只是不再存在两种写法。
- 阈值条目有了真正的稳定身份：`ConstraintService` 保存改为按条 upsert（带 id 更新、不带 id 插入、未出现的旧行删除），面板重新保存后全局默认与例外的条目 id 不再整批重建。上文「阈值条目的 id 仍是整学期替换式回传…」随之作废。
- V28 的 `CASE` 从「ELSE 兜底成 `maxConsecutive`」改成按五类各写一个显式分支：某个类型自己的 key 缺失时补该类默认值 4/8/2/2/3，将来新增第 6 类阈值不会悄悄继承别人的数字。没有新增迁移编号，改的是同一条 V28（本阶段尚未部署）。

**文档**
- ADR-0025 与 spec 的调代审批描述按实现改正（差分判定），详见 `docs/adr/0025` 末尾「2026-10-26 修订记录」。`PlanVerifier` 行为未改。

**测试**
- `EngineConstraintTest.thresholdExceptionAppliesPerObjectInTheEngine`：全局 4 + 教师 10 例外 1 → 该教师单日两节扣一份软分、教师 11 两节不扣（此前只有「全局条目」的引擎用例）；`thresholdWithoutGlobalOrExceptionDoesNotLimitTheEngine`：只有别的对象的例外时谁都不受限。
- `ConstraintEntryMigrationTest.v28WritesEachThresholdItsOwnCatalogueDefault`：五类各写 `{}`/NULL，迁移后分别落 4/8/2/2/3（`COURSE_MAX_PER_DAY` 与 `EVENING_MAX` 同为 2，但由两个独立分支产生）。
- `ConstraintEntriesIntegrationTest.savingAgainUpdatesInPlaceInsertsAndDeletesByEntryId`：改一条 + 增一条 + 删一条 → 原 id 不变、新条目新 id、被删的行没了，REST 回应与存储一致。
