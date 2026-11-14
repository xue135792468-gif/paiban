# 05 双轴复查与整改记录（单票放行，整特性全量待执行）

2026-11-12。固定比较：`git diff 10b3e8b4045390265d63dee74e3389473ec51016...6611b3c4b0e2ea91478c9cd227fe31ad102b545e`。两端均已解析，差异非空；完整施工与提交记录见交付文档。原始审源的物理快照为工作树 `dingtalk-t05-review-snapshot`，后续整改另行增量复核。

## Standards

原始报告：[t05-standards-review-raw.md](t05-standards-review-raw.md)。本轴最终原始计数 P1=1、P2=3、P3=2（6 项），最高 P1。

范围：固定 `10b3e8b4045390265d63dee74e3389473ec51016...6611b3c4b0e2ea91478c9cd227fe31ad102b545e`，仅 Standards 轴。原始计数：P1=1，P2=3，P3=2。

### 发现

- **P1** `frontend/e2e/substitution-approval.spec.ts:58`。触发：任一用例进入 `submitCover`；它把 `Page` 传给要求 `Locator` 的共享 `pickOption`（`frontend/e2e/helpers.ts:332-337`），调用 `scope.page()` 即 TypeError，两条审批 Playwright 均未执行。依据 `t05-wire-contract.md:30` 要求真实 Playwright 覆盖，`CODING_STANDARDS.md:37` 要求公共交互集中在 `helpers.ts`。最小修正：传入表单 `Locator`，并删除 `46-53` 的私有下拉交互，复用/补强共享 helper。

- **P2** `backend/src/main/java/com/paiban/substitution/service/SubstitutionMapper.java:74-81`。触发：某实例已有 `EFFECTIVE` 行，老师再提交第二次调整，或新请求被驳回/撤回/作废；此时 `finalTeacherId` 为空，mapper 却查询旧生效行并回填 `substituteTeacherId`。后果：待批 DTO 把旧代课人冒充本次期望人，页面可显示错误的“实际”人。依据 `t05-wire-contract.md:12`：待批时该字段必须是期望人。最小修正：仅旧 `EFFECTIVE` 请求才回退查生效行，其余状态回退 `expectedSubstituteTeacherId`。

- **P2** `backend/src/main/java/com/paiban/substitution/service/SubstitutionApprovalService.java:189-199,223-232`。触发：开课时刻后直接调用 reject/withdraw；代码只检查 `SUBMITTED`，会落 `REJECTED/WITHDRAWN`，而应落 `EXPIRED`。依据 ADR0037:22、`t05-transaction-plan.md:8`、`spec.md:137`。最小修正：锁后刷新后复用截止判定；过期先提交 EXPIRED、历史和审计，再返回 409。

- **P2** `frontend/src/components/substitution/types.ts:152-160`。触发：展示旧/系统撤销的 `CANCELLED`；当前文案是“已取消”，并固化在 `types.spec.ts`。ADR0037:29-31 明确应展示“系统撤销”，以免冒充申请人撤回。最小修正：改映射并同步测试。

- **P3** `backend/src/main/java/com/paiban/substitution/service/SubstitutionApprovalService.java:113-115,191-193,224-229`。触发：审批账号的权限或教师绑定在预读 `AppUser` 后、取得锁前被并发修改；approve/reject 是“先查权限后锁”，withdraw 也是“先取用户、锁后才验归属”，等待锁期间会沿用旧用户快照。依据 `t05-transaction-plan.md:9` 要求持锁刷新后检查权限。最小修正：先完成学期/申请锁，再重新读取用户并判断权限或归属。

- **P3** `frontend/src/components/substitution/SubstitutionMineTable.vue:98-104`。触发：期望人与最终批准人不同但姓名相同；`changedTeacher` 比较姓名，因此不显示“期望/实际”，虽两个 teacher id 不同。依据 ADR0037:37-38 要求两值并存且向用户展示。最小修正：按 id 判断。

### Fowler 基线

Mysterious Name、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man、Refused Bequest 均 0。

Duplicated Code：判断项/possible smell，`substitution-approval.spec.ts:46-53` 的 `async function pickFirstOption(select: Locator) { await select.click(); ... }` 复制下拉交互；同时违反明确标准 `CODING_STANDARDS.md:37`，已在 P1 计为硬问题，不另计 smell 优先级，也不扩成重构建议。

### 限界

已实读固定 diff、ADR0002/0008/0009/0030/0037、spec/issues/wire/transaction/调用面交付文档及相关前后端源码、修改测试与 E2E。未测试、构建、联网或改其他文件；未验证真实 PostgreSQL/企业钉钉。报告不包含 main 后续整改。

## Spec

原始报告：[t05-spec-review-raw.md](t05-spec-review-raw.md)。本轴原始计数 P1=0、P2=5、P3=1（6 项），最高 P2。

