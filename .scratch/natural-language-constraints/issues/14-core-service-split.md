# 14: 把 ConstraintService 的规划、持久化与模板拆成独立服务

**What to build:** 票 13 已抽出共享匹配器、类型化参数、完成对象和 `ConstraintAuditService`；`ConstraintService` 仍同时承担目录、规划、持久化、版本、模板和工作区清理。本票把剩余部分按职责拆开，公开 REST 契约与行为不变。

**Blocked by:** 无（票 13 已完成审计分离；本票是纯结构重构）。

**Status:** needs-triage

## 背景

- 当前 `ConstraintService` 约 1400 行：`save/preview/plan/persist/version/settings` 与模板 `save/list/apply/preview/delete` 仍在一起。
- 目标结构：
  - `ConstraintConfigService`：目录规划、保存、预览、持久化、配置版本、`settings` 快照。
  - `ConstraintTemplateService`：模板保存、列表、套用、预览、删除；依赖 `ConstraintConfigService` 的规划/持久化/版本方法。
  - `ConstraintService`：目录（`types()`）与门面/委托，保持现有 REST 与 `settings()` 调用点。
- `ConstraintAuditService` 已在票 13 完成，不再移动。
- 约束：不改语义、不改表结构、不降低测试覆盖；拆完后跑完整门禁。

## 验收

- [ ] `ConstraintConfigService` 承担规划/保存/预览/持久化/版本/`settings`，`ConstraintService` 只做目录与委托。
- [ ] `ConstraintTemplateService` 承担模板 CRUD 与套用；模板端点的公开契约不变。
- [ ] `ConstraintController`、求解器、草稿/发布/调代/CLI 的调用点全部通过门面或新服务，行为测试保持绿。
- [ ] 定向回归（目录 25 类、模板往返、版本冲突、工作区清理、AI 保存审计）+ 一次完整门禁。
