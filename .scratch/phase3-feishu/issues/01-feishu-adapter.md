# 01: 飞书适配层实现

**What to build:** 实现飞书适配层的三个核心接口：OrgDataProvider（组织数据导入）、AuthProvider（身份认证）、NotificationChannel（消息通知）的飞书实现。完成后，系统能通过飞书 API 拉取组织架构、验证用户身份、发送消息通知，与钉钉实现并存且互不干扰。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] `FeishuOrgDataProvider` 实现：调用飞书 `/open-apis/contact/v3/departments` 与 `/open-apis/contact/v3/users` 拉取组织架构
- [x] `FeishuAuthProvider` 实现：调用飞书 `/open-apis/authen/v1/access_token` 验证用户身份
- [x] `FeishuNotificationChannel` 实现：调用飞书 `/open-apis/im/v1/messages` 发送消息
- [x] 配置项：`integration.feishu.enabled`、`integration.feishu.app-id`、`integration.feishu.app-secret`
- [x] 飞书 API 客户端封装：统一处理认证（tenant_access_token）、错误重试、日志记录
- [x] 单元测试：fake 飞书 API 验证三个接口的实现
- [ ] 集成测试：真实飞书沙箱企业验证（手动步骤，不进 CI；检查项见 README「手动验收」）
- [x] 遵循 ADR-0005：飞书是可选适配器，本地数据为事实源
