# 03: 管理员指定代课：预览、代发与亲自确认

**What to build:** 提供管理员可独立使用的代课流程：管理员搜索教师或从课表选择实际授课，选择启用代课人，查看调整前后对照和完整变化清单，再选择提交审批或亲自确认生效。管理员不需要绑定教师身份；操作者、代发对象和批准人均按真实账号记录。仅有查看权限不可写；提交审批始终待批，直接确认须有审批权限。跨科提示、硬冲突、开课截止、快照失效与幂等均在流程中明确处理。未转换旧格式实例只读并提示状态。

**Blocked by:** 01:按实际授课日期查看和导出一次性调代

**Status:** done (2026-10-29, 集成分支 `feature/admin-substitution-workbench`；门禁后端 1012 / 前端 417 / Playwright 117 全绿)

- [ ] 无教师绑定且具备相应权限的管理员可经 HTTP 完成代人预览、提交审批和亲自确认；课表只在确认后改变。
- [ ] 提交审批进入待审批队列，待批期间旧安排继续生效且不发已生效通知。
- [ ] 仅有全校/调代查看权限者不能代发或批准；教师不能代其他教师提交；班主任和教师的数据范围不扩大。
- [ ] 缺少全校课表查看权限者不能搜索或查看其他教师、全校课表及超出本人范围的预览数据；接口与界面均遵守相同范围。
- [ ] 管理界面只提供启用教师且排除当前教师；同科优先，跨科有提示但可继续选择。
- [ ] 教师、班级、教室及启用约束冲突由服务端拒绝并说明；界面不能绕过硬错误。
- [ ] 预览左右面板、变更清单、来源/目标格高亮及影响教师/日期来自同一方案；基线参考不作为差异起点。
- [ ] 仅预览阶段的取消、无解、过期或校验失败不创建申请、生效安排或通知；已提交申请在审批失败时保留原单和原安排。过期/快照变化要求重新预览，不自动确认。
- [ ] 确认时复核权限、当前版本和开课截止；在截止前可确认，恰好开课或之后拒绝。
- [ ] 同一确认凭证重试只返回一次结果；旧快照或规则变化产生明确冲突，保留用户意图供重预览。
- [ ] Playwright 覆盖选教师、指定代课、左右对照、清单、提交审批及有权管理员亲自确认。
- [ ] 对未转换旧格式实例，管理写入被阻止并说明转换状态，不静默沿用新语义。
- [ ] 申请与审计分别保存真实操作账号、代发对象、基线原教师、调整前后教师及审批人；管理员绑定的教师身份不替代这些信息。

## 规格依据

`spec.md`「范围、权限与入口」「对照、清单与预览生命周期」「提交审批、亲自批准与并发」「课前再次调整与截止」。

## 交付记录（2026-10-29，branch feature/t03-admin-substitute-flow）

### 逐条验收证据

1. **无教师绑定管理员经 HTTP 完成代人预览、提交审批和亲自确认；课表只在确认后改变**
   `AdminSubstitutionIntegrationTest.adminDispatchSubmitApprovalKeepsOldPlanUntilApproved`（内置 admin 账号无 teacherId）与 `adminPersonalConfirmRunsTheApprovalPath`：candidates→preview→submit-approval/confirm 全链 HTTP；预览与提交审批阶段 `substitutionRows.count()==0`、班级课表 `substituted=false`，确认后 `teacherId=代课人`。
2. **提交审批进待审批队列，待批期间旧安排继续生效且不发已生效通知**
   同上用例：提交后状态 `PENDING_APPROVAL` 且出现在 `?status=PENDING_APPROVAL` 列表；`previewHasZeroSideEffects` 断言原教师 unread-count=0（生效通知只在批准后由 approve 路径发出）。
3. **仅查看权限者不能代发或批准；教师不能代其他教师提交；班主任/教师数据范围不扩大**
   `viewOnlyUserCannotDispatchOrApprove`（自建「仅 substitution:view」角色：candidates/preview 403、旧入口代人提交 403、`GET /substitutions` 仍 200）；`teacherCannotDispatchOthersOrUseWorkbench`（工作台 403、代人提交 403、本人提交 200）；`headTeacherScopeIsNotExpanded`（工作台 403、记录接口仍强制本班 classId 400）。收紧点：`SubstitutionRequestService.create`、`SubstitutionPlanService.swapTargets` 的管理者判定改为 `hasAllPermissions(TIMETABLE_VIEW, SUBSTITUTION_SUBMIT, SUBSTITUTION_APPROVE)`。
4. **缺全校课表查看权限者不能搜索/看他人/全校课表/超范围预览数据**
   同上两个用例（教师与班主任均 403），候选接口自身要求管理组合权限；界面侧工作台入口按同组合显隐（`SubstitutionWorkbenchView.isManager`，教师打开显示引导提示，见 e2e 用例 3）。
5. **管理界面只提供启用教师且排除当前教师；同科优先，跨科有提示**
   `candidatesPreferSameSubjectAndExposeConflictReasons`：候选不含当前教师、`busyReason` 可见、`sameSubject`/`crossSubject` 标记正确且同科排序在前；服务端按 `Teacher.isActive` 过滤（`buildCandidates`）。
6. **教师、班级、教室及启用约束冲突由服务端拒绝并说明**
   `hardConflictIsRejectedByServerOnConfirm`：同时段冲突教师 preview 返回 issues 提示，confirm 409「代课教师在该时段已有课」且零写入。校验复用 `PlanVerifier`（独立校验器）+ approve 的 busy 检查。
