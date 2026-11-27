# 票 05 独立 Spec 轴原始发现

审源：`git diff 10b3e8b4045390265d63dee74e3389473ec51016...6611b3c4b0e2ea91478c9cd227fe31ad102b545e`。P1=0；未要求行为扩张=0。

- **P2** `backend/src/main/java/com/paiban/substitution/service/SubstitutionMapper.java:72-81`：同一课节/日期已有 EFFECTIVE 代课，再提交第二张 SUBMITTED 申请且尚未改人时，mapper 会查旧生效行，把旧接课人填入 `substituteTeacherId/Name`。触发后，`SubstitutionMineTable.vue:32,98-104` 会把新申请显示成“期望新人 / 实际旧人”，违约 `t05-wire-contract.md:12` 的“待批时 substituteTeacherId/Name 是期望人”。最小修正：SUBMITTED 直接返回 `expectedSubstituteTeacherId`；仅对旧 EFFECTIVE 行保留兼容回查，并加重复代课待批回归。
- **P2** `backend/src/main/java/com/paiban/substitution/service/SubstitutionApprovalService.java:189-203,223-235`：开课后若先调用 reject 或 withdraw，二者只检查 SUBMITTED，分别落 REJECTED/WITHDRAWN，绕过 `409` 的惰性作废。依据 `spec.md:131,137`、`t05-wire-contract.md:19` 与 ADR-0037:22。最小修正：锁后 refresh 同时按真实截止检查；已过期则先提交 EXPIRED，再于事务外返回 409，并补 reject/withdraw 课后用例。
- **P2** `frontend/e2e/substitution-approval.spec.ts:55-59` 将 `Page` 传给声明为 `Locator` 的 `pickOption`（`helpers.ts:332-337` 调用 `scope.page()`），实际触发 `TypeError: scope.page is not a function`，两例均在审批 UI 前失败，未履行 `spec.md:185` 的审批 E2E 义务。最小修正：传 `page.locator('body')` 或为 helper 明确支持 Page，重跑固定审源。
- **P2** `frontend/e2e/substitution-approval.spec.ts:125-132`：同一“期望教师”同时存在于已撤回行和重提后已生效行，`getByText(...).toBeVisible()` 命中两行而严格模式失败。最小修正：按 `生效` 或第二次提交的 reason 限定唯一行后再断言。
- **P2** `frontend/src/components/substitution/types.ts:152-159` 把 `CANCELLED` 显示为“已取消”；发布撤回导致的系统取消应为“系统撤销”，否则与申请人撤回混淆。依据 `t05-wire-contract.md:10`、ADR-0037:28-31。最小修正：改标签并同步组件测试。
- **P3** `frontend/src/components/substitution/SubstitutionMineTable.vue:98-105` 用姓名判断是否改人；两位教师同名时，期望人与实际人不同也不会并列展示。依据 ADR-0037:37-38。最小修正：按 teacher id 判断，并让同名校验有可区分的显示。

实读：`spec.md`、05 票、`t05-wire-contract.md`、`t05-transaction-plan.md`、`t05-caller-regression-map.md`、ADR-0037；661 固定差异及上述后端/DTO/前端/迁移/测试/E2E，以及允许的 UI 交付文档。未读另轴报告，未运行测试、构建、网络或服务；主控回传的 E2E 失败作为客观证据采用。只评 05；06/07/08、03 策略、真实 PG/企业钉钉边界不判缺陷。
