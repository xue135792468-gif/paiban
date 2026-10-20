# 10: 调代申请与审批（双模式可切换）

**What to build:** 实现调代申请完整流程：教师提交调代申请（哪天哪节课请假、原因、期望代课教师）、状态机支持审批/直接生效两种模式（由系统设置切换）、教务审批界面（批准/驳回）、状态变更历史。完成后，teacher 提交"周三第 3 节请假"；审批模式下 admin 批准后生效，直接生效模式下立即生效；状态切换只影响新申请。

**Blocked by:** 09

**Status:** ready-for-agent

- [ ] substitution_request 表（申请人、原课节、原因、期望代课教师、状态、审批人、审批时间、审批意见）
- [ ] 状态机实现（ADR-0009）：SUBMITTED → PENDING_APPROVAL → APPROVED/REJECTED → EFFECTIVE，直接生效模式跳过 PENDING_APPROVAL
- [ ] 配置项 `paiban.substitution.approval-mode` 控制模式，默认 approval
- [ ] 已在流程中的申请按提交时的模式走完（配置切换不影响存量）
- [ ] 教师 UI：提交调代申请（选择日期与课节，自动加载那天的课程，选择期望代课教师，填原因）
- [ ] 教师 UI：我的调代申请列表（含状态）
- [ ] 教务 UI：待审批列表、批准/驳回操作、审批意见填写
- [ ] substitution_history 表记录所有状态变更（谁、何时、从什么状态到什么状态、备注）
- [ ] REST Assured 集成测试：两种模式分别走通完整流程；配置切换只影响新申请
- [ ] 权限校验：只有 admin 能审批，teacher 只能提交自己的
