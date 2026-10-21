# 04: 前端拆分、撤销栈与批量校验接入

**What to build:** 把 771 行的 `DraftEditorView.vue` 拆成编排层 + `DraftGrid.vue` + `useDraftAdjust.ts`；新增会话内撤销栈（最多 20 步，按钮 + Ctrl+Z，移动的逆操作是移回，换课的逆操作是再换一次）；拖拽开始时用一次 `validate-moves` 替代每个节次各发一次的 40 个请求。完成后，现有拖拽移动行为不变，但多了撤销和一次请求的校验。

**Blocked by:** 02

**Status:** done

- [x] 拆出 `components/draft/DraftGrid.vue`：网格、拖拽、单元格状态
- [x] 拆出 `composables/useDraftAdjust.ts`：接口调用、撤销栈、统一错误提示
- [x] `DraftEditorView.vue` 只保留编排、工具栏、视角切换（≤600 行）
- [x] 拖拽开始改为一次 `move-targets` 请求，覆盖所有目标节次
- [x] 撤销栈：最多 20 步、按钮 + Ctrl+Z、刷新清空
- [x] 撤销失败（外部已改动）时提示并保留栈
- [x] `useDraftAdjust.spec.ts`：入栈/出栈、上限、失败不弹栈
- [x] 现有 `e2e/scheduling.spec.ts` 的拖拽用例保持通过
