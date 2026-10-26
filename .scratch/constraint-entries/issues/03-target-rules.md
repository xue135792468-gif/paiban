# 03: 其余目标型约束支持多对象与正确组合

**What to build:** 用户可以给多门课程、多个教师或多个教研时段分别配置条件，各条在对应表单和评估中生效，互不覆盖。
**Blocked by:** 02
**Status:** done（未部署，2026-10-26 后端切片完成；前端在本目录下一票）
**Spec:** ../spec.md 的全目录表（目标型 13 类，本票补齐其余 12 类）

- [x] 覆盖教研禁排、课程教室类型、不排晚自习、同课每日一次、体育下午第一节、连堂、课程分散、主科上午、教师偏好、教师首节、无课日、体育末节。
- [x] 多偏好时段采用“或”；禁排/固定无课日按并集；相同违反事实不因全局/具体目标重叠而重复惩罚。
- [x] 同课程互斥教室类型、重复条目及不可兼容标量条件明确拒绝，失败不部分写入。
- [x] 保留“全部课程”“默认体育类”和各类既有计数/参数含义。
- [x] 每类都可通过相应最小表单完成配置和重新读取；求解、草稿、调代硬/软检查与 CLI 读取同一条目语义。
- [x] 生成调代方案后修改本票目标型规则，审批按当前硬规则与软评分重验，失败不部分生效。
- [x] 全目录清单逐类标明测试证据，不能只断言两个 teacherId；先红后绿，后端完整闸门已过（独立双轴评审与页面截图随含前端的一票交付）。

**范围边界：** 不新增任意 DSL，不改变各规则的原始业务定义；阈值在 04。

## Comments

**2026-10-26 前置语义已定（用户拍定方案 1）**：

- 列表为空＝该类型未配置，**永不**表示「全部对象」。
- 「全部课程 / 全部学科 / 体育类」必须落成一条显式条目（对象留空或显式「全部」）；前端要给「全部」一个可发现入口（勾选或明确的「全部课程」选项），不能靠留空。
- 现有 5 类旧语义（`TEACHER_MEETING_BLOCKED` 学科、`SAME_COURSE_ONCE_PER_DAY`、`SPORT_NOT_FIRST_AFTERNOON`、`COURSE_SPREAD`、`PE_LAST_PERIOD_AVOID` 的课程）在迁移中各转成一条显式条目，旧配置重新打开后含义不变。
- 详见 `../spec.md` 的「空条目语义」条。


### 实现说明（后端，2026-10-26）

**条目化类型的注册点**
- `ConstraintType.ENTRY_BACKED`（`EnumSet`，13 个目标型）+ `ConstraintType.isEntryBacked()`。
  02 的 `TEACHER_UNAVAILABLE` 与 03 的 12 类都在里面；5 个阈值和 6 个单开关类型不在里面，
  04 只需把阈值常量加进这个集合，不需要改任何评估代码。
- `ConstraintType.allowsEmptyTarget()`（参数是否全部可选）说明哪些类型的**一条空条目**是合法的
  「全部课程 / 体育类」。空**列表**永远是「未配置」，两者在请求、存储、读取、评估里全程可区分。

**空条目语义（与 02 的差异点）**
- `ConstraintSettings.from(...)`：只有 `!isEntryBacked()` 的类型才用 `params_json` 合成 legacy 条目；
  条目化类型没有条目行就是未配置，`params_json` 完全不参与评估。
- 存储层 `{}` 不再被丢弃，但只在条目化类型上（它是显式「全部」条目）；阈值/开关仍丢弃 `{}`，
  否则会凭默认值凭空启用一条规则。
- `ConstraintService.entryDtos` 不再给条目化类型合成隐式条目，`GET.params` 同步为空，
  读到的就是会评估的；模板快照对条目化类型一律发 `entries`（可为空数组），不会再回灌旧 `params`。

**每类的组合语义（求解 / 草稿 / 发布 / 调代 / CLI 同一条目快照）**

