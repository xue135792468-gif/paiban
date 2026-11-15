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

## 2026-11-15 最终增量双轴复审

固定基点仍为 `c27af84f449df5d572d6f2bdd9757799652e43c9`，最终源码候选 `0502c137fe7997c4fd6f40b698c4ab6b5d6f8488`；比较 `git diff c27af84...HEAD`，两次提交为 `0e625e9`、`0502c13`。本轮只核最终候选与四项整改，不打开其他历史功能。两轴独立只读，不重跑前端套件。

### Standards（worker-backup，路由 `magpie/dumate/qwen38-flash`）

- **硬性违反 0，判断建议 5，不阻塞发布。** 窄屏保存/恢复同一滚动容器；本教师占用与 DraftGrid 空格说明口径一致；任一失配/锁定整组不投影；新公开交互已有真实 Playwright 路径；新/改 SFC 均未超过 600 行。
- 判断建议保留：教师姓名回退与方案类型文案的小份重复、`teacherMismatch` 等未消费字段、E2E 写请求监听的小份重复、旧 `unmatched` 测试标识命名。均为可维护性观察，不是已证实业务错误；沿用上一轮决定，不在本票另建抽象或扩大重构。

### Spec（worker，路由 `workbuddy-local/cn:deepseek-v4.1-flash`）

- **缺失/部分 0，范围扩张 0，逻辑错误 0，不阻塞发布。** 六项确认行为、四项整改及原 apply/cancel 接口均与最终代码对应。
- 非阻塞文档问题：`manual-adjust.md` 仍写「某一步单独未投影」，与最终整组拒绝的旧口径不一致。主控确认后已仅修改该说明为整组不投影；不改变产品代码。

### 主控结论

两轴均无发布阻塞项；只落实一处帮助文档同步。所谓 0 问题限于本次增量复审范围，不把首轮流動、非阻塞判断建议或未覆盖的真实设备写成已全部消除。完整冻结门禁另行记录，不由本复审代替。

## PR CI 首轮失败与一行测试整改

PR #32 首次 run `43676022144`：backend、frontend、deploy-scripts 通过，e2e 失败。失败不是教师预览用例；本功能场景在 CI 通过。唯一失败是 `lifecycle.spec.ts:73` 全页查找「新建授课任务」时命中顶部工具栏与空状态卡两个按钮，Playwright strict mode 拒绝点击。该文件和产品页面均不在原功能 diff 中；同文件本地原样运行 2/2 通过，说明有加载时序差异，不能据此声称原断言必然失败。

主控按 `CODING_STANDARDS.md:35` 的容器作用域要求，仅把按钮定位限定到 `.toolbar`；教师选择器仍原样断言已停用教师不可选，不新增等待、不放宽断言、不跳过、不改业务代码。修正后 `lifecycle.spec.ts --repeat-each=2` 实际 4/4 通过。原 CI 失败保留，不作为原功能门禁通过证据；整改后的冻结门禁另存新收据。
