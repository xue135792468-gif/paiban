# 06 Standards 增量原始轴

只读审 `90bd973...70ab6a7`；未跑测试/构建/网络/服务。Java 根=`backend/src/main/java/com/paiban/`，test 根=`backend/src/test/java/com/paiban/`，`t06-*` 在 `.scratch/dingtalk-multiuser/`。原轴保留：Hard P2=3，Judgement P3=2。

## 原发现处置

- **S1 P2 已关闭**：`notification/channel/LocalNotificationChannel.java:46` 已有 `REQUIRES_NEW`；`s1-http-red.exit=1`，同测日志显示 `notification` 残留 2 行，green exit=0；新增 HTTP 原子性测试见 `test/notification/NotificationDeliveryIsolationIntegrationTest.java:300-334`。35 条最终定向 exit=0、源一致=1。
- **S2 P2 已关闭**：`test/support/NotificationExecutorTestSupport.java:16-40` 用单 worker FIFO sentinel + queue/active 有界归零；`test/notification/NotificationDeliveryIntegrationTest.java:342,360` 与 `test/substitution/NotificationBusinessIntegrationTest.java:176` 已改为 drain 后 `verifyNoInteractions`，三处 `after(500).never()` 消失。
- **S3 P2 已关闭**：`notification/service/NotificationRecipientResolver.java:71,97` 均仅保留 `deletedAt == null`，不再以 `Teacher.active` 判账号；`test/notification/NotificationDeliveryIsolationIntegrationTest.java:337-406` 覆盖入队后 active=false、account enabled+UID、两条外部通知。`qc2-red.log` 为真实 0/预期 2 断言失败，原包装无 exit 文件，green exit=0。
- **J1 P3 保留**：`notification/service/NotificationService.java:130-146` 与 `substitution/service/SubstitutionEffects.java:193-217` 的模板变量装配仍是重复形状；主控已裁定两动作语义不同、不是独立规则缺陷，本次不强制通用工厂。
- **J2 P3 已关闭**：`notification/service/NotificationFailureRecorder.java:22` 已删无用参数；`notification/service/NotificationService.java:86-89`、`substitution/service/SubstitutionEffects.java:190`、`substitution/service/SubstitutionAuditService.java:33-57` 已清理闲参与计算，最终 35 条通过。

## 新增复核

Hard 新增 P1/P2/P3=0/0/0；Judgement 新增 P1/P2/P3=0/0/0。QC1 发送记录 5/5 通过、eslint/tsc=0；类型中文化、受理/留存说明、权限及安全机制未放宽。受影响 3 条浏览器测试真实 green，`source-stability=1`；`70ab6a7` 与 main `877dee9` 只读 diff 为空。未验证 PostgreSQL、真实租户或手机送达。

## Counts

- 原轴：Hard P1=0、P2=3、P3=0；Judgement P1=0、P2=0、P3=2。
- 处置：S1/S2/S3 关闭（3 个 P2），J2 关闭（1 个 P3），J1 保留（1 个 P3，经裁定非延期缺陷）。
- 新增：Hard 0；Judgement 0。
- 残余：Hard 0；Judgement P3=1（仅 J1）。最坏新增项：无。