| 类型 | 组合语义 |
|---|---|
| `TEACHER_MEETING_BLOCKED` | 并集：学科留空的条目覆盖全部学科，任一 (学科, 星期, 节次) 命中即一节课一次违反 |
| `COURSE_REQUIRED_ROOM_TYPE` | 每课程独立：按课程取该条目的教室类型，不同课程互不干扰 |
| `COURSE_NOT_IN_EVENING` | 课程并集：落在任意条目的课程（空 = 全部）就一次违反 |
| `SAME_COURSE_ONCE_PER_DAY` | 课程并集，按 (班级, 课程, 天) 的课对判一次，全课程条目与具体条目重叠仍只算一对 |
| `SPORT_NOT_FIRST_AFTERNOON` | 并集：空课程条目=体育类按课名判定，与具体课程条目重叠只算一次 |
| `CONSECUTIVE_LESSONS` | 逐课程条目：每个课程用自己的连排节数，课程之间不互相牵连 |
| `COURSE_SPREAD` | 课程并集，按 (班级, 课程, 天) 事实各记一次，重叠不叠加 |
| `MAIN_SUBJECT_MORNING` | 逐课程条目：每课程用自己的上午截止节次，一节课最多一次违反 |
| `TEACHER_PREFERRED_SLOT` | 同一教师的多条时段聚合为**或**集合：落在任一时段即满足，落在集合外只扣一次 |
| `TEACHER_NO_FIRST_PERIOD` | 教师并集：被点名的教师排在当天第一节各记一次 |
| `TEACHER_DAY_OFF` | (教师, 星期) 并集：同一教师可有多天无课，命中即一次 |
| `PE_LAST_PERIOD_AVOID` | 同 `SPORT_NOT_FIRST_AFTERNOON`，并集 + 每节一次 |

- `ConstraintSettings.matchesCourse` 改为对整条条目列表取并集（原来只读第一条），
  并新增 `courseScalar` / `courseText` 读取「该课程那一条」的标量，供逐课程规则使用。
- 改动点：`ConstraintChecker`（`checkLesson` / `checkGroups` / `checkOptionalHard` / `softPenalty` 四处同语义）、
  `PaibanConstraintProvider`（同类并集，注册条件从「参数非空」改为「条目非空」）、
  `SwapPlanner.placeable`（课程教室类型 + 教研禁排改为逐条目）、`SubstitutionCli`（见下）。
  `PlanVerifier` 不复制规则，继续走 `ConstraintChecker.checkHardFast`，自动一致。
- 软分不膨胀：并集判定放在「一节课只问一次」的位置（`isSport*`→`targetsSport`、`preferredSlotViolated`
  等返回布尔），重叠条目不会让 penalty 翻倍。

**保存校验（一次请求全量校验，失败零写入，错误定位 `类型显示名(类型码) 第N条: …`）**
1. 同类型在一次请求里出现多次 → 拒绝；权重 0–100；未知类型/未知参数/整数范围沿用目录 schema。
2. 对象存在性与域：`teacherId` 必须是教师；`courseId` 必须是课程（全局资产）；`subject` 必须出现在
   学科目录（教师学科 ∪ 课程学科，学校尚未填任何学科时跳过该校验）。
3. 星期 1–7（`Param.day` 范围）；`(星期, 节次)` 必须是该学期作息里真实存在的槽位；
   `beforePeriodNo` 必须是该学期出现过的节次号；学期没有作息时明确报错要求先配作息。
4. 完全相同条目 → 拒绝（含空条目重复）。
5. 同一目标的互斥/不可兼容标量条件 → 拒绝：`COURSE_REQUIRED_ROOM_TYPE` 同课程两个教室类型、
   `CONSECUTIVE_LESSONS` 同课程两个连排节数、`MAIN_SUBJECT_MORNING` 同课程两个截止节次
   （`SCALAR_SPECS` 一处声明，四类以外无此结构）。
6. 校验全部发生在删除/写入之前；任一错误即 400，`constraint_config` 与 `constraint_entry` 不变。

