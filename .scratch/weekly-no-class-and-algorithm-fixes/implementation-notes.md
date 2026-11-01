# 实施约定与检查点

2026-11-01：用户显式调用implement-spec，授权本批实现、测试和评审，不部署生产。

- 集成分支：codex/weekly-no-class；初始规划提交5b2a7ae；源代码基点4c1cc19。
- 集成工作目录：E:/排课系统/.worktrees/weekly-integration。任务worktree均在主仓库.worktrees中，通过脚本联接依赖；该目录仅本地忽略。
- 主仓库main中的17张已修改截图及其他未跟踪原型/报告不是本任务，不stage、不清理。
- 初始资源检查仅约1GB可用；默认一个执行任务/一个测试进程串行。每次派发、全量门禁前再查资源；不关用户已有浏览器或其他服务。
- Git Bash必须使用C:/Users/liu/AppData/Local/Programs/GitPortable/bin/bash.exe。系统bash是WSL，不能代用。Maven前source .tools/env.sh，定向测试-DforkCount=0；低内存MAVEN_OPTS=-Xmx512m。全量门禁资源足够时再运行。
- 工具暴露不等于任务完成。group/deepseek执行者01已实际读到工作区，主模型仍须检查补丁与红绿证据。算法判断由主模型负责。

## 后续票实施提示

- 02：SAME_COURSE_ONCE_PER_DAY的按天聚合不能在单边placeable中根据尚未全部移出的旧课表拒绝。最终score/PlanVerifier必须保留；教师/教室同时占用、禁排等不可修复边界仍可安全提前判定。
- 03：TimetableSolution的periods已是ProblemFactCollectionProperty。按真实PeriodSlot统计内部空位，不用节次数字跨度虚构缺失作息；可对班级/日期聚合后join真实PeriodSlot，再按未占位逐格计软分。硬模式只要求违规有无一致；不要把因评分刻度差异导致的硬分数差异当作发布规则。
- 04：CLASS_UNAVAILABLE复用现有entry/groupKey配置。全局和多班在05补完整UI；新类型的soft请求必须服务端拒绝。配置目录由enum动态生成，新学期只预置三条冲突配置，无需为“目录可见”强行加表。Schema是否变化以实际需要决定。
- 04/05：AI prompt当前从全部ConstraintType生成目录，normalizer也valueOf接受类型。新增类型时必须显式排除AI生成并返回UNSUPPORTED；保留手工配置的全量保存/恢复，不能只在prompt上隐藏。
- 04/05：现有slot-grouped头部字段只传teacher/course，没有classes；班级选择需要把既有class options传到底层或新独立小组件。不要继续膨胀已超限SFC。
- 05：容量预检查是必要条件（每班周任务数 > 真实可用槽），不能当作充分可解证明；求解真正运行前也要核对。存配置可提示不足但允许保存，任务提交应在无job落库前拒绝明确不足。

## 验证记录

01–03算法修正、04单班闭环、05前端已合入集成分支；05后端仍在独立分支实施。04单班Playwright实际1/1通过，全目录/模板61项通过，AI解析9项通过，CLI18项通过；发布/权限/版本/后续调课边界2项通过。各项证据见core-delivery、ui-delivery、catalogue-delivery、scope-ui-delivery、cli-delivery与published-delivery；有重复的定向测试数量不累加为独立总数。

尚未完成新增3场景浏览器合验、两轴复审与完整verify闸门。当前环境无Docker和本地PostgreSQL；H2通过不代表真实PostgreSQL已验证。未部署生产。
