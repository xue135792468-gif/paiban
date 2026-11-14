# 票05恢复审批代码定位图（只读盘点）

盘点基点：集成 `60a7d9d`，没有执行测试，不代表验收通过。统筹已核对提交、锁、operator字段、前端表单与可调时钟的主要定位。

## 1. 提交→立即生效路径
- Controller入口: `backend/src/main/java/com/paiban/substitution/web/SubstitutionController.java:52-57` (`create` 方法)
- Service实现: `backend/src/main/java/com/paiban/substitution/service/SubstitutionRequestService.java:79-183` (`create` 方法)
- 立即生效逻辑: 140-159行保存为 `SUBMITTED`；161行 `verifyCoverOrReject` 静态校验；164-169行 `DeadlineChecker` 开课截止检查；170-172行设为 `EFFECTIVE`；173-174行写历史；176行 `retireEffectiveRowAt`；177-178行 `createCoverSubstitution` 生效行；179行 `notifyEffective`。

## 2. 真实 operator_user_id 是否填写
- 自助提交路径 (`SubstitutionRequestService.java:140-159`): **未填写** (实体 `operatorUserId` 保持 null，无 `setOperatorUserId` 调用)。
- 对比: 工作台管理端 (`AdminSubstitutionService.java:371`, `AdminSwapService.java:466`) 显式写入 `operator.getId()`。

## 3. 学期锁与请求锁
- 学期锁: **存在**。`SubstitutionRequestService.java:131` 调用 `effects.lockSemester(semesterId)` (底层 `semesters.findWithLockById` 悲观锁)。
- 请求锁: **不存在**。`SubstitutionRequestRepository.java` 无行锁方法，`SubstitutionRequestService.java` 无针对申请行的锁。
- 顺序: 仅先拿学期锁，无请求锁，不存在两锁先后嵌套。

## 4. 查询/明细/历史 ownership 判据
- 列表 `list` (`SubstitutionRequestService.java:186-213`): 判定 `mineOnly || !hasPermission(SUBSTITUTION_VIEW)`，普通教师强制限为 `user.teacherId` 查本人；Controller 传 `mine || !canApprove`。
- 明细 `detail` (`SubstitutionRequestService.java:215-224`): 非 `SUBSTITUTION_VIEW` 必须 `user.teacherId == request.requesterTeacherId`，否则 403。
- 历史 `history` (`SubstitutionRequestService.java:226-258`): 满足以下任一即可，否则 403：(1) 有 `SUBSTITUTION_VIEW`；(2) 原教师或申请人 (`originalTeacherId`/`requesterTeacherId`)；(3) 代课教师 (生效行 `substituteTeacherId`)；(4) 班主任 (`adminClass.headTeacherId`)。

## 5. 前端组件入口
- 教师自助申请表单: `frontend/src/components/substitution/SubstitutionRequestForm.vue` (挂载在 `frontend/src/views/SubstitutionsView.vue` mine tab，含 COVER/SWAP 选项)
- 教师本人申请列表: `frontend/src/components/substitution/SubstitutionMineTable.vue` (挂载在 `SubstitutionsView.vue` mine tab)
- 管理端调代记录列表: `frontend/src/components/substitution/SubstitutionRecordsPanel.vue` (挂载在 `SubstitutionsView.vue` records tab，需 `substitution:view` 或 `substitution:view:class`)
- 教务工作台入口: `frontend/src/views/SubstitutionWorkbenchView.vue`
- 管理端「待审批」列表组件: **当前不存在** (待实现)

## 6. 可复用 HTTP fixture 与可调 Clock 测试路径
- 通用课表/调代 HTTP fixture: `backend/src/test/java/com/paiban/support/ScheduleFixture.java`
- 调代场景专用 fixture helper: `backend/src/test/java/com/paiban/substitution/AdminSubstitutionIntegrationTest.java:74` (`buildFixture`)
- 可调 Clock 测试路径 (使用 `@MockitoBean private Clock clock;` + `AtomicReference<Instant>`):
  - `backend/src/test/java/com/paiban/substitution/AdminSubstitutionDeadlineIntegrationTest.java:49-59`
  - `backend/src/test/java/com/paiban/substitution/AdminSwapDeadlineIntegrationTest.java:55-66`
  - `backend/src/test/java/com/paiban/substitution/SubstitutionRescheduleDeadlineIntegrationTest.java:54-65`

## 7. 需更新旧直接生效断言的测试文件
- `backend/src/test/java/com/paiban/substitution/SubstitutionIntegrationTest.java` (`directModeTakesEffectImmediately`, `repeatedCoverRetiresPreviousEffectiveRow`, `coversOnDifferentWeeksResolveIndependently`)
- `backend/src/test/java/com/paiban/substitution/AdminSubstitutionIntegrationTest.java` (`plainTeacherCanSubmitCoverForOwnLesson` 及辅助方法 `otherCoverRequest`, `legacyFormatInstanceIsReadOnlyForManagementWrites`)
- `backend/src/test/java/com/paiban/substitution/AdminSwapIntegrationTest.java` (`concurrentCoverInvalidatesSwapSnapshotAndVoucherRejected`)
- `backend/src/test/java/com/paiban/acceptance/FullChainAcceptanceTest.java` (行 171-177 教师自助提交断言 `EFFECTIVE`)

## 统筹补充的验收重点

- `detail/history` 实际用 `Objects.equals` 比较可空教师ID，不能把这里的“==”描述误读成已安全鉴权；两个null会相等。05按实际operator账号判定申请归属，相关教师关系必须先判非空；保留的班主任分支仍需对应权限。
- 待批查重当前发生在学期锁之前；05必须在统一锁之后复查，并以并发HTTP证明同实例最多一张待批。
- 审批按学期→申请顺序锁定并读新状态，避免锁前载入的JPA缓存掩盖并发处理。提交后课表已变更、基线撤回、代课教师停用或已过开课时刻都需按当前事实处理。
- 过期审批返回拒绝后，再GET必须看到已作废和历史。不能用对全部ApiException免回滚的做法保存作废，以免同时提交其他失败操作的半写。
- 可复用已有 `@MockitoBean Clock` + AtomicReference测试边界；不改生产时钟，不为每个状态新造通用框架。