**`params` 兼容路径（现有前端不改照常工作）**
- `PUT /api/constraints`：不传 `entries` 时 `params` 就是**一条条目**——条目化类型即使 `params: {}`
  也落成一条显式「全部/体育类」条目（旧前端对体育类、同课每日一次、课程分散、体育末节就是发 `{}`）；
  必填参数为空仍在 `params` 路径上 400（与 02 之前一致）。
- `GET /api/constraints`：`params` = 第一条条目参数，`entries` = 全量；`TEACHER_UNAVAILABLE` 前端照旧只读 `entries`。
- 模板：旧模板（只有 `params`）照旧可套用（走同一个 `save`）；条目化类型的新模板一律存 `entries`。
- `SubstitutionCli` 快照：`entries`（可选 `id`）优先；只有 `params` 的旧快照会按同一规则合成一条条目，
  所以 CLI 不会因为「空列表=未配置」而丢掉旧文件里的规则。

**迁移**
- 新增 `V27__constraint_entry_all_target.sql`（**本票需要新迁移，理由见下**）：V26 只复制了非空
  `params_json`，而 `SAME_COURSE_ONCE_PER_DAY` / `SPORT_NOT_FIRST_AFTERNOON` / `COURSE_SPREAD` /
  `PE_LAST_PERIOD_AVOID` 这四类的「留空＝全部/体育类」正是存成 `{}`/NULL 的。在新语义下那等于未配置，
  所以 V27 给这四类补一条无对象条件的显式条目（仅在无任何条目行时补）。开关类型和必填对象类型不补。
  `TEACHER_MEETING_BLOCKED` 的学科留空但星期/节次必填，旧值非空，V26 已覆盖。

**测试证据（逐类）**

| 类型 | 纯规则（`ConstraintTargetEntriesCheckerTest`） | 公开 REST（`ConstraintTargetEntriesIntegrationTest`） | 引擎 / CLI |
|---|---|---|---|
| `TEACHER_MEETING_BLOCKED` | 学科条目+空学科条目并集取 2 | 两条落库并读回 | — |
| `COURSE_REQUIRED_ROOM_TYPE` | 两课程各自教室类型互不干扰 | 同课程两教室类型 400 且原配置不变 | — |
| `COURSE_NOT_IN_EVENING` | 两课程晚自习各一次 | 两条落库；旧 params 请求成一条 | 两条各扣 1 硬分 |
| `SAME_COURSE_ONCE_PER_DAY` | 全课程+具体重叠只 1 次 | 两条落库 | 重叠只 -1 硬分 |
| `SPORT_NOT_FIRST_AFTERNOON` | 并集 2 次 / 软分 140 | 两条落库 | — |
| `CONSECUTIVE_LESSONS` | 逐课程判一次 / 软分 60 | 同课程两连排数 400 | — |
| `COURSE_SPREAD` | (班级,课程,天) 只 1 次 / 软分 50 | 两条落库 | — |
| `MAIN_SUBJECT_MORNING` | 各课程自己的截止节次 2 次 | 两条落库 | — |
| `TEACHER_PREFERRED_SLOT` | 同时段「或」，只 1 次违反 | 两条落库 | CLI 2 条偏好 → plan 无解 / verify FAIL |
| `TEACHER_NO_FIRST_PERIOD` | 两教师各一次 | 两条落库 | 审批重验 409（`SwapApprovalReverificationIntegrationTest`） |
| `TEACHER_DAY_OFF` | (教师,天) 并集 | 空列表=未配置、两条落库 | — |
| `PE_LAST_PERIOD_AVOID` | 并集 2 次 / 软分 70 | 两条落库 | — |

- `ConstraintEntriesCheckerTest`：新增「条目化类型只有 `params_json` → 未配置」，阈值仍走 legacy 回退。
- `ConstraintEntryMigrationTest`：V26→V27 断言四类各得一条 `{}` 条目、开关类型无条目、未配置类型不补。
- `SubstitutionCliTest`：新增偏好时段条目与旧 `params` 快照两条路径（同一结论）。
- `ConstraintTargetEntriesIntegrationTest`：12 类一次请求各 2 条共 24 行；互斥/标量冲突/未知教师/未知课程/
  未知学科/超作息 400；模板 12 类各 2 条往返；`params: {}` → 一条空条目且设置里真实生效。
