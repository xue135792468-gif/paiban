# 06: 完成卡片摘要、增删计数与跨流程页面验收

**What to build:** 将已可用的规则操作整合成经确认的折叠卡片页面，用户能清楚看懂生效条目和对象例外，并完成保存及模板操作。
**Blocked by:** 03, 04, 05
**Status:** done（2026-10-26，未部署）
**Spec:** ../spec.md 与 01 的已确认原型

- [x] 四组默认折叠；显示启用类型/总类型与生效条目数，顶部计数与渲染使用同一份状态。
- [x] 卡片有名称、说明、共用开关/软硬/权重；硬规则隐藏权重，保留既有学校模式差异。
- [x] 目标/阈值条目有摘要、编辑、删除与添加；六个单开关类型没有加减行。覆盖空状态、长列表、默认值与被覆盖值。
- [x] 保存入口始终容易找到；失败定位、未保存状态、重新打开与模板往返清晰，不丢掉 02–05 的最小功能。
- [x] 24 类目录清单全部关闭，调代方案在配置改变后按当前规则重验；不能仅用列表保存成功代表约束已生效。
- [x] 组件测试验证增删/计数/空状态，Playwright 完成配置→求解/草稿检查→模板→重新打开的验收并留图。
- [x] 双轴验收闸门（后端全量 + 前端 lint/build/unit/SFC 尺寸/isBlank + 5 个约束 E2E）在本票分支跑完；`verify.sh --full` 与部署留在集成分支统一执行。

**范围边界：** 本票整合可用行为，不承担前票未实现的业务规则；自然语言开关仍保持原样。

## 实现说明（2026-10-26）

### 布局被 01 的确认取代：本票第一条的「四组默认折叠 + 卡片」是旧文案

01 的 Comments 记录了用户 2026-10-25 在实际浏览器里确认**变体 B（主从布局）**：左侧常驻类型导航 + 右侧当前类型编辑面板 + 顶部吸顶汇总带。本票原本写的「四组默认折叠」「卡片式分组」随之作废：

- **分组**体现在左侧导航的分区标题上（冲突类 / 资源类 / 教学规律 / 教师偏好），不做折叠卡片，也没有「收起后错误不可见」的问题——导航始终列出全部类型并用红点标冲突。
- 右侧面板仍然是一张类型卡（名称、说明、共用开关/软硬/权重 + 条目列表），这是「当前类型」的唯一渲染，不是 24 张并列卡片。
- 计数口径按 2026-10-25 用户拍定：**「已启用 N / 24 类」与「具体条目 M 条」分开**，开关型不计入条目数；分组头同样分开展示（`N/M 类型 · K 条`）。

### A. 24 类逐类「真的会生效」验收

`backend/src/test/java/com/paiban/scheduling/ConstraintCatalogueAcceptanceTest.java`：表驱动的 24 行，每行都通过公开 REST 保存代表性配置 → 造一份最小草稿 → 断言 `GET /api/timetable-drafts/{id}/validation` 的 `conflicts` 出现该类型自己的 code；硬规则另外断言 `POST .../publish` 返回 409（能被拦截才是生效，不是只出现一行提示）；软规则另外断言独立重算的 `ConstraintChecker.softPenalty` 从 0 变正、且不进 conflicts；6 个单开关类型额外断言关掉后不再报出。另有一条守卫用例断言表的 24 个类型与 `ConstraintType.values()` 完全一致，将来加第 25 类会直接红。

