# 05 独立账号权限快照回归（待修复后验证）

2026-11-12。主控质量写集，基于 `c487df3b84e25b497286d9803fac9ab9a2146745`；其审批生产代码仍为原 8f457ac 版本。只新增 `SubstitutionApprovalAuthorizationSnapshotTest.java` 与本文，没有交付生产改动。

## 受控状态与真实行为

公开 API 创建未来学期、发布课表、代发一张 SUBMITTED 申请、创建独立教务账号。在同一 TransactionTemplate/EntityManager 预读该教务账号并确认具有审批权限；随后通过真实 Root HTTP 将其改为 Teacher。另一条真实 auth/me 请求确认当前账号已没有审批权限；原 EntityManager 刻意仍保持旧角色关联，并断言它确实还认为具有审批权限，然后调用实际 approve。

这条回归强制旧身份快照，配合独立 HTTP 角色变更与结果读回；不声称执行了两个 HTTP 审批请求的并发抢锁，也不依赖 sleep 或私有方法计数。没有 mock 业务类、仓库或时间。

期望：锁内决定重新取得当前身份，拒绝 403；申请仍 SUBMITTED，无生效行，仅提交历史，无审批成功审计。ApiException 在 TransactionTemplate 外捕获，避免吞异常引发 rollback-only 假红。

## 已执行的红

命令：`./mvnw -B -DforkCount=0 -Dtest=SubstitutionApprovalAuthorizationSnapshotTest test`。

实际 **1 条、1 失败、0 错误，退出 1**。所有角色变更及新 auth/me 前置、旧缓存权限断言已通过，失败点为 `revoked approval permission must refuse the decision`：refusal 实际为 null，审批没有拒绝。不是超时、编译或夹具故障。不要将本次未执行到的最终 HTTP 断言冒称已经验证了最终状态。

原日志与退出码保留在本工作树 `.runtime/t05-authz-main/red.log`、`red.exit.txt`，另归档到集成树 `logs/t05-authz-snapshot/`。运行结束 Java 进程 0；临时目录仅本工作树 `.runtime/t05-authz-main/`，未尝试删除。

生产整改由统一 implementer 负责：持有学期/申请锁后刷新当前用户，再判权限或归属。测试不能被该 worker 改写；合入整改后仍需同一测试转绿。现阶段不宣称 P3 已关闭、不宣称 05 放行。真实 PostgreSQL、企业钉钉与全量门禁未验证。
