# 规则分组 code-review 修复记录

基线 `001f9d2..HEAD`，两轴评审（Standards + Spec）的必修项已修（复制时间组保留、空组诊断、冲突提醒全校/全部课程、下拉组后缀、E2E 作用域、契约批次文案、死代码清理）。

本文件只登记评审中**判断类、本次不改代码**的其余发现。

## 接受（当前实现即合理）

- **删除影响/复制 issue 不含整句**：当前显示「学期 · 类型」，已满足故事「让用户知道哪些规则」的要求；整句摘要会显著增加复制与删除路径的拼装成本。接受现状。
- **复制来源允许未来学期**：服务端只拒绝来源与目标相同；spec 写「往期」，但复制未来学期不会产生错误数据。接受现状。
- **WITH_RULES 非原子撤销**：组与每张规则卡各写一个 `recent_deletion` 批次，用户可分别恢复；已在 `contracts.md` §4/§5 如实记录。接受。

## 后续（重构项，不影响当前行为）

- `useTimeGroups` 与 `useRuleGroups` 的重复：可后续抽共享基类/工厂。
- `ConstraintConfigService` 已约 2000 行、职责偏多：后续按「校验 / 查询诊断 / 版本与审计」拆分。
- `RuleGroupType` 的 switch 出现在多处：后续可引入枚举行为（每个类型自带成员键、标签、校验）。
- `SlotKey` 字符串（`day-period`）在前后端重复拼接：后续统一常量或工具函数。

## Hark 复审（2026-11-07）

**结论：修完再合并。无 P0，3 个 P1。**

本次已修：

1. **P1-1 停用卡阻塞后续保存**：复制的「本学期用不上」卡（如引用了目标学期没有的第 9 节）会在下一次任意保存时被后端严格校验拒绝。修法：`ConstraintConfigService.planEntries` 按「配置启用 && 行启用」计算 `strict`；非 strict 行保留原始 params，只做 id 归属/重复检查，跳过 `validateParams`、`validateTimetableConditions`、`referencesOf`，并让 `rejectInconsistentGroups` / `rejectMixedClassScope` / `rejectIncompleteClassSlotGroup` / `rejectContradictoryScalars` / `rejectDuplicateThresholdScopes` 只看启用行。`CopyRulesIntegrationTest` 新增「复制停用卡 → 再保存普通规则 200 → GET 仍保留停用卡 → 改回启用保存 400」。
2. **P1-2 阈值上限重叠的生效顺序不定**：分组展开后同一对象可能同时命中单人/组/全局多条阈值，旧实现按条目 id 先到先得。修法：`RuleGroupExpander` 给展开行加内部标记 `ConstraintSettings.GROUP_EXPANDED`（只存在于快照，不落库）；`ConstraintSettings.thresholdFor` 统一选值：**单人具体规则 > 组规则 > 全局默认；同一层级取更小的上限（更严格），同值 hard 优先，再保持先出现**。`limitOrNull` 与 `thresholdControl` 读同一个选择，数字与强度不会来自不同行。新增 `ConstraintSettingsThresholdPrecedenceTest` 5 例。
3. **P1-3 组数量句读成整组合计**：`describeRule` 对课程组/教师组补「每门 / 每人」，如「技能课 每门每天最多 1 节」；单人/单课与全局默认句子不变。同步更新 `compiler.spec.ts`、`RuleSentenceEditor.spec.ts`、`e2e/course-groups.spec.ts` 与票 05 的字面。

另修 P2-6：**V56 没有推进 `constraint_config_state.version`**，部署时仍打开的旧规则页会带旧 baseVersion 把旧规则写回来。新增 `V59__bump_constraint_version_after_reset.sql` 执行 `version = version + 1`；不改已跑过的 V56（避免校验和不匹配）。

未修（标注后续）：复制接口的 `baseVersion` 必填、回收站孤儿引用清理、实际空组提示、规则冲突检查的误报/漏报、未保存编辑的边界、空组提示文案统一、`constraint_entry` 等表的 `ON DELETE` 外键策略。

