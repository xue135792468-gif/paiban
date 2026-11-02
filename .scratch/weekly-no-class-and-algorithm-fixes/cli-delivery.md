# 02/04 命令行入口复核

主模型发现两个入口残留并补齐：内部SEARCH_BUDGET诊断仍被CLI标题NO_SOLUTION覆盖；CLI快照曾忽略逐项暂停/强度/权重/groupKey，且明确空entries仍回退旧params。后者会让已暂停的每周禁排继续生效，或漏掉非法软化配置。

现有schemaVersion保持1：新增SEARCH_BUDGET状态与独立退出码3，默认预算不变，提供正整数--node-budget便于用户限制搜索，也使验收不依赖墙钟偶发超时。有候选仍返回OK；完整搜索当前范围无候选才NO_SOLUTION/2。

真实TDD证据：SubstitutionCliTest新增预算场景后17项1失败（exit1）；修正预算并补逐项控制场景后18项1失败（exit1）；修正entry映射和空数组语义后18项全通过（exit0，BUILD SUCCESS）。测试调用公开SubstitutionCli.run，覆盖JSON/table、确定性nodeBudget1、非法0、明确空entries、暂停、硬禁排、拒绝soft。临时快照在backend/target下生成并在finally删除。

定向命令：source .tools/env.sh，backend ./mvnw -B -DforkCount=0 -Dtest=SubstitutionCliTest test，MAVEN_OPTS=-Xmx512m。未跑全量或部署，仍待06复审集成。
