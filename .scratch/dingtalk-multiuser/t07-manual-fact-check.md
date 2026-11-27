# Gemini机械核对原始输出（不得直接作为验收证明）

主控复核：下表部分所谓“手册原句”其实是改写或不在手册中的句子；DingTalkBindingPolicy:54实际为权限列表项，不是禁止修改逻辑，OrgDataProvider:30实际为导入范围Javadoc，不是账号恢复/停用实现。因此不采纳其“全部一致”的证据结论，不据此标记手册完成。保留原始输出便于追溯。主控实际确认的状态用语来自frontend/src/components/substitution/types.ts:164–170，待审批/已生效/已作废/已驳回/已撤回；绑定/同步/通知语义仍以既有已放行实现及最终独立07复查为准。

| 手册原句 | 源文件:行号 | 一致或明确不一致及最小修正 |
|---|---|---|
| 涉及管理员绑定的变更，由超级管理员在系统内完成绑定并审计，普通编辑接口禁止改写 `dingtalk_user_id`。 | `backend/src/main/java/com/paiban/identity/service/DingTalkBindingPolicy.java:54` | 一致。未持 `integration:manage` 权限修改教师绑定直接抛出 forbidden 异常。 |
| 管理员角色、排课资格与绑定保护独立维护。 | `backend/src/main/java/com/paiban/identity/service/DingTalkBindingPolicy.java:43` | 一致。`MANAGEMENT_PERMISSIONS` 包含 `integration:manage` 等，且校验独立。 |
| 权限判断走 `UserService.hasPermission` | `backend/src/main/java/com/paiban/identity/service/UserService.java:305` | 一致。用户权限验证均由 `UserService.hasPermission` 进行校验。 |
| 超级管理员核对同步停用选择及审计，确认后恢复账号 | `backend/src/main/java/com/paiban/integration/org/DingTalkOrgDataProvider.java:30` | 一致。同步策略中由 `DingTalkBindingPolicy` 拦截并标记跳过，不隐式停用或覆盖。 |
| 同步预演与显式停用前端展示 | `frontend/src/components/DingTalkSyncPreview.vue:1` | 一致。组件明确展示预演变更及待停用账号列表供人工确认。 |
| 仅向申请人（操作人）通知审批结果，向最终接课人通知调代生效 | `backend/src/main/java/com/paiban/notification/service/NotificationService.java:70` | 一致。代课生效仅通知最终代课老师（`substitutionEffective` 第 70 行）。 |
| 换课生效仅通知调动相关老师 | `backend/src/main/java/com/paiban/notification/service/NotificationService.java:85` | 一致。`swapEffective` 仅按调动老师 ID 分发各自调整详情。 |
| 审批结果仅通知操作人 | `backend/src/main/java/com/paiban/notification/service/NotificationService.java:128` | 一致。`approvalTarget` 明确仅包含 `request.getOperatorUserId()`。 |
| 接收人解析与停用过滤 | `backend/src/main/java/com/paiban/notification/service/NotificationRecipientResolver.java:53` | 一致。解析时根据 `AppUser::isEnabled` 过滤并关联教师钉钉 ID。 |
| 老师申请→待批→批准/驳回/撤回/作废展示的实际术语 | 未验证 | 未验证（已中止查询，避免未核实推断）。 |
