# 06 Notification 调用图与收口清单

## 1. NotificationService 直接/间接调用方与流向
- SubstitutionEffects.java:38 字段别名 `notifications`
  - :171 notifyEffective() -> notifications.substitutionEffective() [保留/改为统一事件]
  - :184 notifySwap() -> notifications.swapEffective() [保留/改为统一事件]
- SubstitutionAuditService.java:26 字段别名 `notifications`
  - :43 planFailure() -> notifications.substitutionPlanFailed() [06停发；失败审计保留]
  - :60 record() -> notifications.substitutionVerifyAlert() [06停发；失败审计保留]
- TimetablePublishService.java:146 发布事件
  - -> NotificationService.java:148 @EventListener onTimetablePublished() [06停发；模板保留，事件监听不等于已异步]
- NotificationService.java:57 泛型 notify() 是public方法（主线程实读纠正）
  - :94-97, :112, :121, :128, :135, :144, :159 各业务模板装配后调用 channel.send()
  - NotificationService.java:72 -> CompositeNotificationChannel.send()
- CompositeNotificationChannel.java:32 按顺序直调各渠道 send()
  - DingTalkNotificationChannel.java:67 -> client.sendWorkNotification()
  - LocalNotificationChannel.java:51 -> 本地落库 notification / notification_log
- 应退役/清理流向：
  - DingTalkNotificationChannel 的发送/三次重试移出业务主事务；三次重试本身沿用
  - 业务事务直接同步等待外发交付应退役，改为事务提交后异步派发

## 2. 前端 SettingsView 旧控件物理位置
- 控件1（通知范围）：
  - 嵌入位置：frontend/src/views/SettingsView.vue:30 `<SubstitutionPolicyCard v-model="form" />`
  - 定义组件：frontend/src/components/settings/SubstitutionPolicyCard.vue:26 `<el-select v-model="form['notification.scope']" ...>`
- 控件2（额外收件人）：
  - 嵌入位置：frontend/src/views/SettingsView.vue:31 `<SubstitutionRecipientsCard />`
  - 定义组件：frontend/src/components/settings/SubstitutionRecipientsCard.vue:9 `<el-select v-model="selected" ...>`

## 3. 公开“测试/发送通知”端点核查
- 扫描未发现公开发送/测试通知端点；仍须对业务调用方及public notify门面收口，不能据此保证所有路径已安全。
- 依据：全文扫描 Controller 无 `/notification*`、`/notifications*` 或发送/测试通知 REST 映射。
- 唯一相关入口为基础配置项读写：
  - SettingsController.java:55 (GET /settings), :72 (PUT /settings)
  - SettingsController.java:98 (GET /substitution-recipients), :110 (PUT /substitution-recipients)

## 4. 相关测试与旧同步期望现状
- 直接断言 notification_log / 外发行为的测试：当前 test 目录下无直接针对 DingTalkNotificationChannel 的测试
- 仅间接涉及配置键的测试：
  - SettingsOnboardingIntegrationTest.java:27, :41, :46 断言 `notification.scope` 配置初始化
  - TrimMigrationDataPreservationTest.java:270, :274 历史迁移数据包含 `notification`/`notification_log` 表结构
- 本次扫描未发现当前通知交付正例；06仍须复核最终集成源与新增审批路径，新增异步交付断言测试。

## 5. DatabaseResetExtension 异步等待指针
- 文件与行号：backend/src/test/java/com/paiban/support/DatabaseResetExtension.java:35-43
- 现状：beforeEach 仅执行 `TRUNCATE TABLE` 清空除保留表外的数据，并在 :47 发布 AiConfigChangedEvent；完全未等待外发通知异步线程池/队列清空，若引入异步交付存在测试跨用例数据污染隐患。

主线程2026-11-12复读调用者与仓库测试搜索后修正上述收口建议。此文仅为准备地图，不是06实现/外发验收；具体决策以t06-delivery-boundary.md为准。
