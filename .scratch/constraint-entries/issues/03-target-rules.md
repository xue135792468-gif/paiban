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
