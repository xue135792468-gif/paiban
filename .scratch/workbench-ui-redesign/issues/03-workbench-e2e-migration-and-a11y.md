# 03: 既有 e2e 迁移 + 无障碍/320px + 帮助文案 + 全量门禁

**What to build:** 把依赖旧工作台结构的既有 e2e 全量迁移到新界面（保持同等行为覆盖），补键盘可达与 320px 断言，更新帮助文档中过时的界面描述，跑定向与全量门禁并记录证据。

**Blocked by:** 01、02

**Status:** ready-for-agent

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

- [ ] 五个 spec 全部迁移完成，行为覆盖与迁移前等价（逐条对照旧断言记录在交付记录里）。
- [ ] 新增键盘可达用例（仅键盘完成一次代课确认）与 320px 用例（步骤1 列表、变化可视化按天列表、无横向溢出）。
- [ ] 帮助文档与新界面一致，帮助中心测试（含锚点）全绿。
- [ ] 定向 e2e 连跑两遍稳定；`npm run lint && npm run build && npm run test:unit` 全绿。
- [ ] 全量门禁 `bash scripts/verify.sh --full` 退出码 0（重定向日志取退出码），记录后端/前端/Playwright 数字与耗时。
- [ ] 交付记录注明：无后端/数据库改动；未部署、未触碰生产数据。

## 规格依据

`.scratch/workbench-ui-redesign/spec.md`；迁移前的五个 spec 即行为基线。
