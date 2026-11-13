# 06 施工协调快照

2026-11-13；这是施工进度，不是单票放行。原八票的最终 full verify 尚未执行，未推送、未部署。

## 当前边界

- 05 已放行，见 t05-review.md；06 基点 23b789f。通知收口、事务与重试按 t06-delivery-boundary.md，日志查询按 t06-wire-contract.md，主控闸门按 t06-quality-plan.md。
- 集成分支 codex/dingtalk-multiuser，工作树 E:/排课系统/.worktrees/dingtalk-integration。
- transport 已合入 8a0ed26（候选 9ca3892），源和 stub 测试由主控查验；交付见 t06-transport-delivery.md。原始日志已复制到 logs/t06-transport/，逐文件 SHA-256 核对。该部分不代表整个通知链已验收。

## 独立写集

|工作树/分支后缀|范围|当前状态|
|---|---|---|
|dingtalk-t06-core|通知渠道/事务异步、业务 hooks、测试 reset、业务集成、ADR0014|施工中；两条纵向正例日志已绿，待可构建 checkpoint 提交，然后继续剩余业务场景|
|dingtalk-t06-transport|HttpDingTalkClient.sendWorkNotification、HTTP stub、交付|完成并合入；worker 已关闭|
|dingtalk-t06-ui|设置页、策略卡、发送记录卡/API、组件测试与 E2E 源|6563767 已合入 c423d26；定向与静态检查已绿，浏览器尚未执行|
|dingtalk-t06-log-api|notification/web、NotificationDeliveryLogQueryService、独立 API 集成测试|施工中；不修改 NotificationLogRepository 或 core 文件|
|dingtalk-t06-isolation|只写独立故障测试与交付|从 core 稳定 checkpoint 0256581 建树；首条故障测试已准备，尚未运行 Maven，等查询 worker 释放测试槽|

core 不修改上述查询文件；UI 不写 Java，transport 不写其它集成行为。NotificationDeliveryIsolationIntegrationTest 由独立 QA 负责，计划见 t06-isolation-qa-plan.md。UI 合并补丁检查曾命中文档两处 Markdown 行尾空白（退出2），主控已移除；不是源码或测试失败，原始 merger 结果保留。

## 下一步

1. core 稳定 checkpoint 0256581 已建立，独立故障 QA 已派；core 继续收件/重试/退休触发回归。
2. worker 最终必须 merge 集成 tip、保存真实定向日志/退出码、只提交各自路径；merger 顺序合入。一次最多两路 Maven，Vitest 不与 E2E 并发。
3. 主控真实浏览器 runner 已准备在 .runtime/t06-browser-run1/（实际位于本特性目录下），尚未运行；源哈希、隔离端口28406/25406和截图必须实验核验。
4. 固定整合候选双轴复查、整改增量复核与单票放行后才施工07。t07-wire-contract、t07-probe-plan、t08-acceptance-plan只是准备材料。

被自动审核拒绝的历史缓存清理继续按 issues/09-temporary-cache-cleanup.md 保留，未换方法删除；06 runtime 同样先留证，不能声称所有任务文件已清理。
