# 03: 只留一个规则页 + 底线置顶 + 清空旧规则 + 删除高级页与模板

**What to build:** 去掉「按类型配置」入口与页面（旧地址回到规则页），连同挂在上面的旧「规则模板」栏、迁移卡、容量提示、协作提醒一起下线；AI 规则面板按票 03b 搬到规则页。规则页顶部用两句大白话显示系统底线（一个老师同一节只能在一个班；一个班同一节只能上一门课），标「系统自带，不能关」；上线时一次性清空所有学期的旧规则和旧规则模板，只保留两条底线。旧「规则模板」的前后端与数据表一并物理删除。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] 导航和规则页里不再有「按类型配置」入口；直接访问旧地址会回到规则页
- [ ] 删除 `ConstraintsView.vue`、`components/constraints/` 下仅供高级页使用的组件与单测；规则页只保留 `types.ts` 共享类型
- [ ] 旧「规则模板」栏、`useConstraintTemplates`、后端 `ConstraintTemplateService` / `ConstraintTemplate` / 仓储 / 5 个模板接口 / 相关 DTO 一并删除
- [ ] 迁移（V56）清空非系统约束配置/条目，删除旧 `constraint_template` 数据并 `DROP TABLE`；两条底线仍生效
- [ ] 清空后自动排课、草稿校验、调代校验正常运行
- [ ] 规则页顶部显示两条底线，不可关闭、不可删除
- [ ] 全仓 `grep` 不再有 `constraint_template` / `ConstraintTemplate` / `/constraint-templates` 的活引用
- [ ] 迁移在 PostgreSQL 容器中验证通过

**Notes:** 产品所有者确认测试阶段可直接清空（2026-11-07）。AI 面板搬迁见票 03b；本票先把页面删干净，03b 再把面板接到规则页。

## 实现说明（后端部分，2026-11-07）

- 已物理删除旧「规则模板」后端：`ConstraintTemplate`、仓储、`ConstraintTemplateService`、控制器 5 个模板接口、`ConstraintService` 模板依赖、相关 DTO 与 `LifecycleService` 的 `CONSTRAINT_TEMPLATE` 标签。
- 新增 `V56__reset_rules_and_drop_templates.sql`：清空非系统 `constraint_config` / `constraint_entry`、`constraint_workspace`、`recent_deletion` 里的旧规则/模板行与 `constraint_migration_map`，并 `DROP TABLE constraint_template`；两条系统底线保留。
- `TemplateReferenceIssue` 改为 `ConstraintReferenceIssue`（规则预览、工作区、影响分析仍返回同一 JSON 形状）。
- `V56` 还会恢复被暂停/软删的系统行、为缺失的学期补上两行；`ConstraintSettings.from` 强制把 `TEACHER_CONFLICT` / `CLASS_CONFLICT` 放回快照并设为必须，数据库行缺失、暂停或软删都不会让冲突检测静默失效（新增 `ConstraintSettingsSystemRulesTest` 2 例）。
- 模板专用测试已删除；迁移测试固定在 V55 验证 V26–V29/V55 的既有行为，不再跨到 V56。
- 前端页面、导航、底线置顶与 03b 的 AI 面板搬迁仍未做，票面保持 ready-for-agent，不勾选前端相关项。

