# rule-groups 跨票契约

Status: done（票 01–08、03b 已按本契约实现）
Date: 2026-11-07
适用：票 01–08、03b。票面与本文件冲突时，以本文件为准，并在该票「实现说明」里记录偏差。

本文件只定跨票共享的决定：数据模型、参数格式、展开位置、删除撤销、复制映射、冲突检查口径。页面细节留给各票。

---

## 0. 术语与命名

- **规则卡（card）**：规则页的一条可编辑单元，1–3 句，共用一个开关。
- **句子（sentence）**：一条独立要求，落库时对应一个 `constraint_entry.group_key`。
- **用户分组（rule group）**：本特性新增的教师组 / 课程组 / 时间组，只是批量选择，不参与评估。
- **卡片键（card key）**：现有 `constraint_entry.group_key`，把一条要求的多个槽位行合并起来。**它不是用户分组**；新代码禁止用 `groupKey` 表示分组，避免同名不同义。
- 数据库：`rule_group`、`rule_group_member`；Java：`RuleGroup`、`RuleGroupMember`；API：`/api/rule-groups`。
- 组类型：`TEACHER` / `COURSE` / `TIME`。

## 1. 数据模型

### 1.1 `rule_group`

| 列 | 说明 |
|---|---|
| `id` | identity 主键 |
| `group_type` | `TEACHER` / `COURSE` / `TIME` |
| `name` | 组名，1–64 字 |
| `semester_id` | 仅 `TIME` 组非空；教师/课程组为全局，跨学期保留 |
| `created_at` / `updated_at` | 审计时间 |
| `deleted_at` | 软删除；7 天撤销复用 `recent_deletion` |

约束：

```sql
CHECK (group_type IN ('TEACHER','COURSE','TIME'))
CHECK ((group_type = 'TIME') = (semester_id IS NOT NULL))
```

唯一性用**可移植的 `live_key`**，不用 PostgreSQL 部分唯一索引（H2 2.3 不支持 `CREATE UNIQUE INDEX ... WHERE`，本地测试会直接失败）：

- `live_key VARCHAR(160) NULL`，服务层维护：
  - 教师/课程组：`TEACHER:<name>` / `COURSE:<name>`；
  - 时间组：`TIME:<semesterId>:<name>`；
  - 软删除时置 `NULL`，恢复时重算（若同名已被新组占用则拒绝恢复并提示）。
- `CREATE UNIQUE INDEX uq_rule_group_live_key ON rule_group (live_key)`。
- 这样只约束“活着的组”，删除后可立即建同名新组，且 H2/PostgreSQL 行为一致。

### 1.2 `rule_group_member`

| 列 | 说明 |
|---|---|
| `id` | identity 主键 |
| `group_id` | FK `rule_group(id)`，ON DELETE CASCADE |
| `teacher_id` | 教师组成员 |
| `course_id` | 课程组成员 |
| `day_of_week` / `period_no` | 时间组成员；**用坐标而不是 period id** |

约束：三选一（`teacher_id` / `course_id` / `day_of_week + period_no` 恰好一组非空）；组类型与成员类型必须一致（服务层校验）。

同样不用部分索引，改用可移植的 `member_key VARCHAR(48) NOT NULL`：

- 教师成员：`T:<teacherId>`；
- 课程成员：`C:<courseId>`；
- 时间成员：`S:<dayOfWeek>-<periodNo>`；
- `CREATE UNIQUE INDEX uq_rule_group_member_key ON rule_group_member (group_id, member_key)`。

`member_key` 由服务层写入；更新成员时整体替换，重复成员在服务层去重。

**为什么时间组不用 `period_id`**：作息保存会删除被移除的课节、保留仍存在的课节；跨学期的复制和「按星期+第几节对应」都以坐标为准。坐标是稳定身份，period id 不是。票 06 的「作息节次被删除时，时间组成员同步移除」按坐标比对实现。

### 1.3 预建时间组

每个学期自动拥有可编辑的 `上午`、`下午` 两个 `TIME` 组：

- 初始成员：该学期作息中 `start_time < 12:00` 的课节 → `上午`；其余 → `下午`。
- 没有作息时建空组，等作息创建后再补（服务层幂等补齐）。
- 用户可改名、增减成员；改名不影响规则（规则引用 group id）。
- 存量学期由票 06 的迁移/启动补齐，不重复建同名组。

### 1.4 软删除与撤销

