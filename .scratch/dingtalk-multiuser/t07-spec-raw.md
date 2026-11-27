# 07 Spec 轴只读评审（原始）

2026-11-13。源：dingtalk-t07-review-snapshot @ 64a8885cefda7782608d1a78a1a6f664cdf2f32a（工作树干净）；基点 e7d0bfc；命令 `git diff e7d0bfc...64a8885`（34 路径）。开工前资源：物理内存空闲 6.49GB/15.73GB，无并发压力。全程只读源，未跑测试/构建/网络。权威：spec.md 89–95、155–160；issues/07；t07-wire-contract.md；t07-probe-plan.md。

## 逐项符合（无缺陷）

1. 测试连接用未保存参数、换 token 后读 dept_id=1：DingTalkConnectionProbeService.java:27-43、HttpDingTalkProbeClient.java:54-86，独立适配器无常驻缓存（:22-48）。符合 spec.md:158。
2. 不写库/不发通知/不落审计：集成测试 204-235 实测库计数与配置不变；保存原子化 Controller:105-133（putAll 有 @Transactional，SettingsService:218）。
3. 权限：Controller:136-142 仅 integration:manage；集成测试 157-174 教务/老师 403；SettingsView.vue:45 整卡隐藏，Spec 组件 spec 断言隐藏且零请求。
4. 空 secret 复用边界（非空优先、显式 useSavedSecret、appKey 严格相等、有保存密钥）：Service:31-41；测试 118-154 覆盖含空白变体与空保存密钥。UI 提示与请求一致（DingtalkConnectionProbe.vue:61-69）。
5. 结果码与固定中文：DingTalkProbeCode.java:8-12；失败 200 且不含凭据（测试 177-191）；UI 固定成功说明（组件 49,87）。符合 wire-contract“不显示供应商正文”。
6. 输入变更后结果失效、loading 独立于保存：组件 71-74、SettingsView.spec 新增 231-243 断言测试期间保存未被锁。
7. 手册：三要素齐备——H5 微应用/权限/可见范围/参数/测试/预演（dingtalk-school-setup.md:19-70）、官方链接（:23,31,32）、mermaid 流程图（:7-17）、实机清单（:73-85，明确由学校在真实租户手机执行）、真实系统截图。我实看 connection-unsaved/rejected 两图：未启用标签、未保存 AppKey、密钥打码、绿/红固定文案，与图注一致。

## 发现

P3-1（c 行为偏差）HttpDingTalkProbeClient.java:66-68：token 阶段任何 4xx 一律 TOKEN_REJECTED，故 429 限流、404 端点不符也显示“未能使用本次 AppKey/AppSecret 获取访问令牌”。与 wire-contract 失败阶段分型意图有偏差，学校可能据此误重置密钥。
P3-2（c 部分行为）探测最坏 5s+15s×2=40s（:39-41），前端默认 30s（frontend/src/api/http.ts:18），慢网下客户端先中断，只得到通用“无法连接服务器”，丢失阶段原因；同类钉钉调用已用 180s（http.ts:14）。

## 不计数观察

- 90018/60020 说法有内部研究出处（t07-token-department-primary-research.md:20-21），离线无法独立复核，未计缺陷。
- README、install-single-user 属手册入口/边界文案，文档级、不越代码范围。Fake 的 `fake-probe:` 约定仅在 fake=true 生效，不构成生产路径。
- 08 准备文档未计；实机/租户/手机/真实 PG 及 full verify 未执行，非 07 规格缺陷，也不据此放行。

## 计数

P1 0；P2 0；P3 2。最坏项：P3-1，限流/端点错误被表述为凭据问题（仅提示，未断言）。未发现缺失或范围外功能新增；本轴不宣称 07 放行。