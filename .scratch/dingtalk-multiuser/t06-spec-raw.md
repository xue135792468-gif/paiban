# 06 Spec轴原始报告

`90bd973e...23b789fd`。

## P1
1. **(c) 实现错误。** `spec.md:76-77` 要求审批人及最终接课人收钉钉通知；`t03-module-plan.md:18` 明定“`Teacher.active`是排课资格，不等同于账号enabled”。`NotificationRecipientResolver.java:71,97` 却在两类映射均过滤 `isActive()`。故账号启用、UID已绑定但排课资格关闭时，审批人不走钉钉，接课人可能连本地降级也跳过。

最小修：两处删 `isActive()`，补“入队后停排课资格、账号/UID不变”红测，断言两通知仍发。33测试及6/6浏览器未覆盖；红测未跑，不称复现。

## P2/P3
无。

计数：P1=1，P2=0，P3=0；最坏=P1。真租户/PG/手机未验证。