- 分组删除走 `recent_deletion`：`entity_type = 'RULE_GROUP'`，`primary_flag = true`；组和每张被删的规则卡各写一个批次。
- 分组成员不软删；组恢复后成员原样回来。
- 组名唯一索引只看 `deleted_at IS NULL`，所以删除后可以立刻建同名新组。

## 2. 规则参数里的分组引用

### 2.1 参数键

在 `constraint_entry.params_json` 里，一条槽位行**要么**写具体对象，**要么**写分组引用，不能同时写：

| 对象 | 具体键 | 分组键 |
|---|---|---|
| 教师 | `teacherId` | `teacherGroupId` |
| 课程 | `courseId` | `courseGroupId` |
| 时间 | `dayOfWeek` + `periodNo` | `timeGroupId` |

例：

```json
{"teacherGroupId": 12, "timeGroupId": 3}
{"courseGroupId": 7, "maxPerDay": 1}
{"teacherId": 42, "dayOfWeek": 5, "periodNo": 3}
```

### 2.2 展开规则（只在快照构建处做）

对每一条启用的存储行：

1. `teacherGroupId` → 取组内**仍启用/未删除**的教师，每个成员生成一行，写入 `teacherId`，删掉 `teacherGroupId`。
2. `courseGroupId` → 同上，写入 `courseId`。
3. `timeGroupId` → 取组内仍存在于该学期作息的坐标，每个坐标生成一行，写入 `dayOfWeek` + `periodNo`，删掉 `timeGroupId`。
4. 同一行同时有教师组和时间组 → 做笛卡尔积。
5. 展开出的每一行**保留原来的 `groupKey`、`enabled`、`hard`、`weight`**；`ConstraintSettings.from` 仍按 `groupKey` 合并成一条要求。
6. 组不存在、组为空、成员全部失效 → 该行展开为 0 行，规则静默失效；API 的 `invalidReferences` 标「分组已删除 / 组内没有成员」。

### 2.3 展开位置

**唯一改动点**：

- `ConstraintConfigService.settings(semesterId)`：读完 configs/entries 后、调用 `ConstraintSettings.from(...)` 前，用 `RuleGroupExpander` 展开。
- `ConstraintConfigService.settingsOf(Planning)`：保存前预览同样展开，保证预览与保存后一致。

不修改：

- `ConstraintSettings` 本身（仍只认具体参数）。
- `ConstraintChecker`、求解器、调代校验、CLI（它们只吃快照）。
- 持久化的 `constraint_entry` 行（继续存组引用，不展开落库）。

展开必须返回新的行对象，**不得修改 JPA 托管的实体**（`settings()` 是只读事务）。

**指纹影响**：`ConstraintSettings.fingerprint()` 会因成员变化而变化。这是期望行为——改组成员等于改了生效规则，旧的换课方案 token 应被拒绝。不要为了稳定指纹把组引用留在快照里。

## 3. 句子与表单模型（票 01/02）

### 3.1 `RuleSentence` 扩展

```ts
interface RuleSentence {
  // 现有字段...
  days: number[]              // 取代 dayOfWeek；多天
  teacherGroupId: number | null
  courseGroupId: number | null
  timeGroupId: number | null
}
```

- 回填：从同一 `groupKey` 的行里取 `distinct(dayOfWeek)`；组引用行直接读组 id。
- 编译：`days × periodNos` 生成槽位行；有组引用时写组键，不展开成员。
- 分组引用字段票 01/02 只预留，票 04/05/06 才接线。

### 3.2 槽位顺序与句子

- 表单顺序固定：**谁 → 什么课 → 什么时候 → 要求**。
- 句子 = 主语 + 时间 + 谓语；不用「的 / 课 / 在」这类虚词。
- 主语优先级：分组名 > 成员名 > `全校` / `所有教师` / `全部课程`。
- 时间优先级：时间组名 > `上午/下午` 折叠 > `周一、周三第 1、2 节`。
- 谓语按类型写死，例：`王老师 周五下午 不上课`、`技能课 不排在 上午`、`体育 不在最后一节`、`教导主任 每天最多 4 节`。
- 强度只显示「必须 / 尽量」；不显示分量数字。

### 3.3 全局默认范围（票 02 补齐）

为了让票 03b 的 AI 建议都能落成句子，票 02 必须支持：

