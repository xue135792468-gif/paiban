# 05 拖拽直接交换、自动列方案与整单撤销

Status: done
Type: task
Blocked by: 04
Owner: worker
日期：2026-11-02

## 目标与输入

04预览/应用及02逆向变更接口；contracts.md；规格§一、二、五。
前置票必须验收并合入后才能认领；ready-for-agent表示规格已就绪，不表示依赖已完成。
共同约束见[规格](../spec.md)、[接口契约](../contracts.md)、[执行路线](../ticket-plan.md)。

## 写入范围与边界

DraftEditorView、useDraftAdjust、draft/DraftGrid与新增方案预览组件、类型/客户端、单测/E2E。遵守SFC基线，不扩大原超长页面。
只修改本票必要文件；发现契约冲突先由统筹统一更正，不能让多个worker各自猜接口。不得改生产数据或部署。

## 实施步骤

1. 准确识别拖拽A与落点B；合法同班两课立即交换，不打开原候选列表让用户再选B。空格直接移动、自身不操作，多占用歧义不猜。
2. 直接交换失败自动请求候选，最多三个，完整展示前后位置/影响人数/软损失；不增加帮我找按钮，不自动应用多课方案。
3. 班级/教师视角保持来源班范围，跨班落点给出解释；来源或目标锁定不进入替代搜索。保留显式跨班换人入口。
4. 每次成功自动保存并重载权威状态；失败、取消、过期或网络结果不确定时查回状态再决定，不盲目重复应用。
5. 最近20步当前会话撤销，一次联动作为一步；撤销走服务端整单验证，后加锁课先解锁；页面说明重载不会保留会话撤销栈。

## 验收清单

- [x] 真实拖拽A到B后两个位置都变化、重载保持，不再出现二次选择；空格移动、自身、跨班与歧义落点正确。
- [x] 直换失败自动显示方案，选中并应用才发生联动，取消/超时不改课。
- [x] 一次撤销整环、后加锁不能撤回、失败不误弹成功；软偏好提示与硬冲突区分。
- [x] 教师/班级视角与点击/键盘等价入口可完成相同操作；过期状态提示可理解。

## 验证与交付

新增组件/composable单测与隔离Playwright真实拖拽/候选/撤销E2E；与06、08共享编辑器默认串行。
每票提交Delivery：实际改动文件、分支/基点/提交、真实命令与退出码、剩余问题；全量门禁统一由09执行。
任务前检查资源；实际执行默认group/deepseek、high；机械搜证仅group/gemini3.8；算法结论由主模型负责。
遵守AGENTS/CODING_STANDARDS，不同worktree隔离；结束清理本票临时文件与进程。

## Delivery

**实现分支**：`codex/t05-direct-drag`（worktree `E:/paiban-wt/t05-drag`）
**基点**：`codex/draft-swap-locks`（含 01/02/03/04，HEAD `96838d8`）
**提交**：`feat(draft): direct drag swap, cycle plans and change-set undo`
**主审补口子追加提交**：`fix(draft): send expected version from explicit swap flow`（显式「换课」入口的 `/swap`/`PATCH` 也携带 `expectedVersion`，旧预览不再覆盖他人修改）

### 改动文件

新增：

- `frontend/src/api/draft.ts`（detail/validation/applyChanges/searchCyclePlans/applyCyclePlan）
- `frontend/src/composables/useDraftEditor.ts`（加载与班级/教师/教室派生状态）
- `frontend/src/composables/useDraftDrop.ts`（落点规则：自身/锁/跨班/同格多课/空格移动/同班交换/失败自动搜索）
- `frontend/src/composables/useCyclePlans.ts`（方案面板状态、搜索与应用）
- `frontend/src/components/draft/DraftEditorToolbar.vue`
- `frontend/src/components/draft/DraftEditorControls.vue`
- `frontend/src/components/draft/DraftTargetDialog.vue`
- `frontend/src/components/draft/CyclePlanPanel.vue`
- `frontend/src/composables/useDraftDrop.spec.ts`
- `frontend/src/components/draft/CyclePlanPanel.spec.ts`
- `frontend/e2e/draft-drag.spec.ts`

修改：