审源：`git diff 10b3e8b4045390265d63dee74e3389473ec51016...6611b3c4b0e2ea91478c9cd227fe31ad102b545e`。P1=0；未要求行为扩张=0。

- **P2** `backend/src/main/java/com/paiban/substitution/service/SubstitutionMapper.java:72-81`：同一课节/日期已有 EFFECTIVE 代课，再提交第二张 SUBMITTED 申请且尚未改人时，mapper 会查旧生效行，把旧接课人填入 `substituteTeacherId/Name`。触发后，`SubstitutionMineTable.vue:32,98-104` 会把新申请显示成“期望新人 / 实际旧人”，违约 `t05-wire-contract.md:12` 的“待批时 substituteTeacherId/Name 是期望人”。最小修正：SUBMITTED 直接返回 `expectedSubstituteTeacherId`；仅对旧 EFFECTIVE 行保留兼容回查，并加重复代课待批回归。
- **P2** `backend/src/main/java/com/paiban/substitution/service/SubstitutionApprovalService.java:189-203,223-235`：开课后若先调用 reject 或 withdraw，二者只检查 SUBMITTED，分别落 REJECTED/WITHDRAWN，绕过 `409` 的惰性作废。依据 `spec.md:131,137`、`t05-wire-contract.md:19` 与 ADR-0037:22。最小修正：锁后 refresh 同时按真实截止检查；已过期则先提交 EXPIRED，再于事务外返回 409，并补 reject/withdraw 课后用例。
- **P2** `frontend/e2e/substitution-approval.spec.ts:55-59` 将 `Page` 传给声明为 `Locator` 的 `pickOption`（`helpers.ts:332-337` 调用 `scope.page()`），实际触发 `TypeError: scope.page is not a function`，两例均在审批 UI 前失败，未履行 `spec.md:185` 的审批 E2E 义务。最小修正：传 `page.locator('body')` 或为 helper 明确支持 Page，重跑固定审源。
- **P2** `frontend/e2e/substitution-approval.spec.ts:125-132`：同一“期望教师”同时存在于已撤回行和重提后已生效行，`getByText(...).toBeVisible()` 命中两行而严格模式失败。最小修正：按 `生效` 或第二次提交的 reason 限定唯一行后再断言。
- **P2** `frontend/src/components/substitution/types.ts:152-159` 把 `CANCELLED` 显示为“已取消”；发布撤回导致的系统取消应为“系统撤销”，否则与申请人撤回混淆。依据 `t05-wire-contract.md:10`、ADR-0037:28-31。最小修正：改标签并同步组件测试。
- **P3** `frontend/src/components/substitution/SubstitutionMineTable.vue:98-105` 用姓名判断是否改人；两位教师同名时，期望人与实际人不同也不会并列展示。依据 ADR-0037:37-38。最小修正：按 teacher id 判断，并让同名校验有可区分的显示。

实读：`spec.md`、05 票、`t05-wire-contract.md`、`t05-transaction-plan.md`、`t05-caller-regression-map.md`、ADR-0037；661 固定差异及上述后端/DTO/前端/迁移/测试/E2E，以及允许的 UI 交付文档。未读另轴报告，未运行测试、构建、网络或服务；主控回传的 E2E 失败作为客观证据采用。只评 05；06/07/08、03 策略、真实 PG/企业钉钉边界不判缺陷。

## 主控判定与施工

两轴计数分别保留，整改按代码位置交由同一 implementer（Dirac）实施。

| 位置 | 主控复读结论 | 整改要求与当前证据 |
|---|---|---|
| E2E 公共交互 | 确认。`pickOption` 要求 Locator，新调用传 Page；新私有首项选择与旧本票 E2E 重复 | 188ae98 已改成真实表单项 Locator；共享首项选择收拢及最终浏览器绿仍待完成。保留原 P1，不把测试运行失败写成产品审批接口已坏 |
| `SubstitutionMapper.toDto` | 确认。final 为空时查询同实例旧 EFFECTIVE 行，违反待批字段契约 | 未批准状态使用本次期望人；旧 EFFECTIVE 兼容回查限定在其适用状态；补重复申请公开 HTTP 回归 |
| reject / withdraw 截止 | 确认。锁后仅检查 SUBMITTED，课后可被记成拒绝/撤回 | 授权及 owner 校验后判截止；EXPIRED 同事务提交并在事务外 409；独立 GET、history、audit、零效果与幂等回归 |
| 系统撤销标签 | 确认。新 ADR-0037 明确写了“展示为系统撤销” | 改共享标签与既有测试，保留 CANCELLED 系统取消语义 |
| 同名不同教师 | 确认。MineTable 使用姓名比较，两个不同档案可被当作同一人 | 按 ID 判断，并在同名场景提供可区分的档案编号展示；公开 DOM 回归；不扩教师接口 |
| 锁前用户权限/绑定快照 | 已确认并独立复现。角色变更及新 auth/me 已显示无审批权，同一 EntityManager 仍持有旧权限，approve 没有拒绝 | 独立 `SubstitutionApprovalAuthorizationSnapshotTest` 真实 1 失败、0 错误、退出 1；失败点为应拒绝却没有 ApiException，不是前置或编译问题。同一 worker 持锁后刷新当前 AppUser 再授权，不新增账号锁序；测试由主控维护，修复后必须转绿 |
| E2E 行范围 | 确认。撤回与重新提交的两行都有同一期望姓名 | 第二次申请的独特原因限定唯一行，再在该行断已生效、期望/实际及审批意见；保持撤回和重提覆盖 |

