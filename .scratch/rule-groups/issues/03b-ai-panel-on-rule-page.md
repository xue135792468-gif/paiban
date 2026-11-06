# 03b: AI 规则面板搬到规则页

**What to build:** 高级页下线后，把「用自然语言帮填表」的只读建议面板搬到规则页（可折叠），保留生成、建议列表、历史、审计与「加入到规则」的能力。选中的建议转换成规则卡/句子加入当前编辑中的规则，保存后仍落成现有约束条目；转换不了的条目要写明原因，不能静默丢弃。后端接口、模型调用与审计不动。

**Blocked by:** 03（页面腾出位置）、02（句子模型支持全局默认范围）

**Status:** done

- [x] 规则页出现 AI 建议面板；AI 未配置/关闭时显示原因，而不是报错或隐藏能力
- [x] 生成、取消等待、历史、审计、只读提示与高级页原有行为一致
- [x] 选中的建议能转换成规则卡并显示正确句子；同一张卡最多 3 句的上限仍生效
- [x] 建议落到 `constraint_entry` 后与直接手写同样规则的结果一致（同一组参数、同一强度）
- [x] 转换不了的参数组合（若仍存在）显示具体原因，不写入、不静默丢弃
- [x] 规则页保存时沿用现有版本冲突与审计链路，不新建写入通道
- [x] 前端单测覆盖「建议 → 规则卡」的纯函数转换；E2E 覆盖一条生成并加入的主路径（AI 可 mock 或走既有测试替身）
- [x] `ConstraintsView` 删除后不再有对 `ConstraintAiPanel` 旧 props/工作区状态的依赖

**Notes:** 产品所有者 2026-11-07 决定保留 AI 面板并搬到规则页。协作提醒/跨设备工作区状态属于高级页草稿的旧机制，随高级页删除；AI 面板自身的历史与审计保留。

## 实现说明

### 转换算法（`frontend/src/rules/proposalToCards.ts`）

`cardsFromProposal(proposals, { allClassIds })` 是纯函数，返回 `{ cards, decisions, rejected }`。

1. `status !== 'READY'` → 拒绝，原因用 `proposal.reason || '建议未就绪'`。
2. `type` 不在 `RULE_TYPES`（16 类）→ 拒绝：`「类型名」暂不支持在规则页添加`。
3. 按类型分组成「一句 = 一张卡」：
   - `CLASS_GAP_MINIMIZED`（开关型）：一组空 entries。
   - `CLASS_UNAVAILABLE`：全空 `classId` 或只有一个班 → 一组原样；多个班且恰好覆盖 `allClassIds` 全部 → 去掉 `classId` 作为全校一组；多个班但只覆盖部分 → 保留 `classId`，由编译器推断「除了这些班」；空/非空混用 → 拒绝（会静默扩大成全校）。
   - 槽位型 / 固定无课日（`periods === 'REQUIRED'` 或 `day === 'REQUIRED'`）：按对象字段（`teacherId`/`courseId`/`classId`）分组，每个对象一组。
   - 单值型（数量/形态/预设）：每条 entry 单独一组。
   - 非开关型但 entries 为空 → 拒绝：`「类型名」这条建议没有可转换的条件`。
4. 每组构造一个伪 `ConstraintConfigDto`（`groupKey = <proposalId>:<groupIndex>`，同组所有行相同），交给现有的 `cardsFromState([config], { allClassIds })`。返回空 → 拒绝：`「类型名」这条建议在规则页无法表达`。
5. 读出的卡再做一次 `adoptCard`：新 `cardId`、句子 `key/groupKey` 重写为 `<cardId>:<index>`、`entryIds` 清空，因此加入的是未保存的新卡，不会覆盖已存条目。
6. 每个成功 proposal 生成一条 `decisions`（`action: 'ADDED'`，`entries` 用模型原始行，不是改写后的分组）。

### 状态与审计链路

- `frontend/src/rules/useRuleAi.ts` 持有 `showAi` / `aiRunId` / `aiDecisions` / `aiNotice` 与目录到 `TypeDraft` 的映射。
- 同一 `runId` 再次加入 → decisions 追加；换一个 run → 整体替换（后端只接受一个 runId）。
- `save()` 的请求体为 `{ configs, ...(runId + decisions) }`，仍走原 `PUT /constraints`、`baseVersion` 与 409 冲突提示。
- 保存成功后的 `loadState()` 与版本冲突重载都会 `resetAi()`，清空 run/decisions/notice，避免旧 run 的决策带到新配置。
- 面板本身未改逻辑：生成、取消等待、历史、审计、只读提示原样保留。

### 测试

- `proposalToCards.spec.ts`：10 条，覆盖教师 4 槽位、两个教师两张卡、全校、部分班级「除了」、全局教师数量、开关型、不支持类型、空 entries、待补齐、decisions 内容。
- `RuleBuilderView.spec.ts`：补 availability mock 与「工具栏开关面板」用例，原有 8 条保持。
- `rule-ai-proposals.spec.ts`：2 条主路径（生成不写入 / 加入并保存），断言 4 行同一 `groupKey` 与审计 `runId` + `decisions[0].action === 'ADDED'`。
- 验收结果：定向单测 29 passed；全量单测 79 files / 603 tests passed；`npm run lint`、`npm run build`、`check-sfc-size.sh`、两条 E2E spec 全部退出码 0。

### 与方案不一致处

- 方案第 4 步示例把 `groupKey` 写成 `<proposalId>:<groupIndex>:<i>`，但同一段又要求「同组所有 entry 的 groupKey 必须相同」；两者冲突。按后者与「每组最终生成一张卡」的意图实现为 `<proposalId>:<groupIndex>`。
- 为通过 `scripts/check-sfc-size.sh`（600 行上限），把视图里的 AI 状态/转换接线抽到 `frontend/src/rules/useRuleAi.ts`；`RuleBuilderView.vue` 保持 589 行，行为与方案一致。
