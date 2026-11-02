# group 身份回归修复交付（本批最后新增项）

分支 `codex/weekly-group-regression`，基线 `0ec3529`，工作目录 `.worktrees/weekly-group-regression`。范围只有 `useConstraintDraft.ts` 的 group key 生成/预留、主模型回归 test 的必要加强，以及 ADR-0025 一段文档澄清；Java、后端协议与模型均未改动。

## 问题与根因

`nextKey` 用一个自增 `keySeed` 同时命名 entry、slot 和 group。页面重载时 `hydrate` 只为每张卡消耗 `entry`/`slot` 两次（三张卡后 `keySeed=6`），而生成器并不知道已持久化的 `group-3`、`group-6`、`group-9`；再 `addEntry` 就可能生成 `group-9`，与第三条卡上已保存的要求撞车。四条要求共用两个相同的 groupKey 后，重载按 groupKey 合并，独立暂停身份被并成三条。

## 修复（最小方案）

1. module 私有 `reservedGroupKeys: Set<string>`，记录已占用的 groupKey；按同一 draft 模块内永不 `clear()`，避免同页多个 draft 实例互相撞。
2. `hydrate` 在构建任何 row 之前，先预留传入 `configs[*].entries[*].groupKey`（trim 后非空）。
3. `restoreRows` 预留快照 rows 里 `entries[*].groupKey`。
4. `nextKey` 保持其它 prefix 原方式；`group` prefix 若命中已预留值就继续递增，生成后加入预留集合。
5. 不修改已保存 groupKey、不改动既有 entry id、不新增后端协议或 crypto 依赖、不重写模型。

## 回归测试加强（保留主模型原始往返断言）

`weeklyGroupIdentity.spec.ts` 保留主模型的原始往返断言，并补充：

- 加上第 4 张卡后，内存草稿的 4 张卡拥有 4 个不同 groupKey（直接钉住生成本身的碰撞）；
- 保存 payload 仍是 4 条、4 个不同 groupKey；
- reload 后仍是 4 条、4 个不同 groupKey；
- 第 4 条要求保留自己的时段（`periodNo=4`）与班级（`classIds=[21]`）。

## ADR-0025 澄清（P3 文档误导）

原文把 `plan` / `verify` 写成共用搜索预算。现在明确：`--node-budget` 只属于 `plan`，默认 50000 节点、时间预算固定 200ms，调用者可以显式增大节点数（默认未变）；`NO_SOLUTION`/退出码 2、`SEARCH_BUDGET`/退出码 3 只适用于 `plan`，`plan` 找到方案为 0。`verify` 不搜索、也不接受 `--node-budget`：校验通过 0，错误或硬约束违规 1，没有 2/3 两种搜索状态。README 已正确只描述 `plan`，Java 未改。

## 红绿证据

| 步骤 | 结果 |
|---|---|
| RED（主模型原 test，日志 `group-identity-red.log`，已清理） | exit **1**，`Test Files 1 failed (1)`，`Tests 1 failed (1)`；reload 后 entries 期望 4 实得 3 |
| RED（本次复核：临时换回 pre-fix 生成器 + 加强后 test） | exit **1**，`Test Files 1 failed (1)`，`Tests 1 failed (1)`，`AssertionError: expected 3 to be 4`（第 4 张卡生成的 key 与已存在的 group-9 相同）；随后从哈希一致的备份恢复修复版 |
| GREEN（主模型 test 单文件） | exit **0**，`1 passed (1)` |
| GREEN（weeklyGroupIdentity + useConstraintDraft + entryChecks + weeklyScope + entryModel） | exit **0**，`Test Files 5 passed (5)`，`Tests 62 passed (62)` |
| GREEN（constraints + draft 定向） | exit **0**，`Test Files 30 passed (30)`，`Tests 214 passed (214)`（前批 29/213 加本新增 1） |
| 前端质量门 | `npm run lint` exit 0；`npm run build` exit 0（vue-tsc + vite，仅既有 chunk 警告） |
| 仓库检查 | `check-sfc-size.sh`、`check-blank-checks.sh`、`check-module-boundaries.sh`、`check-local-dates.sh`、`check-e2e-helpers.sh`、`check-ticket-status.sh` 全部 exit 0 |

## 限制

- 未跑 Java 测试、Playwright 与全量 `verify.sh`：本补丁没有 Java 变更，浏览器/全量门禁留给主线程。
- `reservedGroupKeys` 按设计在模块生命周期内只增不减，占用上限是会话内出现过的不同 groupKey 数量。
- `restoreRows` 的预留与 hydrate 共用同一个 helper，没有单独的测试：要构造独立用例必须依赖全局 `keySeed` 的具体数值，测试会脆且价值低。
