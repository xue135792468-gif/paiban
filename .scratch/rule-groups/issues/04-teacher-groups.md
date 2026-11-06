# 04: 教师分组（端到端）

**What to build:** 教务管理员可以建教师分组（如「教导主任」）、挑选成员；一个老师可在多个组。规则页有分组管理区，写规则时「谁」的下拉可直接选组，也可在下拉里顺手新建组。组成员变化后，引用该组的规则自动作用于新成员。教师列表与规则下拉显示组后缀（「王老师 · 教导主任」），教师列表可按组筛选。

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] 新增分组与分组成员数据（迁移从 V57 续号）；教师组为全局，跨学期保留
- [ ] 规则页分组区可新建、改名、增减成员、删除教师组
- [ ] 规则「谁」可选教师组；下拉内可新建组
- [ ] 句子显示组名，如「教导主任 周一上午 不上课」
- [ ] 约束快照构建时把教师组展开为成员；改组成员后不改规则即生效（集成测试）
- [ ] 草稿校验与调代校验对组规则结论一致
- [ ] 教师列表、规则下拉显示组后缀；教师列表可按组筛选
- [ ] 删除被引用的组时先列出引用规则，可选「先改规则」或「连同规则一起删除」；删除走 7 天可撤销

## 实现说明（后端部分完成，04a）

Status 仍为 `ready-for-agent`：本文件只落地后端；分组管理区、规则「谁」下拉、句子/列表后缀与筛选等页面项留在 04b。

- **迁移 V57**：`rule_group` / `rule_group_member` 与索引。唯一性用服务层维护的 `live_key` / `member_key`，没有使用部分唯一索引（H2 2.3 不支持，PostgreSQL 与本地测试行为一致）。软删除时 `live_key` 置 NULL，恢复时重算；同名活组冲突抛 409。
- **API**：`/api/rule-groups` 的 GET（读权限 `constraint:manage` / `foundation:manage` / `semester:manage`）、POST、PUT、`GET /{id}/delete-impact`、`DELETE /{id}?mode=DELETE|WITH_RULES`（写权限 `constraint:manage`）。教师/课程组全局，时间组必须带 `semesterId` 并校验坐标属于该学期作息。
- **快照展开**：唯一改动点是 `ConstraintConfigService.settings(semesterId)` 与 `settingsOf(Planning)`，两者都调用 `RuleGroupExpander`；持久化行仍存组引用，求解器、草稿校验、调代计划只消费同一份展开后的 `ConstraintSettings`。展开出的行保留原 `groupKey` / 开关 / 软硬 / 权重；组不存在、为空、成员全部失效时该行展开为 0 行（规则静默失效）。
- **保存校验**：`validateParams` 接受与规则目标类型匹配的 `teacherGroupId` / `courseGroupId` / `timeGroupId`；组必须存在、未删除、类型匹配，时间组还必须属于当前学期；具体对象与组键不能同时出现；类型不支持的组键按无效参数拒绝。`validateTimetableConditions` 在带时间组时跳过具体 day/period 存在性检查。`invalidReferences` 会为已删除/不存在/类型不符的分组给出原因；**空组提示未实现**（需要额外加载成员，留待 04b/页面提示）。
- **删除/撤销**：`DELETE` 有引用时返回 409 + `rules` 清单；`WITH_RULES` 先用 `RuleLifecycleService.delete(configId, cardKey, entryIds)` 按规则卡删除引用行，再软删组并写入 `recent_deletion`（`entity_type = RULE_GROUP`，`primary_flag = true`）。组和规则卡是**多个独立批次**（规则卡原本就各有一个批次），因此可以分别撤销；响应 `{batchIds, deletedRules}` 中 `deletedRules` 按规则卡计数。
- **测试**：`RuleGroupIntegrationTest` 9 条，覆盖建组/重名、时间组学期与坐标、快照展开、改成员即生效、停用/删除成员、保存校验（不存在/已删/类型不符/同时写具体对象/类型不支持组键）、删除影响/409/`WITH_RULES`/分批撤销、草稿校验、调代快照。调代只覆盖到 `PlanSnapshotFactory` 读取同一份展开快照，未构造完整调代审批请求。
- **与契约的已知偏差**：契约 §4 的成员请求体写 `{kind, id?}`，按本票后端任务书实现为 `{teacherId?, courseId?, dayOfWeek?, periodNo?}`；契约 §5.2 说组与规则同批次，按任务书改为各自的 `recent_deletion` 批次并在此说明。
