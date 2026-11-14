# 票 05 Standards 轴原始报告

范围：固定 `10b3e8b4045390265d63dee74e3389473ec51016...6611b3c4b0e2ea91478c9cd227fe31ad102b545e`，仅 Standards 轴。原始计数：P1=1，P2=3，P3=2。

## 发现

- **P1** `frontend/e2e/substitution-approval.spec.ts:58`。触发：任一用例进入 `submitCover`；它把 `Page` 传给要求 `Locator` 的共享 `pickOption`（`frontend/e2e/helpers.ts:332-337`），调用 `scope.page()` 即 TypeError，两条审批 Playwright 均未执行。依据 `t05-wire-contract.md:30` 要求真实 Playwright 覆盖，`CODING_STANDARDS.md:37` 要求公共交互集中在 `helpers.ts`。最小修正：传入表单 `Locator`，并删除 `46-53` 的私有下拉交互，复用/补强共享 helper。

- **P2** `backend/src/main/java/com/paiban/substitution/service/SubstitutionMapper.java:74-81`。触发：某实例已有 `EFFECTIVE` 行，老师再提交第二次调整，或新请求被驳回/撤回/作废；此时 `finalTeacherId` 为空，mapper 却查询旧生效行并回填 `substituteTeacherId`。后果：待批 DTO 把旧代课人冒充本次期望人，页面可显示错误的“实际”人。依据 `t05-wire-contract.md:12`：待批时该字段必须是期望人。最小修正：仅旧 `EFFECTIVE` 请求才回退查生效行，其余状态回退 `expectedSubstituteTeacherId`。

- **P2** `backend/src/main/java/com/paiban/substitution/service/SubstitutionApprovalService.java:189-199,223-232`。触发：开课时刻后直接调用 reject/withdraw；代码只检查 `SUBMITTED`，会落 `REJECTED/WITHDRAWN`，而应落 `EXPIRED`。依据 ADR0037:22、`t05-transaction-plan.md:8`、`spec.md:137`。最小修正：锁后刷新后复用截止判定；过期先提交 EXPIRED、历史和审计，再返回 409。

- **P2** `frontend/src/components/substitution/types.ts:152-160`。触发：展示旧/系统撤销的 `CANCELLED`；当前文案是“已取消”，并固化在 `types.spec.ts`。ADR0037:29-31 明确应展示“系统撤销”，以免冒充申请人撤回。最小修正：改映射并同步测试。

- **P3** `backend/src/main/java/com/paiban/substitution/service/SubstitutionApprovalService.java:113-115,191-193,224-229`。触发：审批账号的权限或教师绑定在预读 `AppUser` 后、取得锁前被并发修改；approve/reject 是“先查权限后锁”，withdraw 也是“先取用户、锁后才验归属”，等待锁期间会沿用旧用户快照。依据 `t05-transaction-plan.md:9` 要求持锁刷新后检查权限。最小修正：先完成学期/申请锁，再重新读取用户并判断权限或归属。

- **P3** `frontend/src/components/substitution/SubstitutionMineTable.vue:98-104`。触发：期望人与最终批准人不同但姓名相同；`changedTeacher` 比较姓名，因此不显示“期望/实际”，虽两个 teacher id 不同。依据 ADR0037:37-38 要求两值并存且向用户展示。最小修正：按 id 判断。

## Fowler 基线

Mysterious Name、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man、Refused Bequest 均 0。

Duplicated Code：判断项/possible smell，`substitution-approval.spec.ts:46-53` 的 `async function pickFirstOption(select: Locator) { await select.click(); ... }` 复制下拉交互；同时违反明确标准 `CODING_STANDARDS.md:37`，已在 P1 计为硬问题，不另计 smell 优先级，也不扩成重构建议。

## 限界

已实读固定 diff、ADR0002/0008/0009/0030/0037、spec/issues/wire/transaction/调用面交付文档及相关前后端源码、修改测试与 E2E。未测试、构建、联网或改其他文件；未验证真实 PostgreSQL/企业钉钉。报告不包含 main 后续整改。
