# 05: 多条配置模板可完整往返并兼容旧模板

**What to build:** 用户保存包含多教师、多课程与对象例外的模板后，套用能完整恢复；旧单条模板仍可使用。
**Blocked by:** 03, 04
**Status:** done（未部署，2026-10-26）
**Spec:** ../spec.md

- [x] 模板往返所有条目、类型控制与作用域，不能退回每类型第一条或最后一条。
- [x] 旧单条模板升级后含义不变；多次套用不累加重复条目。
- [x] 审查模板文本容量；大量合法条目可保存/读取，越界不能静默截断；迁移保持两数据库与旧模板兼容。
- [x] 跨学期行政班引用要求重新绑定或明确拒绝；有效全局教师/课程引用保留，不自动猜同名。
- [x] 界面显示套用结果与失效引用，任何校验错误都不部分替换现有配置。
- [x] 通过模板公开 REST＋页面操作先写失败测试，覆盖多类型往返、旧模板、重复套用、非法引用和容量。
- [ ] 独立双轴评审、完整闸门与模板页面截图完成。（闸门与截图已完成；独立双轴评审留给 06 收尾票统一做）

**范围边界：** 不引入自动跨学期实体合并；沿用既有模板替换语义。

## Comments

### 实现说明（后端，2026-10-26）

**模板 JSON 结构（`constraint_template.configs_json`）**

```json
[
  { "type": "TEACHER_UNAVAILABLE", "weight": 100, "hard": true, "enabled": true,
    "entries": [ { "params": { "teacherId": 3, "dayOfWeek": 1, "periodNo": 1 } } ] },
  { "type": "CLASS_MAX_PER_DAY", "weight": 80, "hard": false, "enabled": true,
    "entries": [ { "params": { "maxPerDay": 8 } },
                 { "params": { "classId": 5, "maxPerDay": 5 } } ] },
  { "type": "ROOM_CONFLICT", "weight": 100, "hard": true, "enabled": false, "params": {} }
]
```

- 快照 = 学期里**每一个 config 行**（启用的、关闭的都在），带 `weight/hard/enabled` 与全部条目：
  18 类条目化类型发 `entries`（可为空数组＝未配置），6 个开关型发 `params`。
- 写模板时条目 `id` 不落库（跨学期无意义），响应 `TemplateDto` 新增 `entryCount`，下拉显示「N 类 · M 条」。
- 旧格式（只有 `params`、没有 `entries`）读取时走同一个 `save`：`planEntries` 的 legacy 分支把 `params`
  当作**一条**条目；`entryCountOf` 也按一条计（空 map 计 0）。旧的 `TEACHER_UNAVAILABLE` 单条模板
  套用后仍是那一条，不是「每类型第一条」也不是丢规则。
- 套用是整学期替换，重复套用不累加：每次 `deleteBySemesterId` + 重建，三次套用后条目数仍是模板里的条数。

**容量：扩容而不是收紧（选了扩容 + 保留显式上限）**

- 现状 `VARCHAR(8000)` 装不下真实模板：一条教师禁排条目约 58 字符，200 条就是 ~12k。
- `V29__constraint_template_capacity.sql`：`ALTER TABLE constraint_template ALTER COLUMN configs_json
  SET DATA TYPE VARCHAR(65535)`。`SET DATA TYPE` 是 H2（PostgreSQL 模式）与真实 PostgreSQL 都接受的写法
  （H2 不接受裸 `TYPE`，PostgreSQL 不接受 `MODIFY COLUMN`），且两项都不用改类型语义，旧值原样保留。
- 65535 是行内 VARCHAR 在两种库上的上限，也是「一个学期条目全量」的实际天花板（约 1000 条）。
  `ConstraintService.TEMPLATE_JSON_LIMIT = 65535`，超过仍 `400「模板内容为 N 字符，超过 65535 字符上限」`，
  **不静默截断**；超限时事务未开始写入，不会留下半个模板。
- 为什么不改 TEXT/CLOB：`ddl-auto=validate` 下 `@Lob`/CLOB 与两种库的列类型名对不齐，收益只是「无上限」，
  而模板是需要有界的用户数据。

**跨学期引用：默认拒绝 + 结构化清单 + 显式重新绑定**

- 新增 `POST /api/constraint-templates/{id}/preview?semesterId=`（干跑，不写库）与
  `POST .../apply`（可选 body `{ "classMapping": { "<模板里的 classId>": "<目标学期 classId>" } }`）。
  preview 返回 `{ configCount, entryCount, invalidReferences[], errors[] }`；apply 成功返回既有的
  `ConstraintConfigDto[]`（形状未变，老前端不受影响）。
- `invalidReferences[]` 每条 = `{type, typeName, position, field, fieldLabel, objectId, name, reason}`：
  类型码与显示名、第几条（1 基，与保存校验的「第N 条」对齐）、字段、对象当前名称（用于给用户看），
  以及原因（`行政班属于其他学期（2025 秋）`／`教师不存在或已被删除`／`行政班不存在或已被删除`）。
