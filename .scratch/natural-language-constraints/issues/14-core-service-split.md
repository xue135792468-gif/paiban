# 14: 把 ConstraintService 的规划、持久化与模板拆成独立服务

**What to build:** 票 13 已抽出共享匹配器、类型化参数、完成对象和 `ConstraintAuditService`；`ConstraintService` 仍同时承担目录、规划、持久化、版本、模板和工作区清理。本票把剩余部分按职责拆开，公开 REST 契约与行为不变。

**Blocked by:** 无（票 13 已完成审计分离；本票是纯结构重构）。

**Status:** done（2026-10-28 完成三服务拆分；公开 REST 契约与行为不变）

## 背景

- 当前 `ConstraintService` 约 1400 行：`save/preview/plan/persist/version/settings` 与模板 `save/list/apply/preview/delete` 仍在一起。
- 目标结构：
  - `ConstraintConfigService`：目录规划、保存、预览、持久化、配置版本、`settings` 快照。
  - `ConstraintTemplateService`：模板保存、列表、套用、预览、删除；依赖 `ConstraintConfigService` 的规划/持久化/版本方法。
  - `ConstraintService`：目录（`types()`）与门面/委托，保持现有 REST 与 `settings()` 调用点。
- `ConstraintAuditService` 已在票 13 完成，不再移动。
- 约束：不改语义、不改表结构、不降低测试覆盖；拆完后跑完整门禁。

## 验收

- [x] `ConstraintConfigService` 承担规划/保存/预览/持久化/版本/`settings`，`ConstraintService` 只做目录与委托。
- [x] `ConstraintTemplateService` 承担模板 CRUD 与套用；模板端点的公开契约不变。
- [x] `ConstraintController`、求解器、草稿/发布/调代/CLI 的调用点全部通过门面或新服务，行为测试保持绿。
- [x] 定向回归（目录 25 类、模板往返、版本冲突、工作区清理、AI 保存审计）+ 一次完整门禁。

## Implementation (2026-10-28)

- `ConstraintConfigService`（1259 行）：`list/state/save/preview/audits/active/hasConfigs/settings`、学期创建默认配置事件、规划与持久化、共享配置版本。规划/提交版本方法以包内可见方法开放给模板服务，避免复制校验与写入顺序。
- `ConstraintTemplateService`（249 行）：模板保存/列表/套用/预览/删除；快照读取与班级重绑定；套用走 `ConstraintConfigService` 的同一套 plan → version → persist（`commitPlan`），409 的 `errors` + `invalidReferences` 形状不变。
- `ConstraintService`（157 行）：仅保留 25 类目录 `types()` 与对两个服务的门面委托；controller、`DraftValidator`、`SchedulingService`、`PlanSnapshotFactory`、`ConstraintWorkspaceService` 调用点未改。
- `ConstraintTemplate` 的 javadoc 引用同步改为 `ConstraintTemplateService.TEMPLATE_JSON_LIMIT`。
- 无迁移、无表结构变化、无前端改动。
- 定向回归 `Constraint*Test,EngineConstraintTest`：**244** 例全绿（0 失败/0 错误）。
- 完整门禁：后端 **872** 例（0 失败/0 错误、5 skipped）、前端 **401** 例、Playwright **91** 例，SFC/isBlank/迁移/票据状态/AI 超时检查全绿（`verify.sh --full` EXIT=0）。
