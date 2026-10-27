# 13: 评审遗留：约束核心去重、服务拆分与 E2E 助手收口

**What to build:** 把 2026-10-27 两轴评审与 retro 中列为判断项、但不影响本轮行为正确性的结构问题作为后续重构处理；本轮不改变业务语义。

**Blocked by:** 无（后续技术债，不阻塞票 14 的合并/部署）。

**Status:** needs-triage

## 背景

票 14 的 Standards 评审把以下结构问题列为 judgement call，未在功能票内重构，避免在 12 票收口时引入行为变化：

- `ConstraintChecker`、`PaibanConstraintProvider`、`SwapPlanner` 各自维护一份“条目是否匹配课程/教师/课节/体育类”的谓词；三者目前一致，但未来改动容易漂移。建议抽出共享的 `ConstraintEntryMatcher`。
- `ConstraintService` 同时承担目录、保存、版本、审计、模板、预览、工作区清理；建议按“目录/规划/持久化/模板/审计”拆分。
- `ConstraintProposalService.finish` 与 `finishRunning` 有 12–13 个位置参数；`constraint_entry.params_json` 以字符串键（`courseId`、`dayOfWeek`、`periodNo`…）在 checker/provider/normalizer/planner 间传递，建议引入小的参数对象或类型化访问器。
- `ConstraintAiPanel` 的五段 `v-if` 状态提示可收敛为状态到提示的映射；`AiProvidersView` 的任务行已改为数据驱动，此项仅剩面板。
- 7 个约束 E2E spec 各自复制了一份 `pickOption`，且两套实现已经漂移（`.first()` 与 `.last()` + 精确正则）。建议收进 `frontend/e2e/helpers.ts`；修改节次等共享控件时只需改一处。

## 验收

- [ ] 抽取共享的条目匹配函数，checker/provider/planner 全部调用它，行为测试保持绿。
- [ ] 核心服务按职责拆分，公开 REST 契约与测试不变。
- [ ] 参数访问不再依赖散落的字符串键（或至少集中到一个访问器）。
- [ ] `pickOption` 只在 `frontend/e2e/helpers.ts` 实现一次，7 个约束 spec 改为 import；相关 e2e 保持绿。
- [ ] 定向回归 + 一次完整门禁；不改变票 14 已确认的语义。
