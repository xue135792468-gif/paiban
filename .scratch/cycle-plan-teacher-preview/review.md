# 教师课表预览：固定版本代码复查

固定基点 c27af84f449df5d572d6f2bdd9757799652e43c9，首轮候选 0e625e9d7db4706bbdda4c90996a259927772e18。Standards 与 Spec 独立只读，均未运行测试或浏览器。主控不做前端验收，不在本轮评价模型能力。

## Standards

## Standards 轴（base c27af84 → candidate 0e625e9）

**Hard 1｜新交互无 Playwright 用例**
`CODING_STANDARDS.md:38`：「用户可见的新交互随票提供 Playwright 用例；组件单测与后端集成测试不能替代。」本票新增点教师姓名展开预览、应用后/当前安排切换、窄屏全屏返回等公开交互，`frontend/e2e` 只有 `draft-drag.spec.ts:341,376` 两处旧选择器迁移，`git grep` 在 e2e 目录内找不到 `cycle-plan-teacher`/`teacher-preview` 任何引用。确认问题，与"未实跑"是两回事。

**Hard 2｜预览投影口径与后端 apply 校验分叉**
`CODING_STANDARDS.md:56` 要求同一规则前后端各自抽纯函数并断言同一行为，「避免『预演说排除、落库却写入』」。前端 `cyclePlanPreview.ts:90-101` 逐条降级（命中项照常渲染"应用后"，`CyclePlanTeacherPreview.vue:77` 报"已投影 N 步"）；后端 `DraftCyclePlanService.java:230-236` 任一 move 不符即整单 409，`:283-284` 的指纹还包含 `lesson.locked()`，`:220` 一处变化即整单失效。差异有两处：预览不检查锁定；预览呈现"部分可应用"，真实 apply 必然整体拒绝。后端 `DraftCyclePlanIntegrationTest.java:226,247` 钉死了整单失效语义，前端没有对应一条。确认问题。

**Judgement（smell baseline）**
- Duplicated Code：`CyclePlanPanel.vue:169-172` 的 `teacherNameOf` 与 `cyclePlanPreview.ts:61-63` 同形；`cyclePlanPreview.ts:47-50` 新建第三份 `DAY_NAMES`/`dayName`，与 `DraftGrid.vue:237-238`、`swapPreview.ts:19` 并存。
- Duplicated Code + 未知风险：`CyclePlanPanel.vue:187-194`（按 `move.teacherId` 去重）与 `cyclePlanPreview.ts:105-106`（按草稿 `lesson.teacherId` 优先）两份教师列表，mismatch 时口径分歧，可能让切方案候选与 chips 不一致。
- Mysterious Name：`CyclePlanPanel.spec.ts:107,230,263,278,289` 断言 `.move-item`/`.on`/`.active`/`preview-full` 等 class 名，属私有实现；规格「避免私有实现断言」未被 CODING_STANDARDS 强制，故不阻塞。

**已核对兼容，无问题**
`DraftGrid.vue:144-166` 两个新 props 默认 `{}`，`:68,74` 均有 `v-if` 守卫，四个未传参调用方（`TimetableEditorView/DraftEditorView.vue:77`、`DualTimetablePreview.vue:36,52`、`TimetablePreviewGrid.vue:37`）渲染不变；`apply`/`cancel` 事件签名保持（`CyclePlanPanel.vue:73`）；`DraftGrid.spec.ts:78-86` 钉住"空格才写移走说明"。`CODING_STANDARDS.md:57` 计数同源、`:51` 读接口默认值未触达。`CyclePlanPanel.vue:145-292` 组件内五个 ref 属既有惯例，不算 `:55` 违反。

## Spec 原始报告

已按固定对象只读完成 Spec 轴复审，未跑测试/构建/网络，未改文件。资源起步 7.64 GB 可用。

**结论**：缺失 0 项，范围扩张 1 项（低风险、已在交付记录披露），确认逻辑问题 1 项，另有 2 项未知风险待前端验收观察。

**六项逐条对照**（规格 `.scratch/cycle-plan-teacher-preview/implementation.md:9-14`）

- 行为 1 教师姓名可点、跨行政班完整课表：已实现。链接在 `frontend/src/components/draft/CyclePlanPanel.vue:53`，过滤只按 `teacherId`（`frontend/src/components/draft/cyclePlanPreview.ts:152-154`），预览带 `show-class-name`（`CyclePlanTeacherPreview.vue:54`）。
- 行为 2 并排 / 手机全屏：**并排与全屏切换实现存在**，但手机返回保留滚动位置这一条有确认问题（见下）。
- 行为 3 默认 after、明示未生效、可切当前安排：`CyclePlanPanel.vue:148` 与 `CyclePlanTeacherPreview.vue:107` 默认 `after`，提示文案 `:41-43`。
- 行为 4 整组投影不丢课、不改输入：`projectCyclePlan` 每步都对同一份草稿判定（`cyclePlanPreview.ts:84-103`），`applyProjectionToLessons` 用 `map`+展开产出新对象（`:138-148`），原数组与方案对象未被写入；互换两侧同时命中，`cyclePlanPreview.spec.ts:77-101` 覆盖。同教师多课、同名不同 ID 分别由 `:103-128` 覆盖。
- 行为 5 按 ID 切换、切方案不累积：chips 以 `teacherId` 为键（`CyclePlanTeacherPreview.vue:27-32`）；投影是 `activePlan` 派生，`selectPlan` 每次重置 `after` 并校验教师归属（`CyclePlanPanel.vue:200-210`），结构上无法叠加上一方案。
- 行为 6 只读 + 沿用原 apply/cancel：预览 `:draggable="false"`（`CyclePlanTeacherPreview.vue:52`），新增 DraftGrid 属性只出文字、无写操作；apply 仍是 `cycle-plan-apply` 走 `DraftEditorView.vue` 原确认与真实接口；预览打开时底部按钮先返回，再点才 `cancel`，原流程未删（`CyclePlanPanel.vue:236-242`）。