| # | 类型 | 触发方式（最小草稿） | 断言 code | 软罚分 |
|---|---|---|---|---|
| 1 | TEACHER_CONFLICT | 同一教师同一节在两个班 | `TEACHER_CONFLICT` | — |
| 2 | CLASS_CONFLICT | 同一班级同一节两门课 | `CLASS_CONFLICT` | — |
| 3 | ROOM_CONFLICT | 同一教室同一节两节课 | `ROOM_CONFLICT` | — |
| 4 | ROOM_CAPACITY_FIT | 45 人班排进 10 人教室 | `ROOM_CAPACITY` | — |
| 5 | CLASS_GAP_MINIMIZED | 某班周一只排第 1、3 节 | `CLASS_GAP_MINIMIZED` | ✓ |
| 6 | HEAD_TEACHER_FIRST_PERIOD | 班主任的课本班第 2 节 | `HEAD_TEACHER_FIRST_PERIOD` | ✓ |
| 7 | TEACHER_UNAVAILABLE | 条目(教师甲,1,2) + 该教师第 2 节有课 | `TEACHER_UNAVAILABLE` | — |
| 8 | TEACHER_MEETING_BLOCKED | 条目(学科=数学,1,2) + 数学教师第 2 节有课 | `TEACHER_MEETING` | — |
| 9 | COURSE_REQUIRED_ROOM_TYPE | 条目(语文→实验室) + 语文排普通教室 | `ROOM_TYPE` | — |
| 10 | COURSE_NOT_IN_EVENING | 条目(语文) + 语文排第 9 节晚自习 | `COURSE_NOT_IN_EVENING` | — |
| 11 | SAME_COURSE_ONCE_PER_DAY | 条目(语文) + 同班同日两节语文 | `SAME_COURSE_PER_DAY` | — |
| 12 | SPORT_NOT_FIRST_AFTERNOON | 条目(体育) + 体育排第 5 节（下午第一节） | `SPORT_NOT_FIRST_AFTERNOON` | ✓ |
| 13 | CONSECUTIVE_LESSONS | 条目(语文,连排2) + 第 1、3 节 | `CONSECUTIVE_LESSONS` | ✓ |
| 14 | COURSE_SPREAD | 条目 `{}`（全部课程）+ 同日两节语文 | `COURSE_SPREAD` | ✓ |
| 15 | MAIN_SUBJECT_MORNING | 条目(语文,截止4) + 语文排第 6 节 | `MAIN_SUBJECT_MORNING` | ✓ |
| 16 | TEACHER_PREFERRED_SLOT | 条目(教师甲,1,1) + 该教师第 2 节有课 | `TEACHER_PREFERRED_SLOT` | ✓ |
| 17 | TEACHER_NO_FIRST_PERIOD | 条目(教师甲) + 该教师第 1 节有课 | `TEACHER_NO_FIRST_PERIOD` | ✓ |
| 18 | TEACHER_DAY_OFF | 条目(教师甲,周一) + 周一有课 | `TEACHER_DAY_OFF` | ✓ |
| 19 | PE_LAST_PERIOD_AVOID | 条目(体育) + 体育排第 10 节（当天最后一节） | `PE_LAST_PERIOD_AVOID` | ✓ |
| 20 | TEACHER_MAX_PER_DAY | 全局 2 + 例外(教师甲=1)，该教师单日 2 节 | `TEACHER_MAX_PER_DAY` | ✓ |
| 21 | CLASS_MAX_PER_DAY | 全局 2 + 例外(本班=1)，本班单日 2 节 | `CLASS_MAX_PER_DAY` | ✓ |
| 22 | COURSE_MAX_PER_DAY | 全局 2 + 例外(语文=1)，同班同日 2 节语文 | `COURSE_MAX_PER_DAY` | ✓ |
| 23 | EVENING_MAX | 全局 2 + 例外(本班=0)，本班 1 节晚自习 | `EVENING_MAX` | ✓ |
| 24 | TEACHER_MAX_CONSECUTIVE | 全局 3 + 例外(教师甲=2)，该教师连上 3 节 | `TEACHER_MAX_CONSECUTIVE` | ✓ |

**红过的证据（变异验证）。** 表写完全绿，因此另做一次故意破坏：把 `ConstraintChecker.checkInternal` 里的 `checkOptionalHard(...)` 调用去掉（复现 2026-10-26 那次「日级硬规则在草稿/发布路径完全不生效」的形状），同一套用例立刻 **10 行变红**——CLASS_GAP_MINIMIZED、HEAD_TEACHER_FIRST_PERIOD、CONSECUTIVE_LESSONS、COURSE_SPREAD、MAIN_SUBJECT_MORNING、TEACHER_PREFERRED_SLOT、TEACHER_NO_FIRST_PERIOD、TEACHER_DAY_OFF、PE_LAST_PERIOD_AVOID、TEACHER_MAX_CONSECUTIVE，报文统一是「配置保存成功，但草稿校验没有报出 CODE」。恢复后 25 条重新全绿。这一步说明该验收确实能挡住这一类失效，而不是只跟着实现走。

### B. 页面状态与同源计数