- 判定顺序：先按 `classMapping` 改写 classId（只改显式给出的），再扫描 `teacherId/courseId/classId`
  三类引用。仍无效则 `409`，错误体带 `invalidReferences`（`ApiException` 新增 `details`，
  `GlobalExceptionHandler` 把 details 平铺进错误体），**完全不写库**。没有映射时绝不按同名猜测。
- 教师/课程是全局资产：仍存在就原样保留（测试断言跨学期套用后 teacherId/courseId 不变）；
  不存在则与前一类一样列明并拒绝，无效 id 不会落库。
- preview 的 `errors` 只放「非引用类」原因（作息里没有该节次等）：`plan()` 新增 `checkReferences`
  开关，preview 传 false，避免同一问题既进清单又进错误串。

**重构（一处校验、两处使用）**

- `save` 拆成 `plan(semesterId, requests, checkReferences)`（纯校验，返回 `Planning{configs, errors}`）与
  `persist(...)`（落库）。`save` = plan(检查引用) + 有错即 400 + persist；`preview` = plan(不查引用) 只读。
  套用因此复用与保存**完全相同**的校验，不存在「预览放过、落库拒绝」。

**测试**

- `ConstraintTemplateCompatibilityTest`（6 条，公开 REST）：18→(实际 3 类 + 开关) 多类型往返（条目逐条、
  权重/软硬/启用一致，禁用开关行也回来）；旧 `params` 模板套用＝一条 legacy 条目并可再次存成新模板往返；
  200 条合法条目存/取/套用（快照 12k+ 字符）+ 600 行超容量 400 且不建模板；跨学期班级 `409` 列清单、
  preview 同清单且零写入、`classMapping` 重新绑定后教师/课程保留且旧 classId 未落库；同一模板套用三次条目数不翻倍；
  未知教师 `409`／超作息 `400` 且配置逐字不变。
- `ConstraintEntryMigrationTest.v29WidensTheTemplateTextBeyondTheOldEightThousandCharacters`：
  迁移到 V29 后写入 60000 字符可读回（旧的 8000 会失败）。
- 门禁：`cd backend && ./mvnw -B test` → **720 通过 / 0 失败 / 5 跳过**（04 后 713，本票 +7）。

**未做项与风险**

- 真实 PostgreSQL 上 V29 尚未实跑（本地 H2 覆盖，且 `SET DATA TYPE` 是 PG 合法语法）；部署时按 runbook 先备份。
- 模板仍不可编辑：引用了已被删除的教师/课程时，只能先在系统里恢复对象或重新存模板；界面已明确说明。
- 同一模板里若同时出现两个学期来源的 classId，一次映射即可全部覆盖（映射按 id 生效，不区分来源）。
- 条目 `id` 仍不写进模板（整学期替换语义），因此套用后条目 id 会重新生成——前端不依赖它做定位。

### 实现说明（前端，2026-10-26）

**组件拆分与行数**

| 文件 | 行数 | 职责 |
|---|---|---|
| `components/constraints/ConstraintTemplateBar.vue` | 356 | 模板区：存为模板 / 选择 / 套用 / 删除，套用结果与失败提示，失效引用对话框（含班级重新绑定） |
| `components/constraints/useConstraintTemplates.ts` | 192 | 模板列表、保存、预览、套用、删除的 HTTP 与状态；409 时把 `invalidReferences` 收进状态 |
| `components/constraints/templateModel.ts` | 123 | 纯函数：下拉标签、套用结果计数/文案、失效行渲染、阻断项与 `classMapping` 组装 |
| `views/ConstraintsView.vue` | 411（原 551，减 140 行） | 只保留学期、目录、草稿与保存；模板区改为一个组件 + `:classes` / `:reload` |

单测：`templateModel.spec.ts`（8）、`ConstraintTemplateBar.spec.ts`（5）。

**交互顺序（套用前三步，失败零写入）**

1. 点「套用模板」先 `POST .../preview`：拿到「将恢复 N 类配置、M 条条目」与失效清单；
   `errors` 非空 → 顶部红条写明原因，**不发** apply 请求（当前配置原样保留）。
2. 失效引用非空 → 弹清单对话框：每行「类型（码）· 第N 条 ·「字段」· 对象名（#id）· 原因」；
   `classId` 行给一个「选择当前学期的班级」下拉（选项只有目标学期的班级），教师/课程行写明
   「该对象无法通过套用修复」。有任一行未处理时「按所选班级重新套用」禁用。
3. 无失效引用 → 确认框（写明将覆盖并恢复的规模）→ apply。成功显示
   「已套用模板「X」：恢复 N 类配置、M 条条目」，**数字来自 apply 响应**而不是本地推测，随后刷新配置。

- 对话框本身就是第二次确认（用户刚为每行做完选择），不再叠加一层确认框。
- 服务端 409（例如预览后对象又被删）时把错误体的 `invalidReferences` 收进同一清单，页面提示失败且不刷新，
  因此不会出现「清了一半」的画面。
