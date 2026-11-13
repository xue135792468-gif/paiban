# 第一批小 delta：Spec 轴（be48fc0…d7ee6ff）

审源 `d7ee6ffca6456296f5daf654809d6c8516b6f3ea`（只读 WT，rev-parse 全 SHA 已核、工作树干净）；diff `be48fc0...d7ee6ff`，9 个源文件 +25/−42，仅去重与常量复用。未跑测试/构建/网络，未改源。

- 结论：新增规格缺失 0、越界 0、行为变更 0；Spec 轴维持 0 confirmed，原 be48 计数不动。
- 复用：`trackBrowserErrors`、`fillDateInput` 集中于 `frontend/e2e/helpers.ts:74-83`；4 份错误收集器（account-audit、dingtalk-sync、notification-delivery-logs、substitution-approval）与 3 处日期动作（dingtalk-acceptance、substitution-approval、substitution）改为调用，断言、日期值与收件人断言仍在各 spec 内。
- 常量：`SettingsView.vue:204,237` 改用 `INTEGRATION_MANAGE_PERMISSION`，等价于原字面量 `integration:manage`，权限判定结果不变。
- 删除：`NotificationService.java` 去掉 `substitutionPlanFailed`/`substitutionVerifyAlert` 两个空方法；全仓 grep（含测试）0 调用，`notify`（55 行）与 `deliverAfterCommit`/`NotificationTemplates.isActive`（147-160）未动 → 模板、收件人、身份与代课资格语义不变。
- 未覆盖：主控 P2-QC 手机导航 M1（默认本人课表落点、≤900 隐藏 aside、header 无菜单、TimetableView 无申请入口）本快照未做，仍开放；be48 的 0 不覆盖 M1，原始报告保留不改写。
- 边界：E2E 仅改 helper 调用且未运行，不据此称 browser 转绿或 full 通过；M1 修复后的新 UI 4 文件 delta 待下一批复核。
