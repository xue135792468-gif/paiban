# 02: 集成测试的数据库隔离

**What to build:** 所有 REST 集成测试共享同一个 H2 内存库（`jdbc:h2:mem:paiban_test`），测试之间只靠随机后缀避让，设置类测试写入的 `app_setting`、学期类测试切换的「当前学期」会泄漏到后续测试类。加一个 JUnit 扩展，在每个测试方法前清空业务表（保留身份种子：用户/角色/权限/关联表与 Flyway 历史），让每个用例从确定的空库开始。

**Blocked by:** 无

**Status:** ready-for-agent

- [x] `DatabaseResetExtension` 在每个测试方法前清空业务表，保留身份表与 `flyway_schema_history`
- [x] 表名从 `information_schema` 动态读取，迁移新增表无需改代码
- [x] 注册在 `ApiIntegrationTest` 上，所有 REST 集成测试自动生效
- [x] 全量后端测试保持全绿，且连续跑两次结果一致
