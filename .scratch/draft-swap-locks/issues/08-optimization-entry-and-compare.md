# 08 继续优化入口、来源提示与新旧草稿比较

Status: done
Type: task
Blocked by: 05、06、07
Owner: worker
日期：2026-11-02

## 目标与输入

07显式来源任务API和状态、06锁定交互、05保存行为；规格§六。
前置票必须验收并合入后才能认领；ready-for-agent表示规格已就绪，不表示依赖已完成。
共同约束见[规格](../spec.md)、[接口契约](../contracts.md)、[执行路线](../ticket-plan.md)。

## 写入范围与边界

草稿编辑器、排课任务状态/比较入口、新组件与单测/E2E；需要时复用已有比较视图而不是重复实现。
只修改本票必要文件；发现契约冲突先由统筹统一更正，不能让多个worker各自猜接口。不得改生产数据或部署。

## 实施步骤

1. 在当前草稿提供保留锁定继续优化入口，明确当前草稿、锁定节数、生成新稿不覆盖原稿；任务请求携带正确sourceDraftId。
2. 复用既有任务排队、取消、进度与错误提示，完成后进入新草稿/比较；源稿与新稿名称/身份清楚可辨。
3. 新稿有锁标记，展示变化只包含未锁课程；全锁无可优化、源失效、任务变化、求解不合格等不显示通用成功。
4. 发布资格来自后端整表检查，软偏好与硬问题分别显示；不因任务结束就启用发布按钮。

## 验收清单

- [x] 从当前选中草稿发起并生成另一份，回看原稿不变；比较中锁课不变，新稿刷新仍锁。
- [x] 全锁、锁规则冲突、源不匹配、取消/失败提示明确，无错误可发布状态。
- [x] 普通新建自动排课入口与默认来源行为无回归。
- [x] 键盘可操作入口与结果跳转；重开任务状态不触发重复启动。

## 验证与交付

任务状态组件单测与保留锁继续优化/新旧对比Playwright；与05/06串行集成。
每票提交Delivery：实际改动文件、分支/基点/提交、真实命令与退出码、剩余问题；全量门禁统一由09执行。
任务前检查资源；实际执行默认group/deepseek、high；机械搜证仅group/gemini3.8；算法结论由主模型负责。
遵守AGENTS/CODING_STANDARDS，不同worktree隔离；结束清理本票临时文件与进程。

## Delivery

**实现分支**：`codex/t08-optimize-ui`（worktree `E:/paiban-wt/t08-optimize`）
**基点**：`codex/draft-swap-locks`（已含 01-07，HEAD `d116567`）
**提交**：`feat(draft): continue optimization from the current draft`

### 改动文件

新增：

- `frontend/src/api/scheduling.ts`（`OptimizationJob` 类型与启动/查询/取消三个端点，权限沿用后端 `scheduling:run` / `scheduling:view`）
- `frontend/src/composables/useOptimizationJob.ts`（单次提交防重、1s 轮询、resume 只读、取消与终态停止；`COMPLETED` 不推导可发布）
- `frontend/src/composables/useDraftCompareQuery.ts`（`?compare=` 打开比较并给出 A 侧来源稿 id）
- `frontend/src/composables/useUndoShortcut.ts`（把 `Ctrl/Cmd+Z` 从视图移出）
- `frontend/src/composables/useDraftActions.ts`（把重命名/发布/删除从视图移出，行为不变）
- `frontend/src/components/draft/DraftOptimizationSection.vue`（入口卡 + 任务面板 + 对话框编排，`?job=` 只恢复轮询）
- `frontend/src/components/draft/OptimizationLaunchDialog.vue`（默认名称、锁定继承提示、全锁禁用、提交防重与服务端原因）
- `frontend/src/components/draft/OptimizationJobPanel.vue`（状态/进度/来源版本/继承锁；完成态按 `resultPublishable`/`resultBlockers` 原样展示；取消防重）
- `frontend/src/components/draft/compareDiff.ts`（纯函数：按授课身份配对、未锁变化/锁定变化/无法配对/教师与天数去重）
- 单测：`useOptimizationJob.spec.ts`、`compareDiff.spec.ts`、`OptimizationLaunchDialog.spec.ts`、`OptimizationJobPanel.spec.ts`、`DraftCompareDialog.spec.ts`
- `frontend/e2e/draft-optimize.spec.ts`（4 条：继续优化全链路、全锁阻止、`?job=` 不重启、键盘入口）