主控另实际查看了无学期截图：Teacher 原提示要求自行创建学期，不符合其操作权限。188ae98 已将文案明确为教务管理员完成，标题、testid、权限和行为保持。此为额外视觉 QA，单独记录。

## 真实浏览器证据

- 首轮固定 6611b3c 源：6 条中 **4 通过、2 失败，退出 1**。两条新审批用例均在 Page/Locator 参数处失败，尚未进入审批 UI。原始日志、trace、错误上下文保留在 `logs/t05-browser-run1/` 与 `.runtime/t05-browser-run1/test-results/`。
- 第二轮固定 6c5e43e 源（含 188ae98）：**5 通过、1 失败，退出 1**。改人批准与驳回均实际执行；唯一失败是 Teacher 结果页的期望姓名命中撤回及重提两行，仍需收紧申请行范围。改人批准截图已实际 `view_image` 核验，教务身份及第三人选择正确。
- 两轮均保存实际命令、退出码、前后源码路径和哈希；`SOURCE_IDENTICAL=1`。期间 HEAD 的新增仅为已核验的交付文档合并；实际后端/前端源码和 E2E 哈希前后一致。两轮结束后隔离端口 28405/25405 与本次 Java 服务均已退出。
- 第二轮日志、截图、trace 分别位于 `logs/t05-browser-run2/`、`evidence/t05-browser-run2/`、`.runtime/t05-browser-run2/test-results/`。未覆盖或删除首轮失败。

## 放行状态

**2026-11-13 单票放行。** 两路最终增量见 [Standards](t05-standards-delta-raw.md) 与 [Spec](t05-spec-delta-raw.md)，固定审源 `6611b3c...849a385`。原 Standards 六项及 Spec 六项均关闭，原始计数和优先级保持。Spec 增量 P1/P2/P3 均为 0；Standards 增量硬违反均为 0，另有一个 P3 Duplicated Code 判断项。

主控对该判断项作明确不采纳决定：reject/withdraw 的三行过期分支保持各动作的显式事务流程；真正的截止规则已共用 `overdue`、状态/历史/审计写入已共用 `expire`。现阶段进一步抽取不会消除独立业务规则，当前两条公开 HTTP 回归已守住一致性。此为已裁定的启发式建议，并非延期的已确认缺陷，不把原始判断项改写为零。后续规则确实扩展时再评估抽取。

源码候选、后端/前端定向与主控浏览器证据已核对。允许进入 06；05 暂记 implemented，原八票最后一次全量 verify 通过后才统一置 done。以下各阶段“未放行”说明属于历史快照。

2026-11-13 程序验收更新：整改源码固定 `849a3856fd125391afe72679d26b1ea221e02b8c`，普通合入集成为 `3cb51de399bea852ad6a0c78954fc534fb5f144e`，源码树一致。后端四类组合 23 条通过、退出 0；同名选择追加后的前端四文件 17 条通过、lint/类型检查退出 0。主控第三轮受影响浏览器四条全部通过、退出 0，前后源码哈希相同；隔离端口与本次 Java 均已结束。未重复执行源码未变且第二轮已通过的两条免登例。

第三轮原始记录在 `logs/t05-browser-run3/`、`.runtime/t05-browser-run3/test-results/` 与 `evidence/t05-browser-run3/`。主控实际查看了老师结果页截图，确认老师身份及管理菜单边界；由于主容器独立滚动，截图未覆盖下方结果行，期望/实际人和状态的证据来自该行公开 DOM 断言，不把截图冒称完整结果行视觉证据。改人批准对话框截图已在第二轮实际核验。

最终双轴增量正在只读快照 `dingtalk-t05-delta-snapshot` 审核 `6611b3c...849a385`；待两轴交付并完成主控判定后才更新本票放行状态，下面保留之前的整改门禁说明。

**未放行。** 同一 worker 正在补确认问题的行为红绿、组件与三类后端定向回归；之后由主控运行受影响审批 E2E，并对整改做独立双轴增量复核。06/07/08 仍未施工；本记录不代替整特性最后一次完整门禁。

当前最终原始发现分别为 Standards 6 项（最高 P1）、Spec 6 项（最高 P2）；整改与复核尚未完成。
