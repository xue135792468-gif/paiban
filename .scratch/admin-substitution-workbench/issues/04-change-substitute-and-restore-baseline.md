# 04: 课前更换代课人与恢复原安排

**What to build:** 允许课前对仍有效的授课实例再次修订：A→B 后可预览改为 C，或预览恢复基线教师 A。新修订经审批前旧安排 B 继续执行；确认后以新版本替代当前有效安排，历史保留完整。净代入/代出统计按最终有效安排计算，解除和接替教师均收到去重通知。

**Blocked by:** 03:管理员指定代课：预览、代发与亲自确认

**Status:** in-progress (2026-10-29, branch feature/t04-change-substitute-restore)

- [x] HTTP 端到端完成 A→B 后再次预览 B→C；待审批期间当前教师仍为 B，批准后当前教师为 C。
- [x] HTTP 端到端预览并确认恢复 A；当前安排回到基线，历史 A→B 与恢复修订仍可追溯。
- [x] 修改代课人或恢复基线均展示新的左右对照和变化清单，不能直接改写或删除历史事件。
- [x] 按当前实际安排计算代入/代出：A→B→C 时 A 被代一次、C 代课一次、B 不计当前代课；恢复 A 后本次代入/代出为零。
- [x] 当前课表、教师/班级/教室视图及导出一致；旧代课人不再看到该课，新代课人只看到一次。
- [x] 每次生效修订向调整前后受影响教师并集发送通知，包括解除安排的教师；相同修订重试不重复通知。
- [x] 同一实例已有活跃待审批修改时阻止并行新单并引导查看原单；不静默取消他人申请。
- [x] 两管理员基于同一旧版本竞争时最多一个修订生效，另一方得到冲突，上一有效安排保持完整。
- [x] 截止前可修订；开课时刻等于或晚于截止拒绝，包括恢复基线；旧预览不得跨截止确认。
- [x] Playwright 覆盖查看当前 B、发起改 C/恢复 A、审批状态和当前有效结果。

## 规格依据

`spec.md`「课前再次调整与截止」「统计、历史与通知」「Testing Decisions」。

## 交付记录

2026-10-29，分支 `feature/t04-change-substitute-restore`（票 01/02/03/08 集成之上）。

### 修订链落库设计（V41）

- `substitution_request` 加 `revises_request_id`（自引用 FK，前驱申请）与 `revision_kind`
  （`CHANGE_SUBSTITUTE` / `RESTORE_BASELINE`）；`substitution_preview` 同样加这两列，
  凭证绑定「快照哈希 + 基准申请」，确认时双重复核。
- 修订 = 新申请 + 新生效行；批准（与普通批准同一 `approve` 路径）时基准生效行转入
  新状态 `SUPERSEDED`（行保留、内容不改写，历史事实仍在行与流转历史里），新行接管该实例。
  读模型只装载 EFFECTIVE 行，因此状态迁移就是原子版本切换；驳回/过期/校验失败零改动。
- 纯判定集中在 `service/RevisionPolicy.java`（修订类型判定、恢复可行性、统计谓词），配纯单测。

### 统计口径改法

`SubstitutionQueryService.stats` 不再按 EFFECTIVE 行数直接计数，改用
`RevisionPolicy.countsAsCurrentCover` 谓词过滤：只数「当前生效、非恢复行」的 COVER 覆盖。
A→B→C 后 A 被代 1、C 代课 1、B 为 0；恢复 A 后代入/代出均为 0；被替代旧行退出统计，历史保留。
`SubstitutionRepository` 两个旧的 count 查询随之删除。调课参与计数（swap）未动，留给票 07。

### 通知

`NotificationService.substitutionEffective` 修订分支：被解除的「调整前教师」收
`SUBSTITUTION_REVISION_RELEASED`，接替的「调整后教师」收 `SUBSTITUTION_REVISION_ASSIGNED`，
两者均含「取代申请 #<前驱 id>」；班主任/管理员通知的前后变化同样呈现本次修订（前教师 → 新教师）。
同一修订重试靠凭证幂等重放不重复通知（测试断言通知数不变）。

### 顺手加固

凭证过期改为 `markExpiredIfActive` 条件更新（绕过持久化上下文），
并发下「后到者的旧行快照」不再可能覆盖他人已写入的消费结果。

### 验收证据（票据 10 条对照）

| 条目 | 证据 |
| --- | --- |
| 1. A→B→C 全链 | `SubstitutionRevisionIntegrationTest.coveredInstancePreviewProducesRevisionInsteadOfConflict` + `revisingCoverKeepsCurrentTeacherUntilApprovedThenSwitches`（待批 B 生效 / 批准 C 生效 / 旧行 SUPERSEDED） |
| 2. 恢复 A + 追溯 | `restoreBaselineThroughRevisionKeepsHistoryTraceable`（API 返回 revisesRequestId/revisionKind；两单流转历史完整） |
| 3. 对照清单、不改写历史 | 预览 changes 断言（B→C）+ e2e `revision-banner`/`change-list`；DB 断言旧行保留仅状态迁移 |
| 4. 统计口径 | `statsCountOnlyCurrentArrangementAgainstBaselineTeacher` + `RevisionPolicyTest` |
| 5. 四视图与导出 | `allViewsAndExportsFollowTheCurrentArrangement`（班级/教师/教室/全校 JSON + 丙导出空、丁导出有课） |
| 6. 通知并集 + 去重 | `revisionNotifiesReleasedAndAssignedTeachersWithoutDuplicates`（解除者 +1、接替者 +1、重试不变） |
| 7. 单活跃修改 | `pendingRevisionBlocksParallelModification`（409 引导 #原单；丙仍生效）+ 票 03 `singleActiveModificationPerInstance` 回归 |
| 8. 两管理员竞争 | `twoRevisionsFromSameBaseAllowOnlyOneToTakeEffect`（后确认者 409、无半截状态、可重新预览） |
| 9. 截止三分 | `SubstitutionRevisionDeadlineIntegrationTest` 4 条（前一瞬可确认 / 恰好拒绝 / 预览拒绝 / 恢复基线拒绝） |
| 10. Playwright | `frontend/e2e/admin-substitute-revision.spec.ts` 2 条（改 C 待批+审批状态；恢复 A 当前有效结果） |

### 测试数字

- 新增后端：纯单测 3 + HTTP 集成 9 + 截止集成 4 = 16 条。
- 后端全量 `./mvnw -B test`：**961 tests, 0 failures, 0 errors, 8 skipped（既有）**。
- 前端：`npm run lint` 通过；`npm run test:unit` **407 passed**；`npm run build` 通过。
- Playwright：`admin-substitute-revision.spec.ts` **2 passed**；票 03 回归 `admin-substitute.spec.ts` **3 passed**。
- `scripts/check-migrations.sh`：40 unique versions（V41 无冲突；票 05 占 V42）。

### 遗留与边界

- SUPERSEDED 的历史展示区分（「已被后续安排替代」状态呈现）留给票 07 深化；
  本票在申请 API 暴露 `revisesRequestId`/`revisionKind` 供追溯。
- SWAP 行覆盖实例的再次调整（同班调课修订）归票 05；本票 `currentCoverRow` 显式排除 SWAP 行。
- 队列批准的修订在 `approve` 锁后做了开课截止复核（规格第 6 节「批准也受截止约束」）；
  非修订请求的批准截止复核不在本票范围，保持现状。
