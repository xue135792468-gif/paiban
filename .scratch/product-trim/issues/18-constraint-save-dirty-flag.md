# 18: 约束配置并发保存后「有未保存的修改」不消失

**What to build:** 查明并修复 `constraints-concurrent-save.spec.ts` 中 pageA 保存成功后 `data-testid="constraint-unsaved"` 不消失的问题，让「保存成功 ⇒ dirty 标记清除」成为确定性行为；补一条能稳定复现的回归测试。

**Blocked by:** None（票 17 已完成，本票独立）

**Status:** ready-for-agent

## 症状（已复现，不是 flake）

- 用例：`frontend/e2e/constraints-concurrent-save.spec.ts:108`「选择放弃本地修改时加载最新配置，不写回旧内容」，失败点在 `:130`（pageA 点 `constraint-save` 后 `expectGone(pageA, 'constraint-unsaved')`）。
- 表现：`constraint-unsaved` 元素 **30 秒内 `count()` 恒为 1**（`expect(...).toPass({timeout:30_000})` 超时，Received 1），不是「晚一拍」。
- 复现率：`bash scripts/e2e.sh frontend/e2e/constraints-concurrent-save.spec.ts` 连跑 3 次，第 3 次失败（run1/run2 通过）。
- CI：run `42209420391` 首跑 e2e 1 failed（172 passed），`gh run rerun --failed` 后四 job 全绿 → 间歇性，但有真实根因，不能靠重跑蒙混。
- 归属：与票 17（草稿编辑器拖拽）无关；`ConstraintsView.vue` / `useConstraintSave.ts` 属票 14/07 约束配置。

## 已定位的根因（主代理 2026-11-06 读码核实，供执行者复核）

1. `frontend/src/components/constraints/ConstraintSummaryBar.vue:9`：`v-if="dirty"` 渲染 `data-testid="constraint-unsaved"`。
2. `frontend/src/views/ConstraintsView.vue:84`：`:dirty="dirty || conflict !== null"`。
3. `frontend/src/components/constraints/useConstraintDraft.ts:284`：`dirty = computed(() => snapshot.value !== JSON.stringify(payload()))`；只有 `markSaved()`（`:312-315`）会把 `snapshot` 对齐到当前 payload。
4. `frontend/src/components/constraints/useConstraintSave.ts:94-112`：保存 `PUT /constraints` 成功后依次 `conflict.value = null` → `ElMessage.success` → `options.onSaved?.()` → `await options.reload()`。
5. `frontend/src/components/constraints/useConstraintCollaboration.ts:265-273`：`onEffectiveSaved()` **只清 workspace / AI 状态，不调 `markSaved()`**。
6. `frontend/src/components/constraints/useConstraintDraft.ts:289-310`：`hydrate()` 末尾才 `markSaved()`；而 `reload()`（`ConstraintsView.vue` 的 `loadConstraints()`）会重新拉服务端配置并 hydrate。

**结论**：dirty 的清除被**间接依赖在 `reload()` → `hydrate()` 的副作用**上。若 reload 回来的配置与刚提交的 payload 不完全一致（服务端规范化、条目 id 重建、并发覆盖、请求被打断/未刷新），`snapshot` 就对不上，`dirty` 永远为 1。保存成功本身没有确定性地清 dirty。

## 建议的修复方向（执行者自行判断，不要照抄）

- 让**保存成功**直接成为清 dirty 的时机：在 `useConstraintSave.ts` 成功分支里（或 `onEffectiveSaved` 里）显式调用 `draft.markSaved()`，不再依赖 reload 的间接副作用；注意 `reload()` 之后 hydrate 会再对齐一次，要确认两者不冲突。
- 或者：查清 `reload()` 回来的 payload 为何与提交的不一致，修数据一致性（若属服务端规范化，应有契约说明）。
- **禁止**用「再加超时 / 再加重试 / 放宽断言 / skip」代替修复。上两次 retro 已因此误判为 flake（见 `.scratch/retro/2026-11-05-product-trim-15-16.md`）。

## 验收

- [ ] 建立 tight loop：一条能**稳定复现**「保存后 dirty 恒为 1」的测试（单元或 e2e），先红
- [ ] 修复后该 loop 绿，且 `constraints-concurrent-save.spec.ts` 连跑 ≥3 次全过
- [ ] 保存成功 ⇒ `constraint-unsaved` 消失；保存失败/409 ⇒ 标记保留且冲突条出现（既有行为不回归）
- [ ] 两人并发保存、放弃本地修改（take-theirs / keep-mine）两条路径都不回写旧内容
- [ ] `useConstraintSave.spec.ts` / `useConstraintDraft.spec.ts` 相应断言补齐（若存在）
- [ ] `scripts/verify.sh --full` 退出码 0；CI 四 job 全绿
- [ ] 修复提交里写明「根因 = 保存成功未确定性清 dirty，依赖 reload 间接副作用」

## 范围外

- 不引入新概念、不改后端接口/数据库、不动票 17 的拖拽实现。
- 不顺手修 Hark 另案（未知 `teacherIds` 静默忽略、`GET /api/scheduling-jobs/current` 500）。

## Notes

- 相关文件：`frontend/src/components/constraints/useConstraintSave.ts`、`useConstraintDraft.ts`、`useConstraintCollaboration.ts`、`ConstraintSummaryBar.vue`、`frontend/src/views/ConstraintsView.vue`、`frontend/e2e/constraints-concurrent-save.spec.ts`、`frontend/e2e/helpers.ts`（`seedConstraintScenario`）。
- 历史：该 spec 2026-11-01（HANDOFF:29）与 2026-11-05、2026-11-06 各抖动/失败过；`/retro` 已两次按「时序 flake」加固 `expectGone`，均未根治。这次读码证明根因是保存链路，不是渲染节拍。
- `CODING_STANDARDS.md:44-45` 已有「保存后状态收敛用 toPass」「同类抖动第二次出现按 retro 抽 helper 写标准」两条；本票修完后应复核这两条是否需补一句「先确认保存成功链路真的清了状态，别只加重试」。