| 类型 | 新增范围 |
|---|---|
| `TEACHER_MAX_PER_DAY` | `allowAnyTeacher` → `teacherId` 留空 = 所有教师 |
| `CLASS_MAX_PER_DAY` | `allowSchool` → `classId` 留空 = 全校班级 |
| `COURSE_MAX_PER_DAY` | `course` 可选 → `courseId` 留空 = 全部课程 |

其余类型现有范围不变。

## 4. 分组 API 与权限

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/rule-groups?type=TEACHER&semesterId=` | 列组及成员；`TIME` 必须带 `semesterId` |
| POST | `/api/rule-groups` | 建组 `{type, name, semesterId?, members}` |
| PUT | `/api/rule-groups/{id}` | 改名 / 整体替换成员 |
| GET | `/api/rule-groups/{id}/delete-impact` | 返回引用该组的规则卡（卡片键、句子、学期） |
| DELETE | `/api/rule-groups/{id}?mode=DELETE\|WITH_RULES` | 默认 `DELETE`：有引用返回 409 + 影响清单；`WITH_RULES` 连同规则一起删 |

- 读权限：`constraint:manage`、`foundation:manage`、`semester:manage` 任一。
- 写权限：`constraint:manage`。
- 成员请求体用 `{kind: 'TEACHER'|'COURSE'|'TIME', id?, dayOfWeek?, periodNo?}`；服务端按组类型校验。
- 教师/课程/作息列表不新增分组字段；前端一次拉取相关组，按成员 id 本地拼后缀与筛选。教师/课程数据量小，够用。
- 删除响应与现有 `delete-impact` 风格一致：`{rules: [...], message}`；`WITH_RULES` 时组写一个 `recent_deletion` 批次，每张引用规则卡各写一个批次；响应返回全部 `batchIds`，撤销时分别恢复。

## 5. 删除分组的语义

1. 无引用：直接软删组，7 天内可撤销。
2. 有引用 + `mode=DELETE`：409，返回「哪些规则在用」；用户可选择：
   - 返回改规则（前端取消删除）；
   - 再发 `mode=WITH_RULES`，组和引用规则卡一起软删，但各自写独立的 `recent_deletion` 批次（分组一个，每张规则卡各一个）。
3. `WITH_RULES` 必须按**规则卡**删，不是按单行：同 `groupKey` 的所有槽位行一起软删；卡片开关/删除的现有生命周期逻辑复用。
4. 撤销：现有 `recent_deletion` 恢复接口按批次恢复；恢复组后再恢复规则卡，顺序由处理器保证。
5. 组被规则引用但已被外部删除（数据异常）：快照展开为 0 行，页面标「分组已删除」，不阻止其他规则保存。

## 6. 复制往期规则（票 08）

接口：`POST /api/constraints/copy-rules`，body `{sourceSemesterId, targetSemesterId, baseVersion}`。

语义：

- 整套替换目标学期的**非系统规则**；两条系统底线保持目标学期自己的，不复制。
- 源规则的开关状态原样保留。
- 复制的是 `constraint_config` 类型行 + `constraint_entry` 行；`cardId`/`groupKey` 重新生成，但同一源 `groupKey` 的句子仍归到同一张新卡。
- 不复制分组本身（教师/课程组全局共享；时间组按名字对应）。

逐项映射：

| 源 | 目标 | 失败时 |
|---|---|---|
| `teacherId` / `courseId` | 全局 id 不变 | 教师/课程已删除或停用 → 卡片停用，记「教师已停用：X」 |
| `classId` | 目标学期没有该班（班级属于学期，不能按 id 对应） | 卡片停用，记「行政班不在本学期：#id」 |
| `teacherGroupId` / `courseGroupId` | 全局组 id 不变 | 组已删除 → 卡片停用，记「分组已删除：X」 |
| `timeGroupId` | 按组名找目标学期同名组，换成目标组 id | 无同名组 → 卡片停用，记「本学期没有时间组：X」 |
| `dayOfWeek` + `periodNo` | 按坐标映射到目标作息 | 任一坐标不存在 → 卡片停用，记「第 x 节在本学期作息中不存在」 |
| 无对象参数（全局默认、数量） | 原样 | 无 |

- 失败按**卡片**处理：一张卡只要有一句映射失败，整张卡复制但停用，并列出原因；同一张卡的其他句子照常复制。
- 复制后写审计；目标学期版本推进；`baseVersion` 不匹配返回 409。
- 响应：`{copiedCards, disabledCards, items: [{cardKey, sentence, reason}]}`。
- 规则页的「本学期用不上」区只列 `disabledCards`，用户可逐张启用/删除；启用前需先改掉失效引用（前端用 `invalidReferences` 提示）。

## 7. 保存时的冲突提醒（票 07）

纯前端函数，输入：当前卡片 + 已加载的分组成员。输出：冲突列表，不改数据、不阻断保存。

只比较**必须（HARD）**规则，且只比较「同一对象 + 同一时段」的正反要求：

| 方向 | 类型 |
|---|---|
| 不排（负） | `CLASS_UNAVAILABLE`、`TEACHER_UNAVAILABLE`、`COURSE_UNAVAILABLE`、`TEACHER_NO_FIRST_PERIOD`、`TEACHER_DAY_OFF`、`PE_LAST_PERIOD_AVOID` |
| 排在（正） | `TEACHER_PREFERRED_SLOT`、`MAIN_SUBJECT_MORNING` |

- 先把组展开成具体对象（教师/课程/班级）和具体槽位（星期 + 第几节），再求交集。
- `TEACHER_NO_FIRST_PERIOD` = 每天第 1 节；`TEACHER_DAY_OFF` = 指定星期的全部节次；`PE_LAST_PERIOD_AVOID` = 每天最后一个节次。
- `MAIN_SUBJECT_MORNING` 的正向集合 = 每天 `periodNo <= beforePeriodNo`。
- 数量型、形态型（连堂、分散、不空堂、上限）暂不参与；「尽量」规则之间不提示。
- 命中时提示两条规则的句子 + 受影响成员 + 槽位；按钮「返回修改」/「仍然保存」。保存请求照常发出；服务端不做这项拦截。
- 单元测试至少覆盖：教师组 × 时间组、课程组 × 主科上午、软规则不提示、组内空成员不误报。

## 8. 迁移与清空（票 03/04/06）

| 版本 | 内容 |
|---|---|
| V56 | 删除所有学期的非系统 `constraint_entry` / `constraint_config`（保留 `TEACHER_CONFLICT`、`CLASS_CONFLICT`）；清空 `constraint_workspace`；删除 `constraint_template` 数据并 `DROP TABLE` |
| V57 | 建 `rule_group` / `rule_group_member` 与索引 |
| V58 | 为存量学期补齐「上午」「下午」时间组；按现有作息坐标填成员（票 06） |

- V56 不动已发布课表、草稿、调代、AI 运行记录。
- V56 前必须备份生产库；迁移注释写清「测试阶段，产品所有者确认可清空」。
- 迁移在真实 PostgreSQL 容器验证（沿用 `PostgresContainerIT` 方式）。
- 系统底线不依赖 `constraint_config` 行是否存在：规则页顶部的两句是固定文案；V56 后两条系统行继续生效。

## 9. AI 面板（票 03b）

- 面板从高级页搬到规则页，可折叠；后端 `ConstraintProposalController` 与模型调用不动。
- 建议对象 `ConstraintProposal` 的 `type` 必须是 16 类之一；`entries` 转成规则卡：
  - 同一 `type` + 同一对象 + 槽位集合 → 一句；
  - 一张卡最多 3 句；超过时分多张卡。
- 转换函数是纯函数，放 `frontend/src/rules/proposalToCards.ts`；单测覆盖 16 类的可转换与不可转换情况。
- 转不了的组合（例如目录里存在但句子模型未覆盖的参数）显示具体原因，不写入。
- 高级页的协作提醒 / 跨设备工作区状态随页面删除；AI 历史与审计保留在面板内。

## 10. 测试接缝

- **后端主接缝**：`ConstraintConfigService.settings(semesterId)` 展开后的快照。集成测试至少覆盖：
  1. 教师组/课程组/时间组展开为正确成员与槽位；
  2. 改组成员后不改规则，快照结果跟着变；
  3. 草稿校验、调代校验对同一组规则结论一致；
  4. 组为空 / 组已删 → 规则失效且不报错；
  5. 复制规则四种映射情况；
  6. 删组 + 撤销恢复组与规则。
- **前端纯函数**：句子渲染/编译（16 类）、多天、冲突检查、AI 建议转卡片。
- **E2E**：每票一条主路径；票 04/05/06 的列表后缀与筛选各一条；票 08 的复制确认与「用不上」列表各一条。

## 11. 不在本契约内

- 班级分组、规则模板、课表版本、生效日期、自然语言生成规则的模型改动。
- 求解器、发布模型、课表导出格式。
- 课表格子里的分组后缀。
