# 票01：旧库升级保全测试

2026-11-11，分支codex/dingtalk-t01-migration。测试边界为规格已确认的旧库Flyway升级，不启动Spring，不连接生产或真钉钉。

`RoleUpgradeDataPreservationTest` 创建独立H2 PostgreSQL模式库，执行至V59，写入原admin、另一位原管理员、班主任、停用老师、钉钉绑定、草稿、已发布课表、调代、规则和导入会话。执行V60后断言两个旧管理员成为super_admin、班主任成为teacher、角色目录仅三种、教务无账号/集成/审计权限、退休的本班权限不再授予预置角色。12张业务表逐行逐列比较不变，另复跑Flyway验证角色关系与业务数据不变。

命令：source .tools/env.sh 后 cd backend，`./mvnw -B -DforkCount=0 -Dtest=RoleUpgradeDataPreservationTest test`。

- 红：分支仅含V59时，1项错误，明确报 `No migration with a target version 60 could be found`。不是业务断言失败，不冒称已复现数据丢失。
- SQL候选验证：只复制后端worker当前V60 SQL到本隔离工作树（不提交副本），1项通过、0失败/错误/跳过，退出码0。候选SQL SHA256为 `56B63420B786D2D57761FDEA443E16684C083D68E312569F6CD467448F3C42A0`；验证后确认源文件哈希仍相同，移除临时SQL副本。
- 候选运行日志SHA256：`8407B7310D83B0FA276158B8A842E19F753B224EC21464B4EAFB84B855432423`。

最终整合验收待后端提交合入后进行；必须核对正式V60与候选哈希并在同一集成源码树复跑此测试。不能把候选验证当成已完成整票或真实PostgreSQL验证。临时日志在统计与哈希落盘后随任务工作树清理。
