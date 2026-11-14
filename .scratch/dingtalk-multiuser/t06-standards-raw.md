# 06 Standards 原始轴

只读审 `23b789f...90bd973`；未跑测试/构建/网络/服务。主控 33/33、浏览器 6/6、`EXIT=0`、`SOURCE_IDENTICAL=1` 及截图仅证明 H2/Fake，不证明 PG、真租户或手机。路径根：Java=`backend/src/main/java/com/paiban/`，test=`backend/src/test/java/com/paiban/`；`t06-*` 在 `.scratch/dingtalk-multiuser/`。

## Hard

- **P2 本地渠道跨表写非原子**：`notification/channel/LocalNotificationChannel.java:44-67,92` 先写多行 `notification`，再写 `notification_log`；方法无事务，且 `notification/service/NotificationDeliveryGateway.java:22` 为 `NOT_SUPPORTED`，各 repository 默认事务独立提交。违反 `CODING_STANDARDS.md:14`“写多张表的业务动作须同事务”及 `t06-delivery-boundary.md:31`；中途失败会留部分通知或日志断档。最小修复：本地入口加 `REQUIRES_NEW`，并测任一通知写失败时两表全回滚。
- **P2 异步负例依赖固定观察窗**：`test/notification/NotificationDeliveryIntegrationTest.java:336,354` 和 `test/substitution/NotificationBusinessIntegrationTest.java:170` 用 `after(500).never()`。违反 `t06-delivery-boundary.md:43,52`“不可用固定 sleep 赌时序”。最小修复：按 executor queue/active-count 有界 drain 后立即 `verifyNoInteractions`。
- **P2 把排课资格当作账号停用**：`notification/service/NotificationRecipientResolver.java:71,97` 两条分支都过滤 `Teacher.isActive()`；但 `docs/adr/0036-dingtalk-first-login-identity-and-password-recovery.md:11`、`t03-module-plan.md:18` 和 `t06-delivery-boundary.md:15,29,46` 明确账号看 `AppUser.enabled`，可信无账号 Teacher 可按 UID 发。结果会静默漏掉 enabled 账号、有效 UID 但排课资格关闭的审批/接课通知。源码已确认，反例尚未运行。最小修复：两处仅保留 `deletedAt == null`；User 目标按 enabled，Teacher 目标用 enabled 账号加可信 UID。加入“入队后 active=false、account enabled+UID，预期两通知”真红测。

## Judgement

- **P3 possible Duplicated Code + Data Clumps**：`notification/service/NotificationService.java:131-147` 与 `substitution/service/SubstitutionEffects.java:195-217` 重复 class/course/date/period/substitute 变量拼装。抽成有类型渲染上下文或单一工厂。
- **P3 possible Mysterious Name + Speculative Generality**：`notification/service/NotificationFailureRecorder.java:22-32` 的未用 `errorMessage` 与实际写入 `errorCode` 混淆；`notification/service/NotificationService.java:86-89` 未用 `summary`，`substitution/service/SubstitutionEffects.java:191` 仍计算；`substitution/service/SubstitutionAuditService.java:34-45` 的 `alert`、`requesterTeacherId` 已休眠。删除误名/未用参数与计算并更新调用方。
- 未有明确证据：Feature Envy、Repeated Switches、Shotgun Surgery、Divergent Change、Primitive Obsession、Message Chains、Refused Bequest；文档要求的 gateway/事件边界使 Middle Man 被 repo 覆盖。工具已查项不列。

## Counts

- Hard：P1 **0**、P2 **3**、P3 **0**。
- Judgement：P1 **0**、P2 **0**、P3 **2**。
- 最坏：**P2 active/enabled 混用导致静默漏通知**。

