# 票05调用面与断言回归映射表（只读盘点）

## 核心分流准则
- 管理员工作台：`POST /api/substitution-workbench/previews/{id}/confirm[-swap]` 保持直接生效（保留，不改审批）。
- 老师自助申请：`POST /api/substitution-requests` 恢复为停留在 `SUBMITTED`；若后续测试验证生效/历史/改课表，须经管理员 approve 接口或显式走工作台。

## 受影响用例盘点（全部定位实测）

### 1. `backend/src/test/java/com/paiban/acceptance/FullChainAcceptanceTest.java`
- **L171-L175** (`fullFlowSchedulePublishSubstituteWithdraw`):
  - 当前事实: `as(admin).post("/api/substitution-requests")` 后立即断言 `.body("status", equalTo("EFFECTIVE"))`；且后续直接断言 `.substituted == true` 及周课表教师变更。
  - 最小适配建议: 该段声明为“票15后保留的自助路径”，提交后应断言 `status == SUBMITTED`；随后调用05新增的管理员审批接口（或改为走工作台confirm），再断言 EFFECTIVE 及课表生效行。

### 2. `backend/src/test/java/com/paiban/substitution/SubstitutionIntegrationTest.java`
- **L108-L125** (`directModeTakesEffectImmediately`):
  - 当前事实: 教师提交后立即断言 `status == EFFECTIVE` 和课表 `substituted == true`。
  - 最小适配建议: 该测试名即“直接生效”，为票15旧逻辑；应重构为“自助提交进入SUBMITTED，经审批后变为EFFECTIVE并在课表生效”，断言提交返回 `SUBMITTED`，补调审批后断言 `EFFECTIVE` 与 `substituted == true`。
- **L231-L245** (`datedSubstitutionDoesNotAffectOtherWeeksOfSameSlot`):
  - 当前事实: 教师提交后立即断言 `status == EFFECTIVE` 并查各周课表。
  - 最小适配建议: 提交断言改为 `SUBMITTED`，补调审批接口后再断言多周生效与覆盖隔离（保留既有日期计算）。
- **L273-L283** (`allViewsAgreeOnTheDatedInstance`):
  - 当前事实: 教师提交后未显式断言状态，但紧接着验证班级/教师/全校三视图均已变更为代课教师。
  - 最小适配建议: 提交后补调审批接口，再执行后续的三视图与导出一致性断言。
- **L339-L352** (`legacySubstitutionRowKeepsOverridingFollowingWeeks`):
  - 当前事实: 教师提交后直接 `substitutions.findByRequestId(requestId)` 获取生效行并改为空日期。若未审批，该表无对应记录。
  - 最小适配建议: 提交后先调审批接口使其产生真实 `Substitution` 生效行，再执行旧格式降级逻辑（绝不删除旧日期断言）。

### 3. `backend/src/test/java/com/paiban/substitution/AdminSubstitutionIntegrationTest.java`
- **L330-L336** (`plainTeacherCannotUseTheManagerWorkbench`):
  - 当前事实: 教师自助提交断言 `.statusCode(200).body("status", equalTo("EFFECTIVE"))`。
  - 最小适配建议: 改为断言 `.body("status", equalTo("SUBMITTED"))`。
- **L460-L473** (`snapshotChangeForcesRepreviewInsteadOfConfirming` -> `otherCoverRequest`):
  - 当前事实: 辅助方法 `otherCoverRequest` 期望教师提交后立即生效以改变快照哈希。
  - 最小适配建议: `otherCoverRequest` 在教师提交后补调管理员审批（或直接改为由工作台直接confirm制造快照变化）。
- **L505-L519** (`legacyFormatInstanceIsReadOnlyForManagementWrites`):
  - 当前事实: 教师提交后直接查 `substitutionRows.findByRequestId(requestId)`，若不审批则为0条抛异常。
  - 最小适配建议: 教师提交后先调管理员审批生成生效行，再篡改格式验证只读保护。

### 4. `backend/src/test/java/com/paiban/substitution/AdminSwapIntegrationTest.java`
- **L567-L575** (`snapshotChangeForcesRepreviewInsteadOfConfirming`):
  - 当前事实: 通过教师自助提交 `POST /api/substitution-requests` 制造快照冲突，期望立即产生生效记录。
  - 最小适配建议: 提交后补一步审批，或使用工作台快捷代课确认，确保快照哈希变更。

### 5. `frontend/e2e/withdraw.spec.ts`
- **L25-L38** (`发布 → 撤回（提示未来调代）→ 编辑 → 重新发布，历史仍在`):
  - 当前事实: 教师 `POST /substitution-requests` 后 `expect(request.status).toBe('EFFECTIVE')`。
  - 最小适配建议: 提交后断言 `SUBMITTED`，由管理员 token 调用审批通过接口，再断言变为 `EFFECTIVE`，后续撤回流程与未来调代提示断言完全保留。

### 6. 前端组件与表单
- `frontend/src/components/substitution/SubstitutionRequestForm.vue:175`: 提交后原 toast 提示直接完成；需适配为「申请已提交，等待教务审批」，去掉调课选项（票05要求）。
- `frontend/src/views/SubstitutionsView.vue:13-14`: 提交后刷新 `mineRef`；表格组件 `SubstitutionMineTable.vue` 需准备展示 SUBMITTED、APPROVED、REJECTED 等状态及驳回理由。
