# Standards 首次 delta 复核更正（J5 撤回，d7ee6ff）

- 原报告 `whole-standards-first-delta-raw.md` 原文保留不改；本文件为自纠与计数更正。
- **J5 撤回（误报）**：同一只读快照 `E:/排课系统/.worktrees/dingtalk-standards-fixes-snapshot`（HEAD `d7ee6ffca6456296f5daf654809d6c8516b6f3ea`）逐 test 核对：
  - `dingtalk-sync.spec.ts` 两个 UI 用例（`test(...page)` :19、:127）分别在 :108、:267 体内 `expect(browserErrors).toEqual([])`；
  - `account-audit.spec.ts` 两个 UI 用例（:36、:169）分别在 :152、:216 体内断空。
  两文件均无 API-only 用例、无「收集后无人消费」的用例；「无 afterEach」只是断言写在体内（位于 `finally` 前），不是无守卫。体内断言位于其余步骤之后，任何前置失败本身即判红，不存在静默通过。故无需 fixer 补重复 afterEach。
- **更正后计数**：Hard **P1 0 / P2 0 / P3 0**（H1 仍判关闭，不受本更正影响）；Judgement **P1 0 / P2 0 / P3 1**——仅 **J4（P3，主控不采纳、非延期、未修复）**。J1/J2/J3 关闭结论不变。
- 最坏项：J4（P3 判断建议），无未决 Hard；M1 真导航红例在另一阶段施工，不在本快照。
- 边界不变：未跑测试/构建/网络，未 stage/commit；R6 为交付方报告、R5 为环境误设红、writeset 前后哈希不构成测试前后源冻结；不宣称整规格通过。
