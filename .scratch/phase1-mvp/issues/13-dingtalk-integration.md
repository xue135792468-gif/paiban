# 13: 钉钉集成（可选启用，含 Stream 模式）

**What to build:** 实现钉钉集成的完整能力且保持可插拔：OrgDataProvider/AuthProvider/NotificationChannel 三接口的钉钉实现与本地实现、配置开关 `paiban.integration.dingtalk.enabled`、H5 微应用免登（`dd.getAuthCode` → 后端换 userId → 颁发 JWT）、教职工从钉钉同步（入离职 Stream 事件接收）、调代通知发送钉钉工作通知（失败降级到系统内通知）。完成后，配置钉钉凭证后教师从钉钉工作台点击进入系统自动免登；admin 能一键同步钉钉教职工；关闭钉钉开关后系统完整可用（ADR-0005）。

**Blocked by:** 12

**Status:** ready-for-agent

- [ ] 三个接口（OrgDataProvider/AuthProvider/NotificationChannel）的钉钉实现与本地实现，配置开关切换
- [ ] 钉钉企业内部应用 H5 微应用：前端检测在钉钉环境则调用 `dd.getAuthCode`，后端 `/v1.0/oauth/users/auth` 换 userId，颁发 JWT
- [ ] 浏览器访问走本地账号密码登录，钉钉内访问走免登，同一套前端代码
- [ ] 教职工同步：启动时全量拉取钉钉组织 + Stream 模式（WebSocket 长连接）接收增量事件（入离职、调岗）
- [ ] 钉钉 userId 与本地 teacher 的映射（teacher.dingtalk_user_id 字段）
- [ ] 同步策略：钉钉为准 vs 本地覆盖，可在设置中切换（ADR-0005）
- [ ] 调代通知发送钉钉工作通知（Markdown + ActionCard 带跳转链接），失败降级到系统内通知（CompositeNotificationChannel）
- [ ] 钉钉通知的发送记录写入 notification_log（通道标记为 dingtalk）
- [ ] 管理 UI：钉钉配置页（AppKey/AppSecret 录入、启用开关、手动触发同步按钮、同步日志查看）
- [ ] REST Assured 集成测试（用 fake 钉钉实现）：免登流程、组织同步、通知发送、降级
- [ ] 手动验证清单：真实钉钉沙箱租户走通免登、同步、通知（不进 CI）
- [ ] 无钉钉环境的 E2E 测试（验收场景 3）保持绿色
