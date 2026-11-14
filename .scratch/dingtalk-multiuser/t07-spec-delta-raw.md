# 07 Spec 轴增量复查（原始）

2026-11-13。源 snapshot 只读 fast-forward 至 b253112928b0265f4172684ef60e39d04b037f84（工作树干净），比较 64a8885...b253112 非空 18 路径，backend 净 diff 0、http.ts 与 SettingsView.vue 未改。开工资源空闲 6.07/15.73GB（串行）。未跑测试/构建/网络/E2E，未改源与判定，未读另一轴。

## 原发现处置

- **Spec P3-1（token 阶段 4xx）**：维持原 P3，不升级。可核事实未变：`HttpDingTalkProbeClient.java` 本轮零改动，文案为“未能…获取访问令牌，请检查凭据和应用权限”，是阶段失败＋检查建议，没有断言“密钥错误”；按 wire-contract 只精分已核实供应商码，故属已裁定判断项而非新证据。原文与最坏项不改写。
- **Spec P3-2（30 秒前端预算）**：已修复，静态核实可关闭。`DingtalkConnectionProbe.vue:51-56,99-108` 仅探测 POST 带 `PROBE_TIMEOUT_MS=60_000`，大于 5s+15s 两跳约 40s 最坏预算；全局 30s（http.ts:18）与同步 180s（:14）未变；`SettingsView.spec.ts` 同步第三参数，组件新增预算断言 >40s（无 sleep）。未保存值、固定安全文案、独立 busy、迟到响应失效等原断言全部保留。

## 新增核对

- J4 空判断：组件 `hasText`（:64-70）只判“是否填了内容”，发送的 AppKey/AppSecret 仍原值不 trim，复用比较仍是原字符串严格相等，与后端 `DingTalkConnectionProbeService.java:28-40` 的 hasText + 严格 equals 一致；纯空白 key 禁用、纯空白 secret 走显式复用、`saved-key ` 绝不借用旧 secret 三例均有正向断言（spec:236-313）。复用放宽边界未被打开。
- H1/J7：`trackBrowserErrors` 原样移至 helpers.ts:44-51（console error + pageerror，无吞错），四个 spec 改 import 且仍实际断言为空；`SUPER_ADMIN` 值、`apiLogin` 默认口令与原字面量相同。
- 范围外新增：无。仅前端空判断/请求预算/测试 helper 与其测试，未加端点、端点参数、校验平台或后端行为。

## 不计数观察

hasSecret=false 且 secret 全空白时按钮静默禁用（与既有空 secret 基线一致，不发错误请求）；SUPER_ADMIN 为共享可变对象，目前无 spec 改写。学校真实租户/手机按手册第七节人工清单执行，非本票缺失。

## 运行边界

fixer 最终 `vitest-final-run1.log` 24/24、`.exit=0`、10 文件 before/after 哈希一致，我已只读核对；本次 E2E 尚未执行，不能写已通过；full verify/真实 PG 未跑。

## 计数

新增 Spec：P1 0、P2 0、P3 0。原 Spec P3=2 保留（P3-2 已修复关闭，P3-1 维持已裁定）。最坏项仍为原 P3-1。本轴不就 07 放行表态。