7. **预览左右面板、变更清单、高亮与影响教师/日期来自同一方案；基线参考不作差异起点**
   preview 响应的 left/right/changes 均由同一 `PlanSnapshot.scheduleOn(lessonDate)` 投影生成（`gridFor`/`changeItem`）；前端 `PreviewCompare` 渲染左「调整前（最新）」右「方案后」+ 变化清单 + 只读基线折叠区（`view=baseline`，标注「学期初原始」）。e2e 用例 1 断言左右面板、清单与离开/接替教师。
8. **仅预览阶段取消/无解/过期/校验失败零申请、零生效、零通知；过期/快照变化要求重新预览**
   `previewHasZeroSideEffects`（申请/生效/审计表全 0，通知 0）；`snapshotChangeForcesRepreviewInsteadOfConfirming`（快照哈希变化后 confirm 409「请重新预览」，重预览后可确认）；预览失败/冲突时凭证不消费（`consumeVoucher` 只在成功后 consume）。
9. **确认时复核权限、当前版本和开课截止；截止前可确认，恰好开课或之后拒绝**
   `AdminSubstitutionDeadlineIntegrationTest` 5 条（固定时钟 Asia/Shanghai）：07:59:59 可确认；预览后时钟走到 08:00:00/08:00:01 confirm 409 且零写入；恰好开课不能创建预览；提交审批同样被截止拦截。确认流程在 `lockSemester()` 之后重读 `Clock.instant()` 再复核。
10. **同一确认凭证重试只返回一次结果；旧快照/规则变化明确冲突**
    `idempotentConfirmRetryReturnsFirstResultAndRejectsParamChange`：同凭证同参数重试 200 + `replayed=true`，生效行/历史不再增长；不同 comment 409。凭证语义纯单测 `SubstitutionPreviewTest`（重放不受 30 分钟过期限制，过期只约束消费未用凭证）。
11. **Playwright 覆盖选教师、指定代课、左右对照、清单、提交审批及亲自确认**
    `frontend/e2e/admin-substitute.spec.ts` 3 条全过：亲自确认全链（含对照/清单断言）、提交审批（待批可见 + 旧安排生效）、教师账号被引导回自助入口。种子 `seedWorkbenchScenario` 用未来学期（服务端截止用权威时钟，历史日期会被正确拒绝）。
12. **未转换旧格式实例管理写入被阻止并说明转换状态**
    `legacyFormatInstanceIsReadOnlyForManagementWrites`：数据级检查（`lesson_date IS NULL` 的 EFFECTIVE 行且 `effective_date <= lessonDate`，未 import 票 02 类），preview 409 提示「旧格式…需先完成历史转换」，candidates 返回 `legacyLocked=true`。
13. **申请与审计分别保存真实操作账号、代发对象、基线原教师、调整前后教师及审批人；管理员绑定教师身份不顶替**
    `adminDispatchSubmitApprovalKeepsOldPlanUntilApproved`（operatorUserId/requesterTeacherId/originalTeacherId/previousTeacherId/finalTeacherId/approverId 全断言 + `SUBSTITUTION_ADMIN_DISPATCHED` 审计行）；`teacherBoundAdminDoesNotImpersonateAnyone`（绑定教师的管理员代发，requester=基线教师≠绑定教师）。迁移 V40 为 `substitution_request` 新增 operator_user_id/previous_teacher_id/final_teacher_id。

### 新机制与 API 面（后续票复用）

- `GET /api/substitution-workbench/candidates?entryId=&lessonDate=` — 启用教师候选（排除当前、同科优先、busy 原因、旧格式/待审批单锁定状态）。
- `POST /api/substitution-workbench/previews` — 创建预览凭证（绑定操作者/实例/快照哈希/代课人/原因，返回权威 `deadlineAt` 与 `expiresAt=min(30min, deadline)`），零申请零生效零通知。
- `POST /api/substitution-workbench/previews/{id}/submit-approval` — 消费凭证→创建 PENDING_APPROVAL 申请（代发对象=基线教师），进待批队列。
- `POST /api/substitution-workbench/previews/{id}/confirm` — 消费凭证→同事务创建申请后调用与普通批准完全相同的 `SubstitutionRequestService.approve`（busy 检查、学期行锁、锁后重读时钟复核截止、快照哈希重验、`PlanVerifier`、生效行、审计与通知）。
- 截止：`LessonDeadline.earliest`（多实例取最小，Asia/Shanghai 固定时区）+ `DeadlineChecker`（每次调用实时读时钟）；`ClockConfig` 提供 `Clock` bean 供测试固定。
- 幂等：凭证 `consume(action, actionHash, resultJson)`；相同动作+参数重试返回第一次结果，参数改变 409。

### 测试数字

- 纯单测：`LessonDeadlineTest` 5 + `SubstitutionPreviewTest` 5 = 10，全绿。
- HTTP 集成：`AdminSubstitutionIntegrationTest` 13 + `AdminSubstitutionDeadlineIntegrationTest` 5 = 18，全绿。
- 后端全量 `./mvnw -B test`：912 条，0 失败，5 跳过（PostgresContainerIT 等）。
- 前端：`npm run lint` 通过、`npm run build` 通过、`npm run test:unit` 404 条全绿。
- Playwright：`bash scripts/e2e.sh e2e/admin-substitute.spec.ts` 3 条全过。

### 遗留与边界说明

- 已有 EFFECTIVE 新格式代课的实例，本票在预览时明确 409（引导「课前再次调整」）；B→C 修订链属于后续票（规格第 6 节），不在本票 13 条清单内。
- SWAP（调课）的预览/确认端点契约已按实例+快照+凭证设计预留，但本票只开放 COVER（代课）动作；票 04/05 复用同一凭证表与截止机制。
- e2e 种子未复用 `seedSchool`（其学期固定 2025-09-01，服务端权威时钟会正确拒绝过去的日期），改为 `seedWorkbenchScenario` 生成未来学期并强制放置周三第 1 节。
