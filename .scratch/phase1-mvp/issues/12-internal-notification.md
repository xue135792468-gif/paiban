# 12: 系统内通知

**What to build:** 实现系统内通知能力：通知表、通知中心 UI（右上角铃铛 + 未读数 + 下拉列表 + 详情页）、通知模板引擎（Thymeleaf 字符串模板 + 变量替换）、调代批准时触发通知给当事人（原教师、代课教师、班主任）、通知范围可配置（ADR-0014）。完成后，teacher 收到"您提交的调代申请已批准"的系统内通知，李老师收到"您需代高一 1 班周三第 3 节数学"。

**Blocked by:** 11

**Status:** ready-for-agent

- [ ] notification 表（接收人、模板 code、变量 JSON、是否已读、创建时间、读取时间）
- [ ] notification_log 表（发送记录：谁、何时、什么内容、通道、成功/失败）
- [ ] 通知模板引擎：Thymeleaf 字符串模板，变量包括课程名、时间、原/新教师、教室、原因等
- [ ] NotificationChannel 接口定义（`send(recipients, templateCode, variables)`），本票实现 `LocalNotificationChannel`（系统内通知）
- [ ] 调代批准时调用 NotificationChannel 通知当事人（原教师、代课教师、班主任）
- [ ] 通知范围配置项 `paiban.notification.scope`（minimal / with-grade-leader / with-admin）生效
- [ ] 前端：右上角铃铛图标 + 未读数 badge + 下拉最近 10 条 + 独立通知中心页（分页、标记已读、全部已读）
- [ ] 通知内嵌链接：点击跳转到相关页面（如调代详情）
- [ ] REST Assured 集成测试：调代批准 → 当事人收到通知 → 标记已读
- [ ] Playwright E2E 测试：铃铛未读数正确，点击通知跳转到对应页面
