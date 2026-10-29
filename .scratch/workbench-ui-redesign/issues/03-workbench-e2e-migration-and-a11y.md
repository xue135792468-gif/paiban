# 03: 既有 e2e 迁移 + 无障碍/320px + 帮助文案 + 全量门禁

**What to build:** 把依赖旧工作台结构的既有 e2e 全量迁移到新界面（保持同等行为覆盖），补键盘可达与 320px 断言，更新帮助文档中过时的界面描述，跑定向与全量门禁并记录证据。

**Blocked by:** 01、02

**Status:** done (2026-10-30, 集成分支 feature/workbench-ui-redesign；门禁后端 1012 / 前端 438 / Playwright 137 全绿)

## 需要迁移的 spec（覆盖不可缩水）

| 文件 | 必须保留的行为覆盖 |
| --- | --- |
| `frontend/e2e/admin-substitute.spec.ts` | 管理员选教师→指定代课→左右对照→提交审批/亲自确认 |
| `frontend/e2e/admin-substitute-revision.spec.ts` | 已生效代课的 B→C 修订与恢复基线 |
| `frontend/e2e/admin-swap.spec.ts` | 同班调课：目标日期/节次、候选、整单确认、表单等价 |
| `frontend/e2e/admin-swap-drag.spec.ts` | 拖拽有效/无效目标、取消零变化、表单与拖拽等价 |
| `frontend/e2e/admin-reschedule.spec.ts` | 已调课实例再次调整、旧环已开课冻结 |

迁移原则：
- 优先沿用原有 `data-testid`；新界面需为关键元素补稳定 testid（步骤条、动作卡、切换链接、日期块、目标节次、变化格、提交/确认按钮）。
- 不得删减断言强度；只允许改选择器与步骤路径。原有用例里依赖「同页纵向滚动」的假设要按新向导结构重写。
- 拖拽用例沿用可重试写法（交互零副作用，`expect(...).toPass()` + pointerdown 渲染节拍）。

## 无障碍与移动

- 步骤条、步骤切换有 `aria-live` 播报；日期块 `aria-pressed`、动作卡 `aria-pressed`、跨科勾选有可读 label；键盘可完成：选课 → 下一步 → 选动作 → 选老师/日期节次 → 下一步 → 勾选 → 提交。
- 320px：导航隐藏、步骤1 与变化可视化切按天列表、对照/明细上下堆叠、页面无横向滚动（`scrollWidth <= clientWidth + 1`）。

## 帮助文案

- 更新 `frontend/src/help/docs/substitution-workbench.md`（及 `docs.ts` 相关链接如需）：删除「发起调整」等旧按钮描述，改为三步向导、切换入口位置、变化可视化与窗口日期块的说明；保持锚点与既有帮助测试通过。

## 验收清单

- [x] 五个 spec 全部迁移完成，行为覆盖与迁移前等价（逐条对照旧断言记录在交付记录里）。
- [x] 新增键盘可达用例（仅键盘完成一次代课确认）与 320px 用例（步骤1 列表、变化可视化按天列表、无横向溢出）。
- [x] 帮助文档与新界面一致，帮助中心测试（含锚点）全绿。
- [x] 定向 e2e 连跑两遍稳定；`npm run lint && npm run build && npm run test:unit` 全绿。
- [x] 全量门禁 `bash scripts/verify.sh --full` 退出码 0（重定向日志取退出码），记录后端/前端/Playwright 数字与耗时。
- [x] 交付记录注明：无后端/数据库改动；未部署、未触碰生产数据。

## 实现说明（2026-10-30）

**做了什么**：
- 五个既有 spec 原地迁移到向导结构：`data-testid` 优先沿用，新界面补齐 `wizard-stepper`、`wizard-next-1/2`、`pick-lesson`、`substitute-option-*`、`confirm-*`、`change-visual` 等稳定 testid；依赖「同页纵向滚动」的用例按向导分步重写。另改 `global-scroll.spec.ts`（新增「工作台独立滚动」：内容在 `.wb-scroll` 内滚、窗口/主内容/导航不动）与 `t09-acceptance.spec.ts`（改走三步向导断言）；`helpers.ts` 抽出共享 `seedWorkbenchScenario` / `pickOption`。
- **旧断言逐条对照（迁移前 → 迁移后标题一一对应，断言强度不缩水）**：admin-substitute 3 条、admin-substitute-revision 2 条、admin-swap 2 条、admin-swap-drag 4 条（无效目标覆盖由「空格与窗口外」扩为「空格、来源日之前与跨班」）、admin-reschedule 3 条，共 14 条等价迁移，无删减。
- 新增 `workbench-a11y.spec.ts`：仅键盘（focus + Enter/Space，全程不用鼠标点击）完成一次跨科代课确认到成功态；aria 断言（步骤条 aria-live 文本随步骤更新、课卡/动作卡/日期 chip 的 aria-pressed、跨科勾选可读 label）；320×700 导航隐藏、步骤 1 与变化可视化按天列表、无横向滚动。
- 帮助文档 `frontend/src/help/docs/substitution-workbench.md` 重写为新界面描述（三步向导、动作卡与切换链接位置、窗口日期块 7 天语义、迷你周表/图例/文字明细、方案页签、跨科门、保留时间），锚点保留，帮助中心测试全绿。
- `MainLayout.vue` 补窄屏壳层规则：≤900px 隐藏左侧导航（320px 验收依赖）、≤520px 收敛壳层留白防横向滚动。

**关键取舍**：
- **390px 与 320px 分工**：两者落在同一 ≤520 CSS 断点，e2e 各挑一个代表宽度——390px 做「常规手机」交互验收（按天列表可走完流程，截图 t01-02 / t02-08），320px 做「极限宽度」验收（`scrollWidth <= clientWidth + 1`、导航隐藏，320×700），避免重复跑两条近似链路。
- 「定向 e2e 连跑两遍」按 8 个工作台相关 spec（3 新 + 5 迁移）整批连跑两轮取证，比逐 spec 重跑更贴近真实回归场景。

**证据**：
- 定向连跑两遍：8 个 spec 共 33 条用例，两轮均 33 passed、退出码 0（6.0m / 6.1m）。
- 全量门禁 `bash scripts/verify.sh --full`（带 `VITEST_MAX_WORKERS=4` 限流本机 vitest 并发，重定向 `/tmp/verify.log` 取退出码）：**退出码 0**。后端 `./mvnw -B verify` Tests run: 1012, Failures: 0, Errors: 0, Skipped: 8（18:07 min）；前端单测 55 文件 / 438 用例全绿（含本批新增 windowDates/dragTargets/changeVisual 单测）；Playwright 137 passed（15.5m）；全量总耗时约 36 分钟。本机无 Docker，PostgresContainerIT 按 `--full` 语义跳过（CI 会真跑）。
- 截图 `t03-01-keyboard-success.png` / `t03-02-320-step1.png` / `t03-03-320-change-list.png`（`.scratch/workbench-ui-redesign/acceptance/`）。
- 无后端/数据库改动（`git diff origin/main...HEAD -- backend/` 为空）；未部署、未触碰生产数据。

## 规格依据

`.scratch/workbench-ui-redesign/spec.md`；迁移前的五个 spec 即行为基线。
