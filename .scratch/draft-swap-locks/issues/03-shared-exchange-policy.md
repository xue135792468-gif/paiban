# 03 共用方案策略与设置名称校正

Status: ready-for-agent
Type: task
Blocked by: 无
Owner: unassigned
日期：2026-11-02

## 目标与输入

规格§三；现有substitution.max-plan-teachers、substitution.ranking-preset、RankingPreset。
前置票必须验收并合入后才能认领；ready-for-agent表示规格已就绪，不表示依赖已完成。
共同约束见[规格](../spec.md)、[接口契约](../contracts.md)、[执行路线](../ticket-plan.md)。

## 写入范围与边界

SettingsService、SubstitutionPolicyCard、共享方案策略/排序边界、必要的PlanSnapshotFactory适配、帮助说明及其定向测试。不要修改草稿编辑器或搜索实现。
只修改本票必要文件；发现契约冲突先由统筹统一更正，不能让多个worker各自猜接口。不得改生产数据或部署。

## 实施步骤

1. 将界面教师上限更正为最多变动几节课，保持旧持久化键、旧值、2–10范围和默认6；不悄悄改为去重教师上限。
2. 输出可供草稿和调代共同读取的策略快照/指纹：最大移动课节数、三种排序；保留现有排序优先级和稳定tie-break语义。模块放置遵守边界检查，不让scheduling依赖substitution服务。
3. 标明修改会同时影响两边新生成方案；搜索天数、审批、通知仍只用于发布后调代；修正页面中所有排序都先搜同日的误导性提示。
4. 原日期调课继续使用原有日期窗口与执行流程；为04提供周期维度统计适配契约，不用伪造日期调用。

## 验收清单

- [ ] 旧配置不丢失，默认与边界校验不变，旧客户端调用保持兼容。
- [ ] 同一教师参与多节时，上限按移动课节数控制、去重教师数另计。
- [ ] 三种排序行为不退化为固定课程数优先；草稿与调代能取得相同策略值和指纹。
- [ ] 设置页及说明展示适用范围，原调课生成/排序/审批回归通过。

## 验证与交付

SettingsService与RankingPreset定向后端测试；设置组件单测和设置操作E2E。仅复用既有控件；若另做视觉方案按AGENTS调用Gemini4。
每票提交Delivery：实际改动文件、分支/基点/提交、真实命令与退出码、剩余问题；全量门禁统一由09执行。
任务前检查资源；实际执行默认group/deepseek、high；机械搜证仅group/gemini3.8；算法结论由主模型负责。
遵守AGENTS/CODING_STANDARDS，不同worktree隔离；结束清理本票临时文件与进程。

## Delivery

尚未认领、尚未实现；当前仅发布票据。

## Comments