- `frontend/src/components/draft/types.ts`（`Draft.version`、`DraftLesson.locked`、`rulesFingerprint`、整单变更/方案类型）
- `frontend/src/composables/useDraftAdjust.ts`（`applyChangeSet`/`moveTo`/`swapLessons`/`searchPlans`/`applyPlan`；整单逆向撤销；409 保留栈；busy）
- `frontend/src/composables/useDraftAdjust.spec.ts`（整单变更、方案、撤销与上限）
- `frontend/src/components/draft/DraftGrid.vue`（锁定标记、`data-locked`、`tabindex`、Enter/Space、aria-label）
- `frontend/src/components/draft/DraftGrid.spec.ts`
- `frontend/src/views/DraftEditorView.vue`（接线拆出的组件/composable，593 → 554 行）
- `frontend/e2e/helpers.ts`（新增 `seedDraftDragScenario`，复用 `dragCardToCell` 鼠标管线）
- `frontend/src/components/aiImport/timetablePreview.ts`、`frontend/src/components/draft/swapPreview.spec.ts`（补齐 `locked` 字段）
- `frontend/src/help/docs/manual-adjust.md`（拖拽直达、整单撤销与锁定说明）
- 本票面

### 验证命令与真实退出码

```bash
cd /e/paiban-wt/t05-drag/frontend
npm run lint
# EXIT=0
npx vue-tsc -b
# EXIT=0
npx vitest run --no-file-parallelism src/composables/useDraftAdjust.spec.ts src/composables/useDraftDrop.spec.ts src/components/draft/DraftGrid.spec.ts src/components/draft/CyclePlanPanel.spec.ts
# EXIT=0；Test Files 4 passed；Tests 52 passed（补口子后 useDraftAdjust 24 条）
bash /e/paiban-wt/t05-drag/scripts/check-sfc-size.sh
# EXIT=0（DraftEditorView.vue 554 行）
bash /e/paiban-wt/t05-drag/scripts/check-e2e-helpers.sh
# EXIT=0

cd /e/paiban-wt/t05-drag
bash scripts/e2e.sh e2e/draft-drag.spec.ts e2e/swap.spec.ts
# EXIT=0；12 passed（draft-drag 8 条 + 既有 swap 4 条），真实鼠标拖拽与隔离端口
```

未运行全量 `verify.sh`、未起 Docker（按票面约束，留给 09）。

### 主审补口子（追加提交）验证

```bash
cd /e/paiban-wt/t05-drag/frontend
npm run lint
# EXIT=0
npx vue-tsc -b
# EXIT=0
npx vitest run --no-file-parallelism src/composables/useDraftAdjust.spec.ts src/composables/useSwapFlow.spec.ts
# EXIT=0；Test Files 2 passed；Tests 27 passed（useDraftAdjust 24 + useSwapFlow 3）

cd /e/paiban-wt/t05-drag
bash scripts/e2e.sh e2e/swap.spec.ts e2e/draft-drag.spec.ts
# EXIT=0；12 passed（swap 4 条 + draft-drag 8 条）
```

补口子内容：`useDraftAdjust.swap(..., expectedVersion?)` 与 `move(..., expectedVersion?)` 在调用方提供版本时写入请求体；`useSwapFlow` 新增 `getVersion`，`confirm()` 在确认瞬间读取当前草稿版本；`DraftEditorView` 注入 `detail.draft.version`。旧调用不传版本仍保持原请求体。

### 剩余问题

- 撤销栈仍只存在于当前页面会话；工具栏「刷新」保留栈，整页刷新清空，与规格和页面文案一致。
- 本票只做锁定显示与服务端拒绝反馈；锁定/解锁交互入口属于 06，E2E 通过锁定 API 构造「应用后加锁再撤销」。
- 方案搜索的预算耗尽/部分结果分支由单测覆盖；E2E 场景命中 `FOUND`，未人为把搜索预算压到耗尽。
- 09 复审补充：旧 move/swap 撤销现在也携带写入产生的 `expectedVersion`，他人后续修改会使撤销 409，不会把过期状态重放回去。
- 2026-11-03 生产反馈修复：旧落点判断把“同一时间存在别班课”误当跨班落点，导致真实课表里同班拖拽也失效。现由网格传入目标格**可见**课节，只有可见同班课才交换、没有可见课则普通移动，只有可见别班课才提示走显式入口；同时把“换课”改为“跨班换人”并只列跨班候选，同班交换只保留拖拽。E2E `draft-drag` 9/9、`swap` 4/4、`draft-locks` 8/8，前端 553 单测通过；证据截图见 [acceptance](../acceptance.md#2026-11-03-生产反馈修复同班拖拽被别班课程误拦)。
- 2026-11-03 后续修复已通过 PR #23 合并 main 并部署生产（只重建 frontend，Flyway 仍 47）；部署回执见 [deployment](../deployment.md)。
- 2026-11-03 同一误判补充修复：自动方案搜索原先只要目标时间出现别班课就报“只能在本班范围内换位”，导致同班直换失败后无法列方案。现改为只有目标时间**没有本班课**才拒绝；后端 `DraftCyclePlanIntegrationTest` 11/11、拖拽/换课 E2E 13/13 通过。已通过 PR #24 合并（`f71264e`）并部署，只重建 backend。

## Comments
