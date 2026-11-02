# 九张正式实现票据

Status: ready-for-agent
日期：2026-11-02
用户完成15项选择，并明确要求“开始拆票发布”，视为确认完整规格并授权发布票据；不视为认领、编码、合并或部署授权。

## 票据与依赖

| 票据 | Blocked by |
|---|---|
| [01 持久化课节锁定与草稿写入口保护](issues/01-persistent-locks.md) | 无 |
| [02 统一整单变更校验与旧冲突修复](issues/02-atomic-change-validation.md) | 01 |
| [03 共用方案策略与设置名称校正](issues/03-shared-exchange-policy.md) | 无 |
| [04 草稿联动方案搜索、独立复核与原子应用](issues/04-draft-cycle-plans.md) | 01、02、03 |
| [05 拖拽直接交换、自动列方案与整单撤销](issues/05-direct-drag-and-undo.md) | 04 |
| [06 单节、多选、整天与整班批量锁定](issues/06-bulk-lock-ui.md) | 01、05 |
| [07 指定当前草稿继续优化并保留锁定](issues/07-source-draft-optimization.md) | 01、02 |
| [08 继续优化入口、来源提示与新旧草稿比较](issues/08-optimization-entry-and-compare.md) | 05、06、07 |
| [09 整体验收、两轴复审与交付记录](issues/09-integrate-and-accept.md) | 01、02、03、04、05、06、07、08 |

全部九票均ready-for-agent、Owner unassigned、验收项未完成；存在前置票的必须等待，不因状态ready就提前执行。
规格见[spec.md](spec.md)，跨票边界见[contracts.md](contracts.md)，决策背景见[ADR-0034](../../docs/adr/0034-draft-locks-and-shared-exchange-policy.md)。

## 推进路线

- 初始业务frontier：01、03。01先稳定授课身份、锁定保护和事务一致性；03可独立更正共用设置。
- 01→02；01/02/03→04→05→06；01/02→07；05/06/07→08；全部→09。
- 05、06、08共享草稿编辑器，按该顺序串行。01、02、04、07共享SchedulingService、DTO或validator：默认按01→02→04→07集成；若要04和07并行，先拆清写集合与稳定接口，不能仅看依赖图。
- 推荐整体顺序01→02→03→04→05→06→07→08→09；03只在资源和实际工具槽位允许时与前两票独立并行。
- 小票只做定向回归；源码冻结、两轴复审和一次完整门禁由09收口。后续源码修复必须重新验证，不能复用旧提交绿灯。

## 统筹与执行边界

- 当前发布状态：已写入本地Markdown tracker，尚未认领、实现、合并或部署。发布不是GitHub issue操作。
- 正式实现从独立worktree/集成分支开始，主仓库只作合并；先核实HANDOFF、git log、工作区与线上事实，避免重复开发。
- Worker主力group/deepseek，reasoning_effort=high；机械检索/批量验证用group/gemini3.8；主模型负责高影响设计、验收整合及算法最终判断。
- 真正新增视觉设计时选Gemini4顾问；复用既有组件不需要为拆票另做UI路线。Hark未获授权，不调用。
- 每个子任务开始前查资源；内存不足时串行，不同时跑vitest与e2e。临时产物放工作区，任务结束清理临时文件/进程。
- 提交仅stage本任务路径，不带入主仓库既有截图或其他会话改动；迁移编号实施时跨分支核对。

## 首次开工前

1. 阅读本规格、contracts、ADR和当前HANDOFF。
2. 核实01/03是否已被认领；需要开始实现时再改Status与Owner。
3. 拟定当轮写集合，查系统资源，再派有界任务。
4. 不启动部署；最终交付清楚报告代码、全量验证、合并与部署各自状态。
