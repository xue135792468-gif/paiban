# 05: 课程分组

**What to build:** 和教师分组同样的能力用于课程：可建「技能课」= 体育、美术、音乐；规则「什么课」可选课程组；课程列表显示组后缀并可按组筛选。

**Blocked by:** 04

**Status:** done

- [x] 课程组为全局，跨学期保留；一门课可在多个组
- [x] 规则「什么课」可选课程组，下拉内可新建
- [x] 句子显示组名，如「技能课 每门每天最多 1 节」（Hark 复审后补「每门」，避免读成整组合计）
- [x] 快照展开课程组；改成员即生效（集成测试）
- [x] 课程列表、规则下拉显示组后缀；课程列表可按组筛选
- [x] 删除影响清单与 7 天撤销同票 04

## 实现说明

- **后端不需要改表**：票 04 的 `RuleGroupService` / `RuleGroupExpander` / `ConstraintConfigService` 已按组类型通用实现，`COURSE` 组的 CRUD、成员校验、快照展开、删除影响与 `WITH_RULES` 撤销均是现成能力；V57 不动。
- **后端测试**：`RuleGroupIntegrationTest` 新增 4 条课程组用例（总数 13 条）：
  1. 建「技能课」含 2 门课；同名再建 409；课程组带 `semesterId` 400；成员传 `teacherId` 400。
  2. `COURSE_MAX_PER_DAY` 存 `{courseGroupId, maxPerDay}`，快照展开为每门课一行且不带 `courseGroupId`；持久化行仍只存组引用；改成员后不重存规则，`settings()` 立即反映新成员。
  3. `COURSE_UNAVAILABLE` 展开到每门课；草稿校验对组内课程报 `COURSE_UNAVAILABLE`、组外课程不报。
  4. `DELETE` 被引用组返回 409 + 影响清单；`WITH_RULES` 连带软删规则卡（`deletedRules = 1`），快照不再包含该规则。
- **前端通用化**：
  - `rules/useRuleGroups.ts` 改为 `useRuleGroups(type: 'TEACHER' | 'COURSE')`，按类型映射 `{teacherId}` / `{courseId}` 成员和错误文案；`RuleBuilderView` 同时持有两个实例。
  - `RuleGroupSection.vue` 改为 `groupType + title + memberLabel + options` 驱动，文案、成员筛选、对话框标题与成员下拉都按类型；教师面板保留票 04 的 test id，课程面板追加 `-course` 后缀，避免同页重复。
  - 新增 `RuleGroupPanels.vue` 同时渲染教师/课程两个分组区，并共用页面上的两个 composable，新建组立刻进入规则下拉；规则页因此从 604 行降到 539 行。
- **规则句子**：`RuleNames.group` 拆为 `teacherGroup?` / `courseGroup?`（预留 `timeGroup?`），避免教师组和课程组 id 相同却显示错名字；`SubjectKind` 增加 `COURSE_GROUP`，`courseSubject` / `pendingSubject` / `acceptsSubject` / `clearUnrelatedIds`（`onTypeChange` 清 `courseGroupId`）均支持。`compiler.ts` 的 `courseGroupId` 落库/回填/`allowedKeys` 沿用 04b 预埋逻辑，补了直接测试。
- **下拉与新建**：`RuleSentenceEditor` 的「课程」选项组增加「课程组 · 技能课」与「＋ 新建课程组」；新建流程与教师组共用 `promptGroupName`，取消时保持原主语。
- **课程列表**：`CoursesView` 拉 `getRuleGroups('COURSE')`，名称列显示「体育 · 技能课」（多个组用「、」），顶部 `course-group-filter` 按成员 `objectId` 本地过滤；组加载失败静默留空，不阻断课程页。
- **测试**：`compiler.spec.ts` 覆盖课程组编译、回填、句子名、`missingParts`、同写具体课程与组时不可表示；`RuleSentenceEditor.spec.ts` 覆盖下拉含课程组、只出课程类要求、预览组名、新建并选中课程组；新增 `CoursesView.spec.ts` 覆盖后缀与筛选；`e2e/course-groups.spec.ts` 跑通建组→写规则→落库断言→改成员→课程列表→删组连带规则→回收站。全量 `vitest` 630 条通过，`lint`/`build`/SFC 行数检查通过。
