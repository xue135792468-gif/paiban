# 04 全目录与模板验证

2026-11-01：新CLASS_UNAVAILABLE加入真实26类逐项API生效/发布拦截矩阵；该类型硬约束专用，两种软化路线均应400，其他25类保持原有逐项覆盖测试。模板全目录往返26类20条entry，验证新班级ID显式映射、位置、groupKey、启用状态与硬属性；既有19类条目控制仍逐项核对。

首次运行61项：目录53项1项失败，模板8项通过。失败是通用逐项控制fixture试图把新类型设成soft，与必须留空语义冲突；补上硬约束专用的拒绝断言，没有放宽服务规则。

重新执行：source .tools/env.sh 后，backend ./mvnw -B -DforkCount=0 -Dtest=ConstraintCatalogueAcceptanceTest,ConstraintTemplateCompatibilityTest test，MAVEN_OPTS=-Xmx512m。

实际exit0，目录53项、模板8项，合计61项通过，BUILD SUCCESS。环境H2，未跑PostgreSQL、真实模型或全量门禁。补丁由Gemini产生，主模型检查并修正fixture，测试退出码由主模型实际复验。
