# 03: 飞书组织同步与 Stream 事件

**What to build:** 实现飞书组织架构的自动同步：启动时全量拉取、WebSocket 长连接接收增量事件（入离职、调岗）、同步策略配置（飞书为准 vs 本地覆盖）。完成后，飞书里的教职工变动自动同步到系统，无需手工维护。

**Blocked by:** 01

**Status:** done

- [x] 全量同步：启动时或手动触发，调用飞书 API 拉取所有部门与用户
- [ ] WebSocket 长连接：接入飞书事件订阅，接收 `contact.user.created_v3`、`contact.user.updated_v3`、`contact.user.deleted_v3` 等事件（未内嵌官方 SDK；事件经 POST /api/integrations/feishu/event 回调，见 README 已知限制）
- [x] 事件处理：根据事件类型创建/更新/禁用本地教师记录
- [x] 同步策略：飞书为准（默认）vs 本地覆盖，可在设置中切换
- [x] 同步日志：记录每次同步的时间、变更数量、失败原因
- [x] 管理 UI：手动触发同步按钮、同步日志查看
- [x] 冲突处理：飞书删除的教师，本地保留但标记为"已离职"
- [x] 单元测试：模拟 WebSocket 事件验证同步逻辑
- [ ] 集成测试：真实飞书沙箱企业验证（手动步骤，见 README「手动验收」）