修改：

- `frontend/src/views/DraftEditorView.vue`（接入优化区块与比较配对；593 → 549 行）
- `frontend/src/components/draft/DraftCompareDialog.vue`（新增 `sourceDraftId`/`compareWithDraftId`；锁列、差异摘要、锁定变化红色提示；旧默认选择不变）
- `frontend/src/components/draft/types.ts`（`DraftLesson.lessonIndex?` 可选，服务端未返回时由 id 序推导）
- `frontend/src/help/docs/manual-adjust.md`、`scheduling.md`（继续优化与新旧比较说明，措辞不含“优化成功/可发布”）
- 本票面

### 关键实现口径

- 只有显式入口携带 `sourceDraftId`；普通新建排课仍走 `SchedulingView` 原逻辑，请求体不带来源。
- 原稿不动：E2E 断言来源稿 `version` 与锁定集合不变；新稿行 id 全新，锁定按 `(assignmentId, lessonIndex)` 继承（前端比较用相同身份规则）。
- 任务完成 ≠ 可发布：面板只显示后端 `resultPublishable`/`resultBlockers`，不出现“发布”按钮；发布仍走 `/validation`。
- 全部锁定时入口禁用并提示「全部课节已锁定，没有可优化的课程」，不产生任务。
- 重开页面带 `?job=` 只 `GET` 并继续轮询；E2E 断言页面无 `POST /scheduling-jobs` 且任务数不变。
- 比较只统计未锁变化；锁定课发生变化单独计数并红色提示，无法配对的两侧课节单独计数。

### 验证命令与真实退出码

在 `E:/paiban-wt/t08-optimize/frontend`：

- `npm run lint` → **0**
- `npx vue-tsc -b` → **0**
- `npx vitest run --no-file-parallelism src/composables/useOptimizationJob.spec.ts src/components/draft/compareDiff.spec.ts src/components/draft/OptimizationLaunchDialog.spec.ts src/components/draft/OptimizationJobPanel.spec.ts src/components/draft/DraftCompareDialog.spec.ts` → **0**（29 tests）

在 `E:/paiban-wt/t08-optimize`：

- `bash scripts/check-sfc-size.sh` → **0**（`DraftEditorView.vue` 549 行，`SchedulingView.vue` 786 行未变）
- `bash scripts/e2e.sh e2e/draft-optimize.spec.ts e2e/scheduling.spec.ts` → **1**
  - `draft-optimize.spec.ts` 4/4 通过；
  - `scheduling.spec.ts` 8 条中 7 条通过，失败的是 `场景 1b：草稿拖拽冲突高亮与移动`（`getByText('换课候选')` 期望抽屉打开）。已在该 worktree 上 `git stash` 掉本票改动后重跑同一条用例，**基点代码同样失败**：05 的直接拖拽把「拖到同班有课格」改为直接交换/自动联动方案，旧 spec 仍在等候选抽屉。属 05 遗留的过期断言，不在本票改动范围；未删除或放宽该断言。

### 剩余问题

1. 09 已按 05 新交互改写 `scheduling.spec.ts › 场景 1b`（直接拖拽交换不再打开候选抽屉），并跑通。
2. 09 已给草稿详情 DTO 补 `lessonIndex`；比较以 `assignmentId:lessonIndex` 为准，id 顺序仅作旧载荷兼容兜底。
3. `resultPublishable=false` 的 E2E 场景难以稳定构造，由 `OptimizationJobPanel.spec.ts` 的单测覆盖两种完成态；E2E 未断言任何“可发布”。
4. 09 已执行后端全量（1195 项）、前端单测（549 项）与整套 Playwright（161 passed）；真实 PostgreSQL 待 CI/`--deploy`。

## Comments
