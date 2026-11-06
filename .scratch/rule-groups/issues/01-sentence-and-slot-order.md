# 01: 规则读成一句话 + 拼装顺序改为 谁→课→时→要求

**What to build:** 规则页上每条规则都按主谓宾显示（如「王老师 周五下午 不上课」「体育 不在最后一节」）；新建/编辑规则时槽位顺序为 谁 → 什么课 → 什么时候 → 怎么样，边选边看到句子成形；强度只显示「必须 / 尽量」两档，不显示分量数字。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] 16 类积木规则的句子都按「主语 + 时间 + 要求」生成，不出现「的 课 在」之类虚词
- [x] 节次能折成上午/下午时显示「上午/下午」，否则显示「第 x、y 节」
- [x] 表单槽位顺序为 谁 → 什么课 → 什么时候 → 要求；缺槽位时提示还差什么
- [x] 选择过程中句子实时更新
- [x] 强度只有「必须 / 尽量」，界面不出现分量数字
- [x] 保存结果与改版前同一组选择编译出的约束条目一致（快照不变）
- [x] 句子渲染有纯函数单测，覆盖 16 类

## 实现说明（2026-11-07）

改了这些文件（只动前端）：

- `frontend/src/rules/compiler.ts`：`SubjectKind`、`PeriodOption.startTime`；`timeLabel` 支持上午/下午/全天折叠；`describeRule` 重写 16 类模板与主语标签，类型未选时也能长出「主语 + 时间 + （请选择要求）」；`describeSentence` / `describeCard` / `cardMatches` 增加可选 `periods`；16 类 `label` 简化为「不上课 / 不排课 / 每天最多 N 节 / 每天最多 1 节 / 连堂 N 节 / 分散到不同天 / 不空堂 / 连续不超过 N 节 / 不在最后一节 / 排在上午 / 不排第一节 / 固定无课日 / 偏好时段」。编译函数、`cardFromInputs`、`groupKey`、落库参数均未改。
- `frontend/src/rules/form.ts`：`FormSentence.subjectKind`（不落库）；`formSentenceFrom` 从 id/type 推断主语；`formSentenceInput` 忽略该字段。
- `frontend/src/components/rules/RuleSentenceEditor.vue`：第一行 `rule-subject`（分组下拉：全校 / 所有教师 / 班级 / 教师 / 全部课程 / 课程），第二行 `rule-day` + `rule-period-N`，第三行 `rule-type`（按主语过滤且禁用未选主语时的下拉）；数量、例外、强度顺序不变；删除 `rule-teacher` / `rule-class` / `rule-course` / `rule-scope`。
- `frontend/src/components/rules/RuleForm.vue`：预览把 `props.periods` 传给 `describeRule`，句子可折叠上午/下午。
- `frontend/src/views/RuleBuilderView.vue`：periods 映射 `startTime`；副标题改为「谁 / 什么课 / 什么时候 / 要求」；列表传入 periods。
- `frontend/src/components/rules/RuleGroupedList.vue`：把 periods 传给句子渲染与搜索。
- 测试：`frontend/src/rules/compiler.spec.ts`（16 类精确句子、折叠、缺 startTime 回退、未选要求的半句、无「的 课 在」）；`frontend/src/components/rules/RuleSentenceEditor.spec.ts`（新增：DOM 顺序、主语选项分组、类型按主语过滤、预览折叠、切换主语清要求、已存规则主语回填）；`frontend/src/components/rules/RuleForm.spec.ts`（改为新顺序与例外/预览）；`frontend/e2e/rule-builder.spec.ts`、`frontend/e2e/rule-builder-full.spec.ts`（改用 `rule-subject` 与新标签/新句子）。

设计与偏差：

- 契约与票面要求「表单顺序 谁 → 什么课 → 什么时候 → 要求」，实现为第一行主语、第二行时间、第三行要求；类型未选时仍显示时间，便于先选时间。
- `describeRule` 增加可选第三参 `periods`（票面写 `describeRule(local, names)`），因为折叠上午/下午需要当天作息；不传时行为与纯节次渲染一致。
- 要求类型的课程占位：`course === 'REQUIRED'` 且没选课程时显示「（请选择课程）」；可选课程继续用 `coursePlaceholder`（全部课程 / 体育 / 同一门课程）。
- 切换主语导致类型不兼容时清空 type/amount/例外；保留已选星期与节次（此时类型为空，时间仍可继续选）。

验收命令与结果：

- `npx vitest run --no-file-parallelism src/rules/compiler.spec.ts src/components/rules/RuleForm.spec.ts src/components/rules/RuleSentenceEditor.spec.ts` → 3 files / 58 tests 全过。
- `npm run lint` → 通过（无 warning）。
- `npm run build` → 通过（vue-tsc + vite build）。
- `VITEST_MAX_WORKERS=4 npx vitest run` → 97 files / 709 tests 全过。
- `bash scripts/e2e.sh rule-builder.spec.ts` → 3 passed。
- `bash scripts/e2e.sh rule-builder-full.spec.ts` → 2 passed。
- `bash scripts/check-sfc-size.sh` → ok（RuleSentenceEditor 526 行，低于 600）。
