# 04: 飞书消息通知与卡片

**What to build:** 实现飞书消息通知能力：调代批准时发送飞书消息（支持 Markdown 与交互卡片）、失败降级到系统内通知、通知记录标记来源平台。完成后，教师在飞书里收到调代通知，点击卡片直接跳转到系统查看详情。

**Blocked by:** 01

**Status:** done

- [x] 飞书消息模板：调代批准通知（Markdown 格式，含课程名、时间、原/新教师、教室）
- [x] 飞书交互卡片：支持按钮（"查看详情"）、跳转链接、状态颜色标记
- [x] 通知发送：调代批准时调用 `FeishuNotificationChannel.send`
- [x] 失败降级：飞书发送失败时自动降级为系统内通知（CompositeNotificationChannel）
- [x] 通知记录：`notification_log` 表标记来源平台（dingtalk / feishu / system）
- [x] 单元测试：fake 飞书 API 验证消息发送与降级
- [ ] 集成测试：真实飞书沙箱企业验证（手动步骤，见 README「手动验收」）
- [x] E2E 测试：调代批准 → 飞书通知发送 → 通知记录查询（由后端集成测试 FeishuNotificationIntegrationTest 覆盖；浏览器无法观察飞书侧发送）