- 门禁：`cd backend && ./mvnw -B test` → **687 通过 / 0 失败 / 5 跳过**（02 时 658，本票 +29）。
  前端未改动，现有页面只发 `params`（教师禁排发 `entries`），行为已由上表兼容路径覆盖。

**未做项与风险**
- 前端表单（每类多条目编辑、「全部」可发现入口）在下一票；本票只保证契约与评估。
- 阈值（含 `TEACHER_MAX_CONSECUTIVE`）仍是单值，`isEntryBacked()` 之外的老访问器只为它们保留。
- 学科校验依赖「教师学科 ∪ 课程学科」目录；两处都为空时无法校验，退化为接受。
- 保存仍是整学期替换，条目 `id` 只做稳定回传（与 02 相同）；删除/新增的审计粒度不在本票。
- `COURSE_NOT_IN_EVENING` 等硬默认类型被设为软规则时，`softPenalty` 仍不计分（既有口径，本票未扩大）。
- 真实 PostgreSQL 上 V27 尚未实跑（本地 H2 覆盖）；部署时按 runbook 先备份再随 V1..V27 顺序验证。

### 实现说明（前端，2026-10-26）

**形态判定（不硬编码 13 类名单）**
- `entryModel.typeShape(row)` 只看目录 `params` 形态，把 24 类分成三段：无参数＝`SWITCH`、
  单个 `INT`＝`THRESHOLD`、其余＝`ENTRY`。与后端 `ConstraintType.ENTRY_BACKED`（13 类）逐一对齐；
  04 把阈值扩成多条目时，只要目录形态变化，前端自动跟着变。
- 唯一显式的码表是 `SLOT_SET_TYPES = ['TEACHER_PREFERRED_SLOT']`：它与 `TEACHER_UNAVAILABLE`
  参数声明完全相同（教师/星期/节次），但语义是「任一时段即满足」而非「这些时段都禁排」，
  目录表达不了这层差别。`allTargetLabel()` 则从目录标签自带的「（留空表示…）」里读词，不再另写一份。

**组件与纯函数清单（行数）**
| 文件 | 行 | 职责 |
|---|---|---|
| `paramValues.ts` | 115 | 值裁剪（`filledValues`/`hasValue`）、星期/教室类型文案、`fieldTestId`、ID 参数对应哪张目录 |
| `entryModel.ts` | 219 | 形态判定、`allTargetLabel`/`declaresAllTarget`、`plannedEntries`（卡片 → 请求条目，含位置与 id）、`draftEntryCount` |
| `entrySummary.ts` | 100 | `formatEntrySummary`/`formatSlot`/`slotCardSummary`：每条一行摘要 |
| `entryChecks.ts` | 233 | `duplicateMessages`、`missingParamWarnings`、`scalarConflicts`（镜像后端 `SCALAR_SPECS`）、`entryErrorsOf`（服务端位置 → 卡片） |
| `draftRules.ts` | 128 | `countsOf`（唯一计数口径）、`groupRows`、`buildSavePayload` |
| `useConstraintDraft.ts` | 327 | hydrate（偏好时段按教师合并成一条卡片）、增删改条目/时段、`conflicts()` |
| `useConstraintSave.ts` | 72 | 保存前预检（互斥 → 必填）+ 单次 PUT + 错误回填（视图瘦身用） |
| `ParamField.vue` | 137 | 按目录 `type` 渲染一个参数控件（条目卡片与阈值表单共用） |
| `ConstraintEntryList.vue` | 100 | 条目列表：空状态、卡片、底部添加 |
| `ConstraintEntryCard.vue` | 225 | 一条卡片：摘要、删除二次确认、错误/重复提示、「全部」勾选、偏好时段时段区 |
| `ConstraintSlotRow.vue` | 92 | 偏好时段里的一个（星期, 节次）行 |
| `ConstraintParamForm.vue` | 70 | 阈值单值表单（改用 ParamField） |
| `ConstraintTypePanel.vue` | 259 | 类型级控制 + 三种形态分派 + 偏好时段「或」说明 |
| `ConstraintsView.vue` | 493（≤ 504） | 页面装配；保存逻辑移入 `useConstraintSave` |

