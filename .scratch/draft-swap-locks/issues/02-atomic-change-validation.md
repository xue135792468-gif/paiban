# 02 统一整单变更校验与旧冲突修复

Status: done
Type: task
Blocked by: 01
Owner: worker
日期：2026-11-02

## 目标与输入

01锁定/并发契约；规格§一、五、七；现有ConstraintChecker、LessonView。
前置票必须验收并合入后才能认领；ready-for-agent表示规格已就绪，不表示依赖已完成。
共同约束见[规格](../spec.md)、[接口契约](../contracts.md)、[执行路线](../ticket-plan.md)。

## 写入范围与边界

DraftValidator、SwapService、SchedulingService、草稿变更事务服务、DraftPublishReadiness/发布闸门的必要适配及测试；不实现搜索或页面。
只修改本票必要文件；发现契约冲突先由统筹统一更正，不能让多个worker各自猜接口。不得改生产数据或部署。

## 实施步骤

1. 建立位置/教师变更列表的单一应用入口，构造变更后完整视图，检测不完整映射、重复行、越草稿行、非法时段、跨班隐式换位和锁定课。
2. 支持直接同班两课交换和单课空格移动，保留课程/行政班/教师与固定/专用教室语义。跨班换人保留显式入口，仍服从锁定。
3. 整单验证不能只把A放到仍未搬走的B位置就拒绝；先应用全部候选变化到内存快照再判。
4. 为修复中草稿比较前后硬违规身份及严重程度：旧问题不相关时不封死修复，但不允许新增别的违规或加重原违规。最终发布仍绝对全量检查。
5. 批量变更一次事务写入；支持逆向变更整单校验，供05会话撤销使用；服务端重新读取状态，不信任客户端before/after。

## 验收清单

- [x] 合法A/B交换两条同成同败；同教师双课、空格移动、锁定和禁排处理正确。
- [x] 旧有三处冲突可修一处；修一处同时新增另一处、或原违规严重度加重必须拒绝，不能只比总数。
- [x] 整单失败无部分保存；原已发布基线和已归档草稿不可改。
- [x] 全量硬违规、缺课/失效引用和现有发布流程限制仍阻止发布；软偏好损失独立返回。

## 验证与交付

定向DraftValidator、SwapService、变更服务和发布闸门集成测试，包含强制交错并发和逆向变更反例。
每票提交Delivery：实际改动文件、分支/基点/提交、真实命令与退出码、剩余问题；全量门禁统一由09执行。
任务前检查资源；实际执行默认group/deepseek、high；机械搜证仅group/gemini3.8；算法结论由主模型负责。
遵守AGENTS/CODING_STANDARDS，不同worktree隔离；结束清理本票临时文件与进程。

## Delivery

**实现分支**：`codex/t02-atomic-changes`（worktree `E:/paiban-wt/t02-changes`）
**基点**：`codex/draft-swap-locks`（含 01/03，HEAD `5d1bdf4`）
**提交**：`feat(scheduling): add atomic draft change validation`

### 改动文件

新增：

- `backend/src/main/java/com/paiban/scheduling/service/DraftChangeService.java`
- `backend/src/main/java/com/paiban/scheduling/web/DraftChangeController.java`
- `backend/src/main/java/com/paiban/scheduling/web/DraftChangeDtos.java`
- `backend/src/test/java/com/paiban/scheduling/DraftChangeIntegrationTest.java`
- `backend/src/test/java/com/paiban/scheduling/service/DraftValidatorChangeAssessmentTest.java`
- `backend/src/test/java/com/paiban/scheduling/solver/ConstraintSettingsFingerprintTest.java`

修改：

