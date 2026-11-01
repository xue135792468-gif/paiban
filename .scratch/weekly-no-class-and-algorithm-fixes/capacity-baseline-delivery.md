# 容量规则的旧版行为对照

主模型在独立临时worktree以05前的`089a2d5`为源码，只复制当前公开API测试ClassCapacityIntegrationTest，运行preview/start两个方法，不混入新容量计算器。

命令：source .tools/env.sh，backend ./mvnw -B -DforkCount=0 '-Dtest=ClassCapacityIntegrationTest#previewWarnsAboutOneMissingSlotButSaveIsStillAllowed+startRefusesTheShortageWithoutCreatingAJobOrTouchingAssignments' test，MAVEN_OPTS=-Xmx512m。

实际exit1、2项均失败、0编译/运行错误：

- 40个真实位置、同班两门课20+19共39节、留空2个位置时，旧版start期望400却返回200，确实创建了无意义的搜索任务。
- 同样输入的preview缺失warnings，期望一条不足提示，实际null。

这是一轮在实现之后补做的旧版行为对照，不伪称为当时已先跑过的TDD红灯。新版`89f3eef`对应3项API测试已exit0，并另有235项广定向通过。基线测试及H2进程已结束；临时worktree在记录完成后清理，无生产数据改动。
