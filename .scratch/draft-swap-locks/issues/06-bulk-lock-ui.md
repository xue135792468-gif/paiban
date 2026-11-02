# 06 单节、多选、整天与整班批量锁定

Status: done
Type: task
Blocked by: 01、05
Owner: worker
日期：2026-11-02

## 目标与输入

01批量接口、05编辑器集成结果；规格§四。
前置票必须验收并合入后才能认领；ready-for-agent表示规格已就绪，不表示依赖已完成。
共同约束见[规格](../spec.md)、[接口契约](../contracts.md)、[执行路线](../ticket-plan.md)。

## 写入范围与边界

草稿编辑器锁定工具栏/选择模式、DraftGrid标记、锁定API客户端和单测/E2E；不改后端锁定契约。
只修改本票必要文件；发现契约冲突先由统筹统一更正，不能让多个worker各自猜接口。不得改生产数据或部署。

## 实施步骤

1. 增加清晰的锁定图标和单节锁/解锁；多选模式与拖拽分开，选中状态不能被当作立即移动。
2. 批量范围为已选授课、当前行政班某星期、当前行政班全表；操作前明确班级、范围、实际节数，解锁对称。教师视角不把多班全局选择解释为当前班整表。
3. 刷新后从服务端恢复锁；有位置但违规允许锁并保留问题提示，未排时间给出原因；批量失败整单不改变。
4. 点击和键盘可完成选择、取消、上锁和解锁；加载/提交时防重复触发并保持权威响应。

## 验收清单

- [x] 同科多次课只锁被选中的具体那节，单节与三个批量范围数量/范围准确。
- [x] 重载保留标记，锁课不能拖出/挤走/通过跨班换人绕过。
- [x] 混合有效/无效批次不出现部分成功，错误可定位；重复操作不误报数量。
- [x] 多选模式不触发拖拽，键盘与点击可完成同等操作。

## 验证与交付

定向组件单测、单节/批量/刷新/键盘Playwright；与05/08不并发改共享页面。
每票提交Delivery：实际改动文件、分支/基点/提交、真实命令与退出码、剩余问题；全量门禁统一由09执行。
任务前检查资源；实际执行默认group/deepseek、high；机械搜证仅group/gemini3.8；算法结论由主模型负责。
遵守AGENTS/CODING_STANDARDS，不同worktree隔离；结束清理本票临时文件与进程。

## Delivery

**实现分支**：`codex/t06-lock-ui`（worktree `E:/paiban-wt/t06-locks`）
**基点**：`codex/draft-swap-locks`（含 01–05，基点 HEAD `cc6f1fd`）
**提交**：`feat(draft): lock lessons singly and in batch`

### 改动文件

新增：

- `frontend/src/composables/useDraftLocks.ts`（选择模式、范围展开、批量锁定、确认文案）
- `frontend/src/composables/useDraftLocks.spec.ts`（9 条）
- `frontend/src/components/draft/DraftEditorControls.spec.ts`（5 条）
- `frontend/e2e/draft-locks.spec.ts`（7 条 Playwright）

修改：

- `frontend/src/api/draft.ts`（`setLocks` PUT `/timetable-drafts/{id}/lessons/locks`）
- `frontend/src/components/draft/types.ts`（`LockResult`）
- `frontend/src/components/draft/DraftEditorControls.vue`（单节锁/解锁、多选、按范围下拉、页面说明）
- `frontend/src/components/draft/DraftGrid.vue`（`selectionMode`/`selectedLessonIds`、点击与键盘选择、`data-selected`/`aria-pressed`/`selected` 样式，多选禁拖）
- `frontend/src/components/draft/DraftGrid.spec.ts`（新增 6 条选择模式/键盘/样式用例）
- `frontend/src/views/DraftEditorView.vue`（接线 `useDraftLocks`、批量确认、锁课换课/调位拦截；593 行 ≤ 600）
- `frontend/src/help/docs/manual-adjust.md`（锁定课节帮助段）
- `frontend/e2e/helpers.ts`（`seedDraftLockScenario`：同科三节 + 跨班课节）
- 本票面

### 验证命令与真实退出码

```bash
cd /e/paiban-wt/t06-locks/frontend
npm run lint
# EXIT=0
npx vue-tsc -b
# EXIT=0
npx vitest run --no-file-parallelism src/composables/useDraftLocks.spec.ts src/components/draft/DraftGrid.spec.ts
# EXIT=0；Test Files 2 passed；Tests 26 passed（useDraftLocks 9 + DraftGrid 17）
bash /e/paiban-wt/t06-locks/scripts/check-sfc-size.sh
# EXIT=0（DraftEditorView.vue 593 行）
bash /e/paiban-wt/t06-locks/scripts/check-e2e-helpers.sh
# EXIT=0

cd /e/paiban-wt/t06-locks
bash scripts/e2e.sh e2e/draft-locks.spec.ts e2e/draft-drag.spec.ts e2e/swap.spec.ts
# EXIT=0；19 passed（draft-locks 7 + draft-drag 8 + swap 4），4.6m
```

另跑 `src/components/draft/DraftEditorControls.spec.ts`（5 条）通过，未列入第 6 节固定命令。未运行全量 `verify.sh`、未起 Docker（按票面约束，留给 09）。

### 实现要点

- 只锁具体 `lessonId`；范围展开在客户端（所选 / 当前行政班某天 / 当前行政班全表），只含 `periodId != null` 的课，服务端仍按明确 id 重新校验。
- 批量确认文案含班级、范围、实际节数与锁定/解锁动作；混合无效 id 由服务端整批 400，前端显示原因并重载权威状态，不部分成功。
- 成功后按服务端 `updated` 计数提示；`updated=0` 提示「这些课节已经是目标状态」。409/400 重载但保留选择供重试。
- 多选模式 Grid `draggable=false`，点击/Enter/Space 只切换选择；单击/键盘可完成选择、取消、锁定、解锁。教师/教室视角按天/全班项隐藏并提示切到行政班。
- 锁标记来自 `detail.lessons[].locked`，刷新恢复；冲突样式与锁定样式独立，锁课仍显示冲突。

### 剩余问题

- 批量确认由 composable 内 `ElMessageBox` 完成，视图只 bind；若后续拆 `DraftLockToolbar`，确认文案测试需同步迁移。
- E2E 混合失败用例走 API 直调（UI 无法稳定构造越界 id），后端 `DraftLockIntegrationTest` 另覆盖未排课/他稿/空批次。
- 撤销因后加锁失败沿用 05 的 E2E 用例（`draft-drag.spec.ts`），本票未重复。
- 09 复审补充：客户端解锁未排课节不再比服务端更严（服务端只拒绝“锁定”无位置课节）；E2E 公共 helper 已收敛到 `helpers.ts`。
- 全量门禁、真实 PostgreSQL 与合并发布由 09 统一执行。

## Comments