- `backend/src/main/java/com/paiban/scheduling/constraint/ConstraintViolation.java`（稳定 key，保留 4 参构造器）
- `backend/src/main/java/com/paiban/scheduling/constraint/ConstraintChecker.java`（6 类聚合/全局违规显式作用域键）
- `backend/src/main/java/com/paiban/scheduling/solver/ConstraintSettings.java`（`fingerprint()`）
- `backend/src/main/java/com/paiban/scheduling/service/DraftValidator.java`（`assessChanges`/`ChangeAssessment`/`Violation`/`rulesFingerprint`；`ValidationResult` 追加字段）
- `backend/src/main/java/com/paiban/scheduling/web/SchedulingDtos.java`（`DraftDetailDto` 追加 `rulesFingerprint`）
- `backend/src/main/java/com/paiban/scheduling/service/SchedulingService.java`（详情填 `rulesFingerprint`）

### 新增接口

`POST /api/timetable-drafts/{id}/changes`，权限 `Permissions.TIMETABLE_EDIT`。

- 请求：`changes[{lessonId,toPeriodId,toTeacherId,toRoomId,expectedPeriodId,expectedTeacherId,expectedRoomId}]`、`expectedVersion`、`rulesFingerprint`、`verifySource`。
- `toTeacherId`/`toRoomId` 为 null 保持当前值；`toPeriodId` 为 null 表示移到未排课。
- 响应：`saved/valid/version/hardConflicts/softPenaltyBefore/softPenaltyAfter/softPenaltyDelta/addedConflicts/remainingConflicts/conflicts`。
- 400：空 changes、重复/越草稿 lessonId、不存在或跨学期 periodId、不存在教师/教室。
- 409：版本过期、`verifySource` 来源不符、规则指纹不符、锁定课改 period/teacher。
- 业务拒绝（新增或加重硬违规）返回 200 + `saved=false`，整单零写入。

稳定违规键：逐课 `code|L|lessonId`；聚合/全局 `SAME_COURSE_PER_DAY|<class-course-day>`、`COURSE_SPREAD|<class-course-day>`、`CLASS_GAP_MINIMIZED|<class-day>`、`CONSECUTIVE_LESSONS|<class-course-day>`、`TEACHER_MAX_CONSECUTIVE|<teacher-day>`、`<TYPE>|<bucket-key>`；比较按同键出现次数，次数增加视为加重。

### 验证命令与真实退出码

```bash
cd /e/paiban-wt/t02-changes
source .tools/env.sh
cd backend
./mvnw -B -q -DskipTests compile
# EXIT=0

./mvnw -B -DforkCount=0 -Dtest=DraftChangeIntegrationTest,DraftValidatorChangeAssessmentTest,ConstraintSettingsFingerprintTest,DraftLockIntegrationTest,SwapIntegrationTest,SchedulingIntegrationTest,ConstraintCheckerTest test
# EXIT=0；Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
```

单独先跑的新增用例：`DraftChangeIntegrationTest` 9/9 通过、`DraftValidatorChangeAssessmentTest` 5/5、`ConstraintSettingsFingerprintTest` 4/4（均 EXIT=0，日志 `/tmp/t02-unit.log`、`/tmp/t02-it.log`、`/tmp/t02-suite.log`）。按票面要求未运行全量 `verify.sh`、未起 Docker。

### 剩余问题

- 本票按施工单只做后端；`PATCH /lessons/{id}` 与 `POST /swap` 的响应字段与 force 语义保持不变，未改造它们走新比较逻辑（04/05 消费 `/changes`）。
- 软分 delta 只覆盖 `ConstraintChecker.softPenalty` 已实现的软规则；默认学期配置仅三条硬规则时前后软分均为 0，属预期。
- 未做真实并发压测，交错场景用「两个客户端都持旧版本」的 HTTP 不变量覆盖（第二请求 409、落库行不变）。
- 09 复审补充：违规比较从“同 key 次数”升级为“同 key 权重和”；聚合桶由 2 节变 3 节、配对冲突从 A-B 换成 A-C 都会判为新增/加重。新增 `DraftValidatorChangeAssessmentTest` 两个反例，后端全量 1195 项通过。
- 全量门禁与发布由 09 统一执行。

## Comments