- 模板下拉标签带规模：「标准学期（2 类 · 5 条）」；模板区保留原有「初中模式隐藏」条件（ADR-0018），
  E2E 用 `school.type=senior` 打开（与 `zz-junior-mode` 相反方向，跑完复原 junior）。

**Playwright（`e2e/constraints-templates.spec.ts`，2 条串行）**

1. `保存含多条条目的模板，改动后套用即恢复条目与计数`：接口造 3 条禁排 + 2 条阈值（5 条）→ 界面存为模板
   （下拉显示「2 类 · 5 条」）→ 删一条卡片并保存（计数 4）→ 套用 → 绿色结果显示「恢复 2 类配置」、
   计数回 5、三张卡片回来、接口 3 条条目且 `hard/weight` 一致。
2. `跨学期套用：失效班级列出清单，重新绑定后套用成功`：模板来自学期 A（引用 A 的班级）→ 切到学期 B →
   套用打开清单（含 A 班名与「行政班属于其他学期」）→「按所选班级重新套用」在未选班级时禁用 →
   选 B 的班级 → 套用成功，接口里例外条目 `classId` 变成 B 的班级、教师引用不变。
- 截图（人工复核用，不作断言）：`E:/paiban-wt/screenshots/phase11/constraint-template-applied.png`、
  `constraint-template-cross-semester-issues.png`，两张都人工看过（结果显示 5 条、清单行与下拉齐全）。
- 既有约束 E2E 一并复跑：`constraints-entries`（4）、`constraints-target-entries`（5）、
  `constraints-threshold-scopes`（4）、`zz-junior-mode`（1）全绿。
- 前端闸门：`npm run lint && npm run build && npm run test:unit`（45 文件 / **359** 用例）、
  `scripts/check-sfc-size.sh`、`scripts/check-blank-checks.sh` 均通过。

**未做项与风险**

- 模板区在初中模式仍隐藏（沿用既有可见性），因此初中学校用不到模板；若产品要开放，需要单独确认折叠口径。
- 失效清单一次最多展示模板里的全部行；条目极多（上百条引用）时对话框会很长，暂未分页。
- 模板不可重命名（沿用原有 save-by-name 覆盖语义），与「删除/新建」等价。

### 评审修复（2026-10-26，集成分支 `feature/constraint-phase`）

**行为**
- 对象引用校验合成一处：`invalidReferences` / `referenceProblem` / `referenceName` 与 `requireTeacherIfPresent` / `requireClassIfPresent` 两套代码、两套文案合并成 `referencesOf(...)` + `resolveReference(...)`，一个对象只查一次（原来 `existsById` 与 `findById` 查两遍，现在一次 `findById` 同时给出名字与原因）。穿透五层的布尔参数 `checkReferences` 删除：save、preview、apply 读同一份 `Planning`（`errors` + `references`）。
- 模板错误形状统一：preview 的 200 body 与 apply 拒绝的 409 body 现在由同一个 `Planning` 生成，都带 `errors` 与 `invalidReferences`；前端只保留一套解析（`templateModel.templateReportOf`），删掉 `refusalIssues` / `previewBlockedReason` 的双形状分支。409 仍带业务 body，理由写在 `ConstraintController.apply` 的注释里。
- 随之而来的行为变化：模板 apply 的所有拒绝统一为 **409**（此前「节次不在作息内」这类 plan 错误走 400、只有引用错误走 409）。`aFailedApplyLeavesTheConfigurationByteForByteUntouched` 的期望从 400 改成 409 + `errors`；「逐字节不变」的断言一条未动。
- 套用模板时清空快照里的条目 id（`prepareSnapshot`）：模板是值快照不是身份，跨学期沿用源学期的行 id 会误更新目标学期的行或直接被拒。

**文档**
- 无（本票自己的说明文字保持原样）。

**测试**
- `crossSemesterClassReferenceIsRefusedUntilExplicitlyRebound` 新增断言：preview(200) 与 apply(409) 的 `invalidReferences` 逐字段相同、409 的 `errors` 为空数组 —— 形状统一的直接证据。
- `ConstraintEntriesIntegrationTest.anEntryIdFromAnotherSemesterOrTypeIsRejectedWithoutWriting`：别的学期的 id、别的类型的 id → 400，且两边配置逐字节不变（条目身份的负向用例，与 04 的正向用例配对）。

**2026-10-26 上线记录**：模板完整往返与跨学期重绑；两轴评审后集中修复（条目稳定身份 upsert、阈值「无上限」统一、模板引用校验合一、模板错误形状统一为 409+errors、前端对象选项按字段分离、V28 显式五分支、ADR-0025 补差分语义修订记录）。全量闸门：后端 750、前端 unit 366、Playwright 70。线上冒烟 7/7（全局 6 + 教师例外 3 → 再保存 id 不变 → 模板往返恢复）。