- **同源计数**：`frontend/src/components/constraints/useConstraintDraft.ts` 的 `counts`（`draftRules.countsOf(rows)`）是唯一来源。顶部 `ConstraintSummaryBar` 直接吃 `counts`；左侧 `ConstraintTypeNav` 的每项标签走 `draftRules.entryCountOf(row)`、分组头走 `draftRules.countsOf(groupRows)`，三个数都出自同一批 `TypeDraft`。`useConstraintDraft.spec.ts` 新增用例断言「顶部 = 各分组之和 = 每类条目之和」，并把某一类扩到 22 条后计数仍一致（长列表不截断）。初中模式隐藏的类型仍计入 `counts` 且仍在 payload 里（既有用例「初中模式隐藏教研活动禁排，但它仍在提交范围内」保持通过）。
- **冲突红点**：新增 `useConstraintDraft.conflictCodes()`，把页面在保存前已能识别的三类问题（重复条目、同一对象互斥标量、同一作用域两条例外）映射成类型 code；`ConstraintsView` 的 `errorCodes` 由它与服务端 `saveReport.codes` 合并，左侧该类型的状态点在**按下保存之前**就是红的。视图因此只多了 5 行（411 → 416 行）。
- **其余状态**（未保存提示、保存中/失败定位到类型与第 N 条、重新打开不回退、空列表＝未配置、阈值全局/例外「覆盖全局 N」、偏好时段「或」摘要、模板往返恢复）沿用 02–05 的组件，本次用测试与截图逐项确认，未另写实现。

### C. 跨流程与证据

- **调代差分语义（设计取舍，不是漏测）**：`substitution/plan/PlanVerifier` 的硬规则判定是**差分**的——它在「变更前」和「变更后」各自跑一遍 `checkHardFast`，按 `code|lessonId|message` 做多重集相减，只把**新引入**的违反记为 issue（`checkHardRules`）。理由：审批要回答的是「这次调代是否让课表变坏」，而不是「这份历史课表是否完美」；基线里本就存在的硬违规（历史数据、刚改严的规则）不能让所有调代方案永久不可批。因此「生成方案后新增禁排/改阈值」能否拒绝整单，取决于该方案是否**因此产生了新的**违反：`SwapApprovalReverificationIntegrationTest` 的四条用例（生成后加禁排、加目标条目、收紧阈值 → 整单 409；给**别的**教师加例外 → 仍可批）固定了这个语义。软分则不取差分：`softPenalty` 每条日期都重算 before/after 并求和差值，硬规则不进软分（`weight()` 对 hard 返回 0），本次 24 类验收里也断言了「硬规则软罚分为 0」。
- **Playwright**：新增 `frontend/e2e/constraints-closeout.spec.ts`，5 条用例逐项对应本票的页面状态（`default` / `long-list` / `threshold-global-exception` / `template-roundtrip` / `save-failure`，后两条同时断言模板往返后的计数与「重复条目 → 第 2 条红字 + 左侧红点」）。原有 4 个约束 spec（entries / target-entries / templates / threshold-scopes）全部重跑通过，未回退。
- **截图**（仓库外）：`E:/paiban-wt/screenshots/ticket06/default.png`、`long-list.png`、`threshold-global-exception.png`、`template-roundtrip.png`、`save-failure.png`。

### 测试数字

- 后端全量 `./mvnw -B test`：86 个测试类，**745 通过 / 0 失败 / 0 错误 / 5 跳过**（新增 24 类验收 25 条：24 参数化 + 1 目录守卫）。
- 前端 `npm run test:unit`：45 文件 / **363 通过**；`lint`、`build`、`check-sfc-size.sh`（416 ≤ 600）、`check-blank-checks.sh` 全部通过。
- E2E：新增 5 条 + 既有 4 个约束 spec 15 条，共 **20 条通过**。

### 未做 / 风险

- 未部署：本票只在自己的 worktree 与集成分支上验收；`verify.sh --full`、部署与线上冒烟按项目规则在合并后的集成阶段统一做。
- `PlanVerifier` 的差分语义意味着「基线里已有的硬违规」不会阻止调代审批，这是 ADR-0025 的取舍；若将来要求「审批顺带清账」，需要另开票据，不在本票悄悄改。
- 24 类验收用的是合成教师/课程/教室，覆盖的是「配置 → 草稿判定 → 发布拦截」这条链路；求解器实际出解质量仍由 perf/求解测试负责，本票不重做。
