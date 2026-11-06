# 07: 保存时提醒组规则互相卡住

**What to build:** 保存规则时，系统把分组展开后检查：同一个老师、课程或课节如果同时被两条「必须」规则要求「不排」和「排在」同一时段，就提醒「张老师被这两条规则卡住了」，并列出两条规则和受影响成员。只提醒，不阻止保存，也不替用户选。

**Blocked by:** 06

**Status:** done

- [x] 展开后的冲突能被发现（教师组、课程组、时间组均覆盖）
- [x] 提示写明两条规则的句子和受影响成员
- [x] 用户可选择继续保存或返回修改
- [x] 「尽量」规则之间不提示

**Notes:** 保存前的最后一道提醒，只读不写；服务端不做这项拦截。

## 实现说明（票 07）

- **纯函数**：新增 `frontend/src/rules/conflicts.ts`。`findRuleConflicts(cards, context)` 先把对象（教师 / 课程 / 班级）与槽位（星期 + 第几节）展开，用 `kind:id|day|period` 建负向 / 正向两张 Map，再按「同对象 + 同时段」求交集；同一对句子的多个成员 / 时段合并成一条，`members`、`slots` 去重。
- **只比必须规则**：`sentence.strength === 'HARD'`，且 `RULE_SPECS[type].hardOnly` 为 true 的句子即使数据里是 SOFT 也按 HARD 处理；负向 / 正向任一为「尽量」都不提示。停用的卡片和句子不参与。
- **方向与特殊展开**：
  - 负向：`CLASS_UNAVAILABLE`（具体班级或全校 × 槽位）、`TEACHER_UNAVAILABLE`、`COURSE_UNAVAILABLE`、`TEACHER_NO_FIRST_PERIOD`（每天最早一节）、`TEACHER_DAY_OFF`（选中星期的全部节次）、`PE_LAST_PERIOD_AVOID`（每天最后一节）。
  - 正向：`TEACHER_PREFERRED_SLOT`、`MAIN_SUBJECT_MORNING`（每天 `periodNo <= beforePeriodNo`）。
  - 组引用：教师组 / 课程组展开成成员，时间组展开成坐标；具体 id 优先于组；组不存在或成员为空时该句不产生对象 / 槽位。展开结果再按本学期 `periods` 过滤。
  - 数量型、形态型、不空堂不参与。
- **保存接线**：`RuleBuilderView.save()` 在影响预览通过（没有硬错误）之后、`http.put` 之前调用检查；有冲突时 `ElMessageBox.confirm` 列出「句子一 / 与 / 句子二 / 受影响成员 / 时段」（最多 5 条，其余显示「等 N 条」），按钮「仍然保存」「返回修改」。取消直接返回、不写数据；确认后照常 PUT。
- **context 构建**：`buildConflictContext` 放在 `conflicts.ts`（纯函数、不碰 HTTP），页面只传 `periods / names / allClassIds` 与三个组列表；`RuleBuilderView.vue` 因此保持在 600 行 SFC 上限内（579 行）。
- **测试**：新增 `conflicts.spec.ts` 12 条（教师组 × 时间组、课程组 × 主科上午、软规则不提示、时段不重叠、固定无课日、不排第一节、体育最后一节、空组不误报、停用卡片、文案格式与条数、context 构建）；`RuleBuilderView.spec.ts` 新增 1 条保存提醒用例（取消不 PUT、确认后 PUT 一次），原有 9 条保持通过。
- **验证**：定向 vitest（conflicts + RuleBuilderView）通过；全量 vitest 86 文件 / 672 条，退出码 0；`npm run lint`、`npm run build` 通过；`scripts/check-sfc-size.sh`、`scripts/check-ticket-status.sh` 通过；E2E `rule-builder.spec.ts` 4 条通过。
