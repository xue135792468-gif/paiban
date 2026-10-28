# 06: 已发布课表拖拽进入调课预览

**What to build:** 在管理员课表中允许从一节课拖到明确的同班目标课槽，拖动仅表达调课意图，并进入 05 已交付的候选方案、左右对照、变化清单及审批/确认流程。教师视角发起时目标网格锁定源课行政班。日期与课节表单继续作为等价入口，小屏可逐项检查对照。

**Blocked by:** 05:管理员同班调课方案与跨日对照

**Status:** in-progress (2026-10-29, branch feature/t06-drag-timetable-reschedule)

- [ ] Playwright 从行政班视角拖动课节到同班目标格后，显示目标的实际日期/课节并进入方案预览。
- [ ] Playwright 从教师视角发起时，目标明确属于源课行政班，不把教师空闲格误当班级目标槽。
- [ ] 拖动只提交目标意图；预览前后发布课表不变，须经既有审批或确认流程才生效。
- [ ] 预览呈现目标下完整候选环、影响清单和左右对照；拖拽入口与表单入口所得结果一致。
- [ ] 拖到空格、跨班格、窗口外或已开始槽位时给出明确不可用原因，不产生预览以外的写入。
- [ ] 无解、取消预览或返回修改目标后，当前有效安排、申请及通知均不变。
- [ ] 拖拽选择不会跳过硬约束、开课截止、快照过期或并发冲突检查。
- [ ] 桌面指针拖动可操作；日期/课节选择在不支持或不便拖动时可独立完成相同流程。
- [ ] 小屏可逐一查看左右面板和清单，调整前/后标签始终可辨。
- [ ] Playwright 覆盖拖动到可行目标、拖动到无效目标、取消及表单等价操作，并断言可观察页面结果。

## 规格依据

`spec.md`「代课与调课」「对照、清单与预览生命周期」「Testing Decisions」。

## 交付记录

2026-10-29，分支 `feature/t06-drag-timetable-reschedule`（基于集成分支 `ef1acf5`，票 01–05/08 工作已包含；交付时集成分支尚无票 07 新提交，`git merge feature/admin-substitution-workbench` 为 Already up to date）。提交 `f87bcff`。

### 实现摘要

- **技术选型：Pointer Events**（弃 HTML5 DnD）。理由：① Playwright 用 `mouse.move/down/up` 走真实输入管线即可稳定驱动（Chromium），HTML5 DnD 需要 `dragAndDrop`/dispatchEvent 特殊路径且 dragover/drop 合成时序脆；② 命中测试用 `document.elementFromPoint`（视口坐标），与票 08 的 `overflow-y:auto` 滚动容器天然无冲突——不挂原生 drag 事件就不会触发原生拖拽滚动，滚动状态不影响命中；③ 拖拽中高亮、ghost、禁用态完全可控。
- 新建 `SwapTargetGrid.vue`（373 行）：渲染源课行政班的周课表（列头实际日期），来源课格可拖拽（虚线框 + grab 光标 + 跟随 ghost），同班「当天该槽有课」的格子是有效目标（拖拽中虚线高亮、悬停实线），空格/窗口外/后端拒绝的格子拖拽中变暗 + not-allowed。按天调用 `GET /substitution-workbench/swap-targets`（早于来源日的天不发请求，判定规则直接给出窗口外原因）。
- 新建 `swapTargetRules.ts`：**前端唯一规则源** `evaluateDropCell` 纯函数——窗口外（`date < lessonDate`，与表单日期选择器 `disableDate` 同一表达式）、当天槽位来自后端 swap-targets（与表单课节下拉同一份数据）、来源课本身/空格不可投放（对应后端 `generatePlans` 拒因）；后端请求失败原样展示后端原因（窗口天数、学期范围由后端裁决）。另含 `mondayOf`/`weekDates`/`weekdayLabel`，工作台视图的 weekStart 内联算法改为复用 `mondayOf`。
- `SwapPlannerPanel.vue` 接线：拖拽投放＝另一种「设置目标」——`onDropTarget` 把目标日期写入表单状态后走 `loadTargets → 设课节 → generate` 与表单完全相同的路径，**不复制流程、不加直达确认捷径**；无效投放显示 `swap-drop-invalid` 告警（明确原因 + 未生成方案未写入）；方案生成中暂停拖拽（`disabled`）。
- **教师视角锁定**：拖拽面只存在于调课流程内的行政班网格；工作台的教师周课表（`WorkbenchTimetable`）保持只选不拖（无任何投放格）。教师视角从工作台教师搜索课表发起（选中课节 → 调课），发起后目标网格固定为源课 `entry.classId` 的行政班格子，教师空闲格不在网格里，不会把「教师没课」误当「班级目标槽」。
- 硬约束/开课截止/快照过期/并发冲突全部沿用票 05 后端合同：投放后的 swap-plans / swap-previews 请求与表单字节级同路，前端无任何绕过。
- e2e：`openSwapFlow`/`pickTargetAndGenerate` 从 `admin-swap.spec.ts` 抽到 `helpers.ts` 共用（票 05 spec 改为导入，行为零变化）。

