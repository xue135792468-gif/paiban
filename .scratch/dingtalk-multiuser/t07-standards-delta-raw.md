# 07 Standards 轴增量复查（独立、只读）

- 审源：`E:/排课系统/.worktrees/dingtalk-t07-review-snapshot`，HEAD=`b253112928b0265f4172684ef60e39d04b037f84`（已 fast-forward，工作树干净）。
- 增量：`git diff 64a8885cefda7782608d1a78a1a6f664cdf2f32a...b253112`，18 路径；`-- backend` 净 diff 0 行（与主控口径一致）。
- 提交：`b253112` merge main into fixer；`bd1bef1` share e2e helpers and budget probe；`1d1d61b` checkpoint remediation；`bdd66a9` review decisions/preparation。
- 资源：FreeGB 6.1/15.73（38.8%）。只读代码与已有文档；未跑测试/构建/网络/服务、未删除、未改源/ticket/主控判定、未读另一轴、未读 `.env`。

## 原发现处置（静态核验）

- **H1 P2 已关闭**：`frontend/e2e/helpers.ts:44-51` 新增导出 `trackBrowserErrors`，函数体与原先逐字一致（console error + pageerror，无 try/catch、无过滤）；`dingtalk-connection.spec.ts`、`dingtalk-login.spec.ts` 改 import，`Select-String` 全 e2e 已无本地同名定义。现由 `scripts/check-e2e-helpers.sh`（只查导出名）自动覆盖，符合《测试》条款。
- **J4 P3 已关闭**：`DingtalkConnectionProbe.vue:67-70` 局部 `hasText(value)=trim().length>0`，仅用于空判断；:71-86 三处 computed 改用它；发送值（:102-103）与 `appKey.value === savedAppKey`（:76）仍为原值严格比较，与后端 `DingTalkConnectionProbeService.java:37` 语义一致。新增三条空白用例（spec:236-303）与实现对齐。
- **J7 P3 已关闭**：`helpers.ts:13` 导出 `SUPER_ADMIN`（值未变），`apiLogin` 默认参数改为引用它（:55-56，显式 `string`）；connection/account-audit/roles 三 spec 改 import，使用点各 2/2/5 次，无未用导入。
- **J1/J2/J3/J5/J6 维持原 P3**：主控已逐条裁定不采纳；我按给定边界只看增量的前端与测试，未取得需提升等级的新证据（如 J6 交叉类型确在缩窄编辑态、J5 末尾 probe 断言确在证明旧 secret 未被覆盖）。

## 新增 Judgement（possible，非硬违反）

- **J8｜P3 possible Duplicated Code（测试常量）**：新增请求预算在 `DingtalkConnectionProbe.spec.ts:50,104`、`SettingsView.spec.ts:253` 各写一次 `60_000`，:310 再以魔数 `40_000` 表达「大于串行最坏值」，与 `DingtalkConnectionProbe.vue:55` 的 `PROBE_TIMEOUT_MS` 共四份。仓库已有 `http.ts:7,14` 集中预算与 `SettingsView.spec.ts:19` 回引真实常量的先例（`LoginView.vue:128-130` 则为局部常量先例，故不判违规）。最小建议：把该预算移入 `@/api/http.ts` 导出、组件与两侧 spec 引用，预算断言改为与常量比较。

## 新发现明细

Hard：P1=0，P2=0，P3=0（原 H1 已关闭，现存 Hard 0）。Judgement：新增 P3=1（J8）；未关闭 P3=6（J1/J2/J3/J5/J6 已裁定 + J8）。
最坏项：J8（P3 测试常量重复），原最坏 H1 已关闭。
遵守边界：未碰 backend/绑定/权限/迁移（增量本身为 0）；`PROBE_TIMEOUT_MS` 仅本探测，全局 30 秒与 `DINGTALK_SYNC_TIMEOUT_MS` 180 秒未变。
运行待主控：四组受影响 E2E 我未执行；fixer 的中间态日志被同名覆盖、首红无独立 `.exit`，我不据此宣称行为红绿，也不代主控宣称放行。