**确认问题（1）手机返回不保留列表滚动位置**
`CyclePlanPanel.vue:218` 从 `listPane` 读 `scrollTop`、`:230` 写回，但同文件媒体查询把 `.list-pane` 改为 `max-height: none`（`:413-416`），该元素在窄屏下不再滚动（外层 dialog 才是滚动容器），存回的恒为 0；`list-pane` 在窄屏是 `display: none` 隐藏（`:418`），浏览器本身也会丢弃其偏移。规格第 10 行明确要求「返回保留方案及滚动位置」。`CyclePlanPanel.spec.ts:268-293` 用 `defineProperty` 伪造 `scrollTop`（`:272-273`），只证明代码按同一假设自洽，不构成实机证据。

**未知风险（2，非缺陷断言）**
- `cyclePlanPreview.ts:105` 在教师 ID 与草稿不一致时按草稿 ID 归组，而卡片链接传的是方案 ID（`CyclePlanPanel.vue:53`），理论上可打开空预览并出现方案未涉及的教师 chip。我未找到可达路径：弹窗模态挡住编辑，且服务端建 move 时取自草稿快照的 `lesson.teacherId()`（`backend/.../DraftCyclePlanService.java:324`）。
- `CyclePlanTeacherPreview.vue:159-169` 的空格 occupancy 用全量投影判断，而格子只渲染本教师的课：某格被其他教师的课填入时，本视图该格既无课也无去向说明。

**范围扩张（1，已披露）**
`DraftGrid.vue` 新增 `lessonNotes`、`vacatedPeriods`（默认 `{}`，调用方不受影响）超出规格第 20 行「优先只改」清单，交付记录 `implementation.md:39` 已说明；`e2e/draft-drag.spec.ts:341,376` 仅迁移选择器，业务断言保留，符合第 24 行。后端 DTO 无换教师字段，投影即「节次移动」，与 apply 语义一致，无需新增接口。

未跑浏览器、Playwright 与后端套件，不声称通过；手机路径我只核对实现存在与逻辑正确性，非实机验证；UI 观感留给你验收，上述滚动问题建议一并实测。

## 主控处置（保留原轴计数，不改写原报告）

- 手机滚动：确认代码保存的 listPane 在窄屏没有实际滚动约束，交 Qwen 修正滚动容器及状态恢复。组件回归不能冒称实机证明。
- vacatedPeriods 的全草稿 occupancy：由主控将原未知风险确认成需要修正的投影显示错误。具体反例：教师 A 移走某课后其原格空闲，教师 B 在其他行政班同节上课；B 的占用不能阻止 A 的空格显示移走说明。交 Qwen 改用当前教师投影课表并补公开组件回归。
- 教师元数据不一致：尚无真实可达反例，不扩展产品规则或再加接口。保留未知边界，非阻塞问题。
- DraftGrid 可选只读属性：不采纳范围扩张判断。规格的“优先只改”是优先范围，且明确要求复用 DraftGrid；为该预览增加默认空的显示属性符合已确认功能，未增加业务能力。E2E 选择器迁移也属必要兼容维护。

Standards 两个确认点一并交同一整改者：补已有 Playwright 场景中的新交互回归（不执行浏览器验收）；本地已知输入不能完整投影时整组停止，明确只显示当前课表，不呈现部分应用后。校验限必要字段及移动课锁定，不复制服务端版本、权限或全局指纹规则，服务端继续最终判定。

Standards 的少量重复姓名/星期文案、类选择器等判断建议本轮不作阻塞，不引入全局字典或通用校验框架；原轴报告及数量保留。所有确认点在同一轮收口，后续只核相关增量及必要代码检查，不重审既有业务。

## 模型接续事实

旧施工代理恢复后，宿主元数据变成 gpt-6.1-sol/medium；主控在发现时关闭，git status 确认无新增源码改动。两项整改重新指定 group/qwen3.8/max 的 Dalton（01a1db3b-e334-77f3-96c1-5b9e7016053a）。这属于模型路由/恢复行为的记录，不将其作为 Qwen 能力结论。

## 整改收口

同一 Qwen 整改者完成四项确认点，主控只核读相关增量，不做浏览器验收、不重新开启整项目审查：

- 滚动容器：窄屏 list-pane 保留 overflow-y:auto，明确 max-height 85dvh - 170px（vh 兜底），保存与恢复仍指向同一容器。位置状态与容器接线有组件回归；手机实际视觉/滚动留给用户体验。
- 移走说明：occupied 从 teacherLessons 建立，其他教师在同槽上课不会抹掉当前教师的说明；本教师另课移入时仍不显示虚假空格。
- 原子显示：新增 available；任一步本地已知输入失配或移动课锁定，则所有步骤都不投影，显示当前课表且禁用应用后选项。没有复制服务端全量校验，原 apply 链路不变。
- Playwright 用例：既有 draft-drag 场景新增真实交互路径，未在本轮执行，不冒称 E2E 通过。

最终真实检查：定向 Vitest 49 通过；前端全量 107 文件、826 单测通过；lint、build、tsconfig.app/tsconfig 类型检查均退出 0；SFC/helper 检查通过。未再发现需要本轮扩改的确认问题。保留原轴数量，不把修复后的主控增量核对写成第二轮独立双轴结论，也不将未实跑的手机/Playwright 标为完成。