### 验收清单逐条证据

- **拖到可行目标进入预览**：`admin-swap-drag.spec.ts` 用例 1——`dragSourceToCell`（真实鼠标管线，steps=10）拖周三语文 → 周五数学格，断言目标日期输入框值=`targetDate`、目标课节显示「数学」、候选卡片「2 人环」；选方案后整环对照两日左右面板 + 变化清单 2 行可见。
- **教师视角锁定**：同用例断言 `swap-target-grid` 包含「数学」（源教师周五没课、班里周五有数学——网格是班级课表而非教师课表）；断言 `workbench-timetable` 内 `[data-swap-cell]` 数量为 0（教师课表不是拖拽面）。
- **拖动只提交目标意图、预览前后课表不变**：用例 3（取消预览）API 侧断言零待批申请、零 SWAP 生效行，且 `/timetable/class/{id}?view=latest` 中来源课仍在周三第 1 节；用例 2（无效拖放）同样断言零申请零生效。须经审批/确认才生效由票 05 合同保证（本票无新后端路径）。
- **拖拽与表单结果一致**：用例 4——同一 seed 先表单生成候选，记录卡片数量与首卡 innerText，取消后重进流程拖拽同一目标格，断言数量与首卡文本完全一致。
- **无效目标明确原因、无预览外写入**：用例 2——拖到周四空格显示「空格：空格只挪不补…」（与表单空日同一文案来源）、拖到周一显示「窗口外：目标日期 … 早于来源日期 …」（与表单 disableDate 同一规则）；`swap-plan-card` 始终为 0；跨班格在班级网格中不存在（正是锁定语义，用例 1 佐证）。
- **无解/取消/返回改目标零变化**：用例 3 全链路 API 断言；无解原因沿用票 05 的 `swap-no-solution` 面板（拖拽路径生成的方案与表单共用）；返回改目标＝直接再拖另一格（生成中暂停拖拽防半途改目标）。
- **不跳过校验**：投放后走与表单相同的 swap-plans/swap-previews POST；已开始槽位的拒绝由后端 `AdminSwapDeadlineIntegrationTest` 覆盖（票 05），拖拽路径同样在 generate 处被拒并显示后端原因。
- **桌面指针拖动可用 + 表单等价入口**：拖拽全程真实鼠标事件驱动；表单入口代码零改动（`admin-swap.spec.ts` 2 条继续绿即证据）。
- **小屏逐一查看**：用例 1 在 390×844 视口下断言整环对照、「调整前（最新）/调整后（方案）」标签与变化清单全部可见（SwapCompare 的 ≤900px 纵向堆叠由票 05 提供）。

### 测试数字

- 前端单测：`swapTargetRules.spec.ts` 新增 9 条（mondayOf/weekDates/weekdayLabel 3 + evaluateDropCell 6 分支）——全绿；全量 `npm run test:unit` 52 文件 416 passed。
- Playwright（隔离端口，`scripts/e2e.sh`）：`admin-swap-drag` 4 passed（57s）、`admin-swap` 2 passed（票 05 表单回归）、`admin-substitute` + `admin-substitute-revision` 5 passed（票 03/04 回归）、`global-scroll` 7 passed / 1 failed（见遗留风险 1，基线复现）。
- `npm run lint` 0 错 0 警告；`npm run build`（vue-tsc + vite）成功；`scripts/check-sfc-size.sh` ok（SwapTargetGrid 373 行、SwapPlannerPanel 320 行，均 <600）；`scripts/check-migrations.sh` ok。
- 后端零改动（无迁移；V43 未动用）。

### 遗留风险

1. **`global-scroll.spec.ts`「长菜单的首尾菜单项均可到达」为存量 flaky，与票 06 无关**：`git stash` 本票全部改动后在基线 `ef1acf5` 单跑该用例仍失败（基线失败在 `menuScrollable` 断言；含本票改动时失败在 `getByRole('menuitem', { name: '工作台' })` strict violation——票 03 引入的「调代工作台」菜单项使非精确名称匹配解析出 2 个元素）。同一 spec 其余 7 条滚动用例（含导航/内容独立滚动、回顶、锚点、弹窗）全绿。修复方向（票 08 范围）：定位加 `exact: true`，并核查 `menuScrollable` 的测量时机；未在本票代改以免越界混入。
2. 拖拽网格只覆盖来源日期所在周（周一~周五）；跨周目标仍走表单日期选择器（等价入口），网格按天调用 swap-targets（≤5 个小请求，面板重开时刷新）。
3. 班级网格显示的是 swap-targets 的「当天可作目标槽位」（后端权威），早于来源日的列只显示窗口外原因不显示课程内容；周内已生效旧调代的展示以 swap-targets 为准，极端叠加场景可能与视觉直觉有差，但合法性判定永远单源。
4. 已开始槽位在网格中呈可投放态（前端无法可靠得知服务端开课时刻），松手后由 `generatePlans` 的 DeadlineChecker 拒绝并显示原因——与表单入口行为一致，前端不做浏览器时钟猜测（规格第 6 节）。