**「全部」入口形式：勾选框（不是「全部课程」下拉项）**
- 可空对象参数上出现 `el-checkbox`「适用于全部课程 / 适用于全部学科 / 适用于体育类」，
  勾上时对象下拉禁用（不允许同时表达两种目标）；新卡片默认勾上，所以「全部」永远是**显式勾选**，
  绝不由「留空输入框」表达。
- 勾选状态由 `EntryDraft.allTarget` 承载（不是「值为空」推导）：取消勾选后下拉可编辑、卡片显示
  「未选课程」，保存前提示补选；此前「值为空」与「点了全部」在 DTO 里无法区分。
- 「全部学科」（`TEACHER_MEETING_BLOCKED`）仍要求星期/节次，与后端一致。

**12 类摘要样式**
`张伟 · 周三 第2节`（教师禁排）、`全部学科 · 周三 第2节`／`数学 · 周三 第2节`（教研禁排）、
`数学 · 普通教室`（教室类型）、`数学`（不排晚自习）、`全部课程`／`数学`（同课每日一次）、
`体育类`／`数学`（体育两类）、`数学 · 连排2节`（连排）、`数学 · 上午截止第4节`（主科上午）、
`张伟 · 周一第1节 或 周三第2节（命中任一即满足）`（偏好时段）、`张伟`（不排第一节）、
`张伟 · 周三`（无课日）。缺失必填项显示 `未选教师/未选星期/未选节次`，未知 id 显示 `课程#99`，不留空白。

**payload 形状**
- 13 类条目型：`{type, entries:[{id, params}], weight, hard, enabled}`，**不带 `params`**；
  `entries` 为空数组＝该类型未配置（清空即清空，不隐式补规则）。
- 「全部」条目参数为 `{}`（后端存成一条显式空条目）；偏好时段一张卡片展开成**每个时段一条**：
  `entries: [{id, params:{teacherId, dayOfWeek, periodNo}} × N]`，服务器按教师聚合成「或」。
- 5 类阈值与 6 个开关仍走兼容路径发 `params`（关闭且从未配置的阈值不发）。

**计数口径（一处定义、两处共用）**
- `draftRules.entryCountOf(row)`：开关型 0；条目型＝`entryModel.draftEntryCount`（＝将要落库的条目数，
  偏好时段按时段计）；阈值有值＝1。
- 顶部「具体条目 M 条」与左侧每类「N 条」都走 `countsOf`（`draftEntryCount` 同一函数），
  E2E 断言「顶部总数＝各类导航之和」。

**Playwright（`frontend/e2e/constraints-target-entries.spec.ts`，4 用例，串行共用一学期）**
1. `两门课程各加一条「不排晚自习」，保存后两条都在且重复保存不增删` — 断言卡片摘要、左侧 2 条、接口两条、刷新后仍在、原样再保存仍 2 条。
2. `同课每日一次加一条显式「全部课程」，刷新后仍显示全部课程` — 勾选框可见且已勾、摘要 `全部课程`、接口 `params:{}`、刷新后不变。
3. `同一教师偏好时段一条里加两个时段，摘要保持「或」` — 摘要 `张伟 · 周一第1节 或 周三第2节（命中任一即满足）`、接口 2 条同教师、刷新后 1 卡 2 时段。
4. `同一课程两个教室类型：保存被拒，错误落在对应条目，不产生部分写入` — 保存前预检告警可见、未发请求（接口该类型无条目）、输入保留。
- 下拉选择按 `aria-controls` 锁定当前 select 的 popper，避免多卡片并存时命中上一张卡片残留的下拉。
- 第 5 个用例 `十二类目标型逐一打开并截图（人工复核用，不作断言）`：逐类打开并 `capture()` 到
  `E:/paiban-wt/screenshots/phase11/constraint-entry-<码>.png`（初中模式隐藏的教研禁排跳过），
  2026-10-26 实跑 11 张，人工看过 `SAME_COURSE_ONCE_PER_DAY`（勾选「适用于全部课程」+ 下拉禁用）与
  `TEACHER_PREFERRED_SLOT`（一卡两时段、摘要「或」）。
