# 票 05 Spec 轴增量复查

审源：`git diff 6611b3c4b0e2ea91478c9cd227fe31ad102b545e...849a3856fd125391afe72679d26b1ea221e02b8c`。物理快照 HEAD=`849a3856fd125391afe72679d26b1ea221e02b8c`。

原 6 项仍以原优先级、原计数保留：P1=0、P2=5、P3=1；本次按增量核实，六项均已关闭。

- **P2 待批候选人：关闭。** `SubstitutionMapper.java:74-84` 仅在请求自身为 EFFECTIVE 时兼容回查生效行；SUBMITTED 等未批准状态返回本次 `expectedSubstituteTeacherId`。重复生效后代课待批回归见 `SubstitutionApprovalIntegrationTest.java:216`，符合 `t05-wire-contract.md:12`。
- **P2 课后驳回/撤回：关闭。** `SubstitutionApprovalService.java:194-201,233-247` 在锁后刷新、账号/归属和 SUBMITTED 校验后检查真实截止；过期先提交 EXPIRED、历史和审计，再由外层返回 409。对应 HTTP 回归见同测试 `:294,:358`，符合 `spec.md:131,137`、`t05-transaction-plan.md:15-19`。
- **P2 E2E Page/Locator：关闭。** `substitution-approval.spec.ts:49-56` 现分别传表单 Locator 与共享 `pickFirstVisibleOption`/`pickOption`；`helpers.ts:369` 集中首项下拉交互。run3 的 4 条受影响 E2E 全过、EXIT 0、源码哈希一致。
- **P2 E2E 行歧义：关闭。** `substitution-approval.spec.ts:128-135` 先用第二次提交的独有原因定位唯一 `.el-table__row`，再检查生效状态、期望/实际人和意见；run3 首例真实通过。
- **P2 系统撤销标签：关闭。** `types.ts:166` 将 CANCELLED 映射为“系统撤销”，组件测试已同步；符合 `t05-wire-contract.md:10`、ADR-0037:28-31。
- **P3 同名改人展示：关闭。** `SubstitutionMineTable.vue:109-119` 按教师 ID 判断改人，同名异 ID 时补档案编号；选人 options 在 `SubstitutionRequestForm.vue:49-50`、`SubstitutionApprovalPanel.vue:47-48` 仅用 `teacherOptionLabel` 改 label，value 仍为 `teacher.id`。唯一姓名不变，未扩 API/DTO。

增量新增发现：**P1=0、P2=0、P3=0**。未发现 05 需求缺失、错误实现或未授权扩张；`requireCurrentUser`（`SubstitutionApprovalService.java:337-341`）是 `t05-transaction-plan.md:9` 要求的权限快照修复，不是新产品能力。06/07/08 未纳入。

实读：原/增量 diff、Spec 原文与 05 票、wire/transaction 契约、ADR-0037、`t05-review.md` 的 Spec 处置、相关源码/测试/E2E、23 tests 后端日志、17 tests 前端交付记录、run3 日志。另轴 raw 未读；未运行测试、构建、服务、网络，未修改源码、测试或原报告。真实 PostgreSQL/企业钉钉边界不作本票缺陷。
