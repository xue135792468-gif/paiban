# 01: 工作台向导骨架 + 选课 + 动作表单

**What to build:** 用 v3a 留白版重做工作台的第①②步：三步向导骨架、已选摘要、步骤1 选课（教师/日期/周课表、320px 列表）、步骤2 动作卡与就地表单（代课候选、调课窗口日期块、目标节次、切换入口、拖拽预选）。本票不创建预览凭证、不提交。

**Blocked by:** None

**Status:** ready-for-agent

## 需要重做的文件

- `frontend/src/views/SubstitutionWorkbenchView.vue`（整页重写，SFC < 600 行）
- 新组件放 `frontend/src/components/substitution/workbench/`，建议拆分：
  - `WizardSteps.vue`（步骤条）
  - `PickedSummary.vue`（已选摘要条）
  - `LessonPicker.vue`（步骤1：教师/日期/周课表 + 320px 列表 + 拖拽源）
  - `ActionChooser.vue`（步骤2：动作卡 + 切换链接 + 代课/调课表单）
  - `WindowDates.vue`（调课窗口日期块，含周末/窗口外置灰与原因）
  - 纯函数模块（窗口日期、拖拽目标判定）放同目录 `.ts` 并配单测
- 视觉规格以 `v3a-airy.html` 为准（见 `.scratch/workbench-ui-redesign/spec.md` 的设计依据与路径）

## 交互契约

1. **向导**：① 选哪节课 → ② 怎么调 → ③ 核对并提交；一次一步；步骤条当前蓝色、已完成绿勾；已选摘要带「修改」可回步骤1。
2. **步骤1**：教师搜索/选择 + 日期；周课表列头为真实日期（所选日期所在周，周一~周五），课卡显示课程/教师/班级；点选后底部出现「已选：…」并解锁下一步。320px（≤520）改为按天列表。
3. **步骤2**：
   - 两张动作卡：「换老师上（代课）」/「换时间上（调课）」+ 一句说明。
   - 选中后就地展开表单；另一张收成**右对齐小号虚线链接**，位置在**选中卡与表单之间**（首屏可见；这是原型实测过的摆放）。
   - 代课表单：`GET /api/substitution-workbench/candidates?entryId=&lessonDate=`，启用教师、排除当前教师、同科优先、跨科提示、忙碌原因；被禁用候选可点但显示原因。
   - 调课表单：窗口日期块 = 来源日起 `substitution.search-window-days`（默认 7，含当天），周末/无课日置灰并注明原因；选日期后 `GET .../swap-targets` 列目标节次（空格不可选）。
   - 原因输入框为单行（高度≈40px），不得被 flex 撑高。
   - 底部：左「上一步」，右「还差：…」提示 + 主按钮（未选全禁用）。
4. **拖拽预选**：从步骤1课表把课卡拖到同班已有课的目标格（窗口内）→ 预选「调课」并填好目标日期/节次，落在步骤2（表单可见），**不得直接跳到步骤3**。指针事件实现需可重试（沿用票 06 的 dragstart 节拍 + `expect(...).toPass()` 思路，非法投放零写入）。
5. **权限与路由**：沿用 `WORKBENCH_MANAGER_PERMISSIONS` 与现有路由；无全校课表查看权限的账号按现状被拒。

## 验收清单

- [x] 三步骨架、步骤条状态、已选摘要「修改」回退可用；同一时刻只渲染当前步骤。
- [x] 步骤1：真实周课表（日期列头）、点选、320px 按天列表；下一步未选课前禁用。（按天列表同一 ≤520 断点；320×700 正式验收见票 03）
- [x] 步骤2：动作卡选中/切换正常；**切换链接在表单上方且 1280×720 首屏可见**（两个方向都可来回切）。
- [x] 代课候选来自真实接口：排除当前教师、同科排序、跨科标记、忙碌/置灰原因。
- [x] 调课日期块：窗口内可选、周末置灰并注明、窗口外不出现；选日期后目标节次来自 swap-targets。
- [x] 原因输入框单行（高度 ≤ 50px），不撑高版面。
- [x] 拖拽：有效目标预选调课并进步骤2；空格/跨班/窗口外给出不可用原因且零写入。
- [x] 组件单测覆盖窗口日期计算与拖拽目标判定；`npm run lint && npm run build && npm run test:unit` 全绿。
- [x] 定向 Playwright（新 spec 或改造中的 spec）覆盖上述①②步交互，控制台零错误。

## 实现说明（2026-10-30）

**做了什么**：`SubstitutionWorkbenchView.vue` 整页重写为三步向导（589 行，<600），新组件落位 `frontend/src/components/substitution/workbench/`：`WizardSteps`（步骤条，aria-live）、`PickedSummary`（已选摘要 + 「修改」回退）、`LessonPicker`（教师/日期 + 真实周课表日期列头、≤520px 按天列表、拖拽源）、`ActionChooser`（动作卡 + 表单间切换链接 + 代课候选/调课表单）、`WindowDates`（窗口日期块）。窗口日期与拖拽判定抽成纯函数 `windowDates.ts` / `dragTargets.ts`（前端唯一规则源），各配单测；视图层不做投放判定，预选后统一走步骤 2 表单路径，后端 swap-targets / swap-plans 仍是最终裁决。

**关键取舍**：
- **窗口天数前端固定 7**：`/api/settings` 需要 settings 管理权限，工作台操作者读不到配置接口，因此 `buildSwapWindow` 不拉配置、固定 7 天（与后端 `substitution.search-window-days` 默认值一致），并在日期块上方文案写明「含当天共 7 天」。后端仍按真实配置裁决；若运维改了后端配置，前端窗口块会与后端判定出现偏差——接受该偏差换取零权限扩散，文案已写死「7 天」提示用户。
- **拖拽目标限同教师同班**：拖拽发生在所选教师本人的周课表内（天然同教师），投放目标再要求 `classId` 与来源课相同；空格、来源课自身、来源日之前、跨班四种无效目标都给出原因条（`swap-drop-invalid`，role=alert），零请求零写入。拖拽只表达「想和哪节课对调」的意图，绝不直接跳步骤 3、不自动生成方案。
- 原因输入框固定单行（约 40px 高）；切换链接为右对齐小号虚线、放在选中卡与表单之间（原型实测摆放），两个方向可来回切，1280×720 首屏可见。

**证据**：组件单测 `windowDates.spec.ts` / `dragTargets.spec.ts` 随全量前端单测通过（55 文件 / 438 用例全绿）；定向 e2e `workbench-wizard.spec.ts` 8 条用例（含「①②步全流程走查：控制台零错误」）随 8 个工作台 spec 整批连跑两遍全绿（33 用例/轮，6.0m / 6.1m，退出码均 0）；截图 `t01-01-step1-grid.png` ~ `t01-07-drop-invalid-reason.png`（`.scratch/workbench-ui-redesign/acceptance/`）；全量门禁 `verify.sh --full` 退出码 0（详见票 03 证据节）。

## 规格依据

`.scratch/workbench-ui-redesign/spec.md`；原型 `v3a-airy.html`；接口表见规格。