- 组件单测：`frontend/src/components/constraints/` 共 10 个 spec / 82 用例；全仓 `npm run test:unit` 41 文件 / 323 用例。

**与规格/后端的差异与风险**
- 目录 `GET /api/constraint-types` 没有 `entryBacked`/`allowsEmptyTarget` 字段，前端只能从 `params`
  形态推断（单个 `INT` 视为阈值）。若将来出现「单参数条目型」或阈值改为多条目以外的形态，需同步调整
  `typeShape`；建议后端在目录 DTO 上补 `entryBacked` 一劳永逸。
- 偏好时段的多时段是**前端聚合**：一条卡片 ↔ N 条后端条目。若后端将来支持一个条目内多时段，
  这里要改回直传。删除整张卡片＝删除该教师的全部时段条目（与服务器「整学期替换」一致）。
- 同一目标的互斥条件（教室类型/连排节数/上午截止）前端**只提示不修改**：顶部预检条列出整体结论
  （`constraint-precheck-error`），同时 `scalarConflictsByCard` 把同一条提示落到涉及的每张卡片
  （`constraint-entry-conflict`），保存被阻止且不发请求；服务端 400 的「第N条」仍会落回对应卡片
  （`entryErrorsOf` 按请求位置映射，偏好时段一卡多位置也能定位）。
- 取消勾选「全部」又没选对象时，`targetUnresolved` 让该卡片既不计数也不落库，并提示「请选择目标，
  或勾选「全部课程」」，避免被服务端把 `{}` 存成一条「全部」条目。
- 本轮 `scripts/check-blank-checks.sh` 在**未改动的后端**上失败：`ConstraintService.java` 实际 6 处
  `isBlank(`，`scripts/blank-check-baseline.txt` 仍记 3（03 后端提交新增 3 处未同步基线）。
  前端不涉及该文件，未擅自修改后端或抬基线，留给 03 后端作者处理。

**2026-10-26 上线记录**

- 后端（`fe05ba6`）：`ConstraintType.ENTRY_BACKED`（13 个目标型）+ `allowsEmptyTarget()`；12 类各自的多条目组合语义（禁排并集、偏好时段「或」、标量规则逐条）；保存校验覆盖对象域/星期/作息节次/重复/互斥标量；求解、草稿校验、发布、调代、CLI 全部读同一快照。
- 迁移 **V27**：V26 只复制非空 `params_json`，而「留空＝全部/体育类」四类正是 `{}`/NULL，V27 各补一条显式空条目；`NOT EXISTS` 保证重复执行不产生第二行。
- 前端（`fcf8ae9`→`53e5e66` 后合入）：12 类改成条目列表；「全部课程/全部学科/体育类」用**勾选框**表达（不允许靠留空），未选目标时不落库并提示；偏好时段一条多时段显示「或」；互斥在保存前预检 + 服务端 400 落到具体条目；顶部/左侧计数同源。
- 线上冒烟发现并修复了一个**既有缺陷**（非本票引入）：`ConstraintChecker.checkInternal` 只在 `hardResult=true` 时调用 `checkOptionalHard`，而日级规则（无课日、连堂、偏好时段等）都在其中，草稿校验与**发布**都走 `hardResult=false` 路径 → 硬的无课日规则从来不阻断任何流程。修复：两条路径都调用（内部本就用 `isHard` 把关），并对局部移动按受影响课节过滤，避免把别人课节的冲突带回来（`fea5e86`，新增 2 条回归测试）。
- 门禁：后端 **689** 绿（含 2 条新回归测试）、前端 unit 323、SFC/isBlank 棘轮过、Playwright 59 全绿。
- 线上冒烟 8/8：四类一次保存 → 各类型条目数正确（dayOff 2 / oncePerDay 1 / sport 1 / 教师禁排 3）；两位被禁教师周五都被拒、未列入的教师不被拦；显式「全部课程」条目对未点名的课程生效；同课程两个教室类型 400 且零写入。

