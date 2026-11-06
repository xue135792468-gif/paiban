# 02: 积木补齐：一句选多天、只管某个班

**What to build:** 用积木可以写出「周一、周三 下午 王老师 不上课」这类跨多天的句子，也可以写出只管某一个班的句子（数量型、形态型等原来只能在「按类型配置」里写的组合）。目标：16 类积木类型的全部参数组合都能用一句话表达，包括「所有教师 / 全校班级 / 全部课程」这类全局默认范围，这样票 03b 把 AI 建议转成规则卡时不会丢语义。

**Blocked by:** 01

**Status:** done

- [x] 「什么时候」可多选星期和节次，句子正确显示多天（如「周一、周三下午」）
- [x] 「对谁」可选单个班级的规则类型都能在积木里写出
- [x] 全局默认范围可用一句话表达：所有教师（`teacherId` 留空）、全校班级（`classId` 留空）、全部课程（`courseId` 留空）
- [x] 多天只改前端句子模型与编译/回填；持久化仍是「一行一个槽位、同一 `groupKey` 合并」，不改数据库
- [x] 保存后草稿校验立即按新规则生效
- [x] 规则页不再出现「还有 N 条本页未覆盖的规则」提示（新数据下恒为 0）
- [x] 编译结果走现有约束条目，不新增评估器
- [x] 按 `contracts.md` 第 3 节实现；分组引用字段本票只预留，不接线

## 实现说明（2026-11-07）

只改前端，未动后端、数据库、票 03 及以后的票。

### 数据模型

- `RuleInput.dayOfWeek` → `days?: number[]`；`RuleSentence` / `FormSentence` 的 `dayOfWeek` → `days: number[]`，并新增 `teacherGroupId` / `courseGroupId` / `timeGroupId`（本票一律 null，留给票 04/05/06 接线）。
- 新增 `normalizeDays`（去重升序），与 `normalizePeriods` 一起保证句子和落库顺序稳定。
- 持久化参数仍是每行 `dayOfWeek` + `periodNo`，未改后端字段或表结构。

### 多天与固定无课日

- `timeLabel(days, periodNos, periods)`：`days` 为空显示「（请选择星期）」；`periodNos` 为空只显示星期列表（固定无课日）；所有选中星期的上午/下午/全天切片一致且节次恰好等于该切片时才折叠，否则写「周一、周三第 5、6 节」。
- 编译：槽位型按 `days × periodNos` 生成行，同一 `groupKey`；`entryIds` 键仍是 `${day}-${periodNo}`，例外班级加 `${classId}:` 前缀。
- 固定无课日（`TEACHER_DAY_OFF`）改为每个星期一行、同一 `groupKey`，`entryIds` 键 `day-${day}`；回填把同组多行合并成一句「王老师 周一、周三 不排课」。单天数据仍兼容。
- 回填：`slotSentence` 允许 `days.size >= 1`，并要求同组槽位恰好是「选中星期 × 选中节次」的笛卡尔积；缺组合（例如周一第 1 节 + 周三第 2 节）判为不可表达。例外句把「天 + 节次」一起比较，所有班级的槽位集合必须完全一致。
- 类型未选时，只要已选星期就继续长句子；`missingParts` / `sentenceProblem` 按 `days.length === 0` 提示「选择星期」。
- 表单：`rule-day` 改多选 `rule-days`；节次选项取所有选中星期的交集，切换星期时把已选节次筛到交集；`TEACHER_DAY_OFF` 仍只显示星期、不显示节次。

### 全局默认范围

- `TEACHER_MAX_PER_DAY` 加 `allowAnyTeacher: true` → 不选教师时句子为「所有教师 每天最多 N 节」。
- `CLASS_MAX_PER_DAY` 加 `allowSchool: true` → 不选班级时句子为「全校 每天最多 N 节」（全页沿用「全校」）。
- `COURSE_MAX_PER_DAY` 改为 `course: 'OPTIONAL'`、`coursePlaceholder: '全部课程'` → 「全部课程 每天最多 N 节」。
- 保存时这些类型仍是单值条目，`objectParams` 只在对象非空时写对象键，不会出现 `teacherId: null` / `classId: null` / `courseId: null`。

### 测试

- `compiler.spec.ts`：全部输入改 `days`；新增多天编译（2 天 × 2 节 = 4 行、同一 `groupKey`）、固定无课日多天（2 行 → 一句）、三种全局默认句子与参数、多天/多天固定无课日/全局默认的回填、`unrepresentedGroupCount` 对可表达形态返回 0、对缺组合返回 1。
- `RuleSentenceEditor.spec.ts`：`rule-days` 多选、多天句子、`TEACHER_DAY_OFF` 不显示节次、`all-teachers` / `school` / `all-courses` 的类型列表加入「每天最多 N 节」，以及班级主语下同时提供「不上课」与「每天最多 N 节」。
- `RuleForm.spec.ts` / `RuleCardItem.spec.ts`：同步 `days` 与预留分组字段。
- E2E：`rule-builder.spec.ts` 增加「王老师 周一、周三第 5、6、7、8 节 不上课」，断言保存后 8 行（2 天 × 4 节）、同一 `groupKey`、刷新后仍是一句；`rule-builder-full.spec.ts` 的星期选择改多选。

### 验收命令与结果

- `npx vitest run --no-file-parallelism src/rules/compiler.spec.ts src/components/rules/RuleForm.spec.ts src/components/rules/RuleSentenceEditor.spec.ts` → 3 files / 68 tests 全过。
- 另跑 `src/components/rules/RuleCardItem.spec.ts` 与 `src/views/RuleBuilderView.spec.ts` → 2 files / 10 tests 全过。
- `npm run lint` → 通过（无 warning）。
- `npm run build` → 通过（vue-tsc + vite build）。
- `bash scripts/e2e.sh rule-builder.spec.ts` → 4 passed。
- `bash scripts/e2e.sh rule-builder-full.spec.ts` → 2 passed。
- `bash scripts/check-sfc-size.sh` → ok（RuleSentenceEditor 538 行，低于 600）。

### 偏差

- 票 01 在「已选星期但没选节次」时显示「（还没选节次）」；本票按方案改为只显示星期列表（固定无课日需要这个形态）。
- E2E 种子的默认作息没有下午 `startTime`，多天用例实际显示「周一、周三第 5、6、7、8 节」而不是折叠成「下午」；单测已覆盖有作息时的「周一、周三下午」。
- `RuleBuilderView.vue` 的「还有 N 条本页未覆盖的规则」提示保留条件渲染（新数据下计数为 0，不再出现），未删除该文案。

