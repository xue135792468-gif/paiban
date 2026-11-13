# 06 Spec轴增量复核

范围：`90bd973e...70ab6a7`。

## 原 F1
**P1，已关闭。** 原报告“审批人”系称谓误写，实际目标是申请人、`request.operatorUserId`，不是执行审批的管理员；原 P1 与数量不变。`backend/src/main/java/com/paiban/notification/service/NotificationService.java:124-127` 仍按该字段派发；`backend/src/test/java/com/paiban/notification/NotificationDeliveryIntegrationTest.java:163-224` 验证 operator 与最终 Teacher 分流。

`backend/src/main/java/com/paiban/notification/service/NotificationRecipientResolver.java:71,97` 现只校验 `deletedAt`，不再过滤 `Teacher.active`；User 仍要求 `AppUser.enabled`（`:51,57`），多账号/仅停用禁发/无账号可信 UID 逻辑保留。`backend/src/test/java/com/paiban/notification/NotificationDeliveryIsolationIntegrationTest.java:337-405` 走真实入口，入队后仅关排课资格并保持账号/UID，断言两通知；旧停用禁发仍在 `NotificationDeliveryIntegrationTest.java:290`。

## 证据
`qc2-red.log:194-212` 为实际 0 对预期 2 的 BUILD FAILURE；`qc2-red.exit` 不存在，不补称。`qc2-green.exit=0`；`targeted-35-maven.exit=0`，35/0/0/0；受影响浏览器 3/3、`playwright.exit.txt=0`、`SOURCE_IDENTICAL=1`。真 PG/租户/手机未验。

## 计数
新增硬违反：P1=0；P2=0；P3=0。新增判断项=0。原 F1=1×P1 已关闭；不代主控放行。
