# 13: 评审遗留：约束核心去重、服务拆分与 E2E 助手收口

**What to build:** 把 2026-10-27 两轴评审与 retro 中列为判断项、但不影响本轮行为正确性的结构问题作为后续重构处理；本轮不改变业务语义。

**Blocked by:** 无（后续技术债，不阻塞票 14 的合并/部署）。

**Status:** done（2026-10-27 重构完成并部署；核心服务的规划/持久化/模板拆分转入票 14）

## 背景

票 14 的 Standards 评审把以下结构问题列为 judgement call，未在功能票内重构，避免在 12 票收口时引入行为变化：

- `ConstraintChecker`、`PaibanConstraintProvider`、`SwapPlanner` 各自维护一份“条目是否匹配课程/教师/课节/体育类”的谓词；三者目前一致，但未来改动容易漂移。建议抽出共享的 `ConstraintEntryMatcher`。
- `ConstraintService` 同时承担目录、保存、版本、审计、模板、预览、工作区清理；建议按“目录/规划/持久化/模板/审计”拆分。
- `ConstraintProposalService.finish` 与 `finishRunning` 有 12–13 个位置参数；`constraint_entry.params_json` 以字符串键（`courseId`、`dayOfWeek`、`periodNo`…）在 checker/provider/normalizer/planner 间传递，建议引入小的参数对象或类型化访问器。
- `ConstraintAiPanel` 的五段 `v-if` 状态提示可收敛为状态到提示的映射；`AiProvidersView` 的任务行已改为数据驱动，此项仅剩面板。
- 7 个约束 E2E spec 各自复制了一份 `pickOption`，且两套实现已经漂移（`.first()` 与 `.last()` + 精确正则）。建议收进 `frontend/e2e/helpers.ts`；修改节次等共享控件时只需改一处。

## 验收

- [x] 抽取共享的 `ConstraintEntryMatcher`，checker/provider/planner 全部调用它，行为测试保持绿（`2862cc9`）。
- [x] 审计/保存历史从核心服务分离为 `ConstraintAuditService`；规划/持久化/模板仍合在 `ConstraintService`，这一半转入票 14（`a26ce89`）。
- [x] 参数访问集中到 `ConstraintParams`；`ConstraintProposalService.finish`/`finishRunning` 改用 `ConstraintRunCompletion` 对象（`e19b8aa`、`eb847ec`）。
- [x] `pickOption` 只在 `frontend/e2e/helpers.ts` 实现一次，7 个约束 spec 改为 import；相关 e2e 27 例绿（`5750c8c`）。
- [x] `ConstraintAiPanel` 的状态提示收敛为 `runNotice` 映射（`d9417e9`）。
- [x] 定向回归 + 完整门禁；票 14 已确认的语义不变（门禁结果见交付说明）。

## Implementation (2026-10-27)

- 共享匹配器：`ConstraintEntryMatcher` + 3 个调用方重构；契约单测 `ConstraintEntryMatcherTest`。
- 类型化参数：`ConstraintParams` 集中 `courseId/teacherId/dayOfWeek/periodNo/roomType/subject`；`ConstraintRunCompletion` 替代 `finish` 的 12 个位置参数，repository 默认方法解包。
- 服务拆分：`ConstraintAuditService` 独立；`ConstraintService` 仍负责规划/持久化/模板，剩余拆分见 [票14](14-core-service-split.md)。
- 前端/E2E：AI 面板单映射；`pickOption` 收进 `helpers.ts`。
- 完整门禁：后端 **863** 例（0 失败/0 错误、5 skipped）、前端 **401** 例、Playwright **90** 例、SFC/isBlank/迁移检查全绿；定向 e2e 27 例（7 个约束 spec 共用 helper）。
- 部署：`main` `6ce8a2c` 已上线；Flyway 37、四容器 healthy、两处 health 200；`/api/constraint-proposals/availability` 返回 `available:true`，`/api/constraint-audits` 200，NL 任务仍绑定 provider 2/model 4。
