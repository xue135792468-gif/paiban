# 04-C1 交付记录：账号写保护共享组件

日期：2026-11-11。工作树 `dingtalk-t04-guard`，分支 `codex/dingtalk-t04-guard`，基于集成 `codex/dingtalk-multiuser`（收尾已合并 `37eb3f4`）。本片只交稳定模块，不接 HTTP、不改票面状态。

## 做了什么

- 新增 `identity/service/AccountMutationGuard`（`@Component`），把原来散在 `UserService` 里的三件事抽成唯一一份：
  - `lock()`：只锁稳定的 `super_admin` 角色行；行缺失直接 500（fail closed）。03 首次免登还没有 accountId 时用它，不必假选一个账号。
  - `lockAndRefresh(Long)`：先 `lock()` 再读目标账号并强制从库里刷新，丢掉一级缓存里的旧快照。
  - `isLastEnabledSuperAdmin(AppUser)`：锁内按标量 count 判断「除目标外还有没有启用超管」。
- 两个公开方法都标 `@Transactional(propagation = MANDATORY)`：没有调用方事务直接失败，锁外读改账号不可能悄悄发生。类注释写明「必须先有事务、先锁再读改、网络/第三方 IO 留在锁外」。
- `UserService` 改为注入该组件：`update` / `resetPassword` / `disable` / `enable` 全部走 `lockAndRefresh`，`update` 的降级/停用判断用 `isLastEnabledSuperAdmin`。原有 self 停用、last-super、锁后刷新、02 的 `ROLE_CHANGE`→`UPDATE`/启停连续审计、未变更不伪造事件均保持不变。
- 新增同步停用入口 `UserService.disableForSync(Long)`：复用同一套守卫（self / last-super / 已停用幂等），审计只写一条 `ACCOUNT_SYNC_DISABLE`，actor 取当前登录管理员；没有登录主体直接 401，不会记成 `system`。私有 `disable(id, action)` 只接受 `DISABLE` 或 `SYNC_DISABLE`（`requireDisableAction` 兜底），不开放任意动作/来源。
- 无迁移、无 DTO、无 controller、无 03 规则改动。

## 红 → 绿

| 切片 | 首次失败 | 修好后 |
|---|---|---|
| 同步停用只留一条 SYNC 事件、旧 token 立刻 401 | 新用例自身把 admin 停用后还用 admin 的 token 去建号，`POST /api/users` 401（测试写错，未放宽任何业务断言） | 先建齐账号再停 admin；3/3 通过 |
| SYNC 动作不能退化成普通停用（变异验证） | 把 `applyDisable(user, action)` 改成恒传 `DISABLE` → `ACCOUNT_SYNC_DISABLE total=1` 断言红 | 恢复后绿 |
| 锁外调用必须失败 | 无（新增契约断言） | `lockAndRefresh` 在无事务时抛 `IllegalTransactionStateException` |

## 实测

- 合并后定向：`mvnw -B -DforkCount=0 -Dtest="AccountSyncDisableIntegrationTest,AccountAuditIntegrationTest,SuperAdminGuardIntegrationTest,UserImportIntegrationTest,AuthRbacIntegrationTest,RoleReshapeIntegrationTest,RolePermissionMatrixIntegrationTest,SettingsPermissionIntegrationTest,IdentityDataInitializerTest" test` → **59 通过 / 0 失败**（`AccountSyncDisableIntegrationTest` 3/3、`AccountAuditIntegrationTest` 14/14、`SuperAdminGuardIntegrationTest` 4/4）。日志：`feature/logs/final-targeted.log`（未入库）。
- `-DskipTests test-compile` 退出码 0。
- 变异后单跑 `AccountSyncDisableIntegrationTest#syncDisableLeavesExactlyOneSyncEventAndRefusesTheOldTokenNext` → 1 失败（预期红）；恢复源码并 touch 后重跑 `AccountSyncDisableIntegrationTest + SuperAdminGuardIntegrationTest` → 7/7 绿，避免沿用旧 class。
- 未跑全量 `verify.sh`、未跑前端（本片无 UI）、未接同步确认 HTTP（C2）、未在真实 PostgreSQL 演练，未 push/deploy。

## 给 C2 的调用约定

在既有事务内：`guard.lock()` 之后重扫结果已就绪（网络在事务外完成）→ 逐项核对候选与期望 teacherId/UID → 每个账号调用 `userService.disableForSync(accountId)`；任一目标不合法就整批回滚，不要半写。`disableForSync` 负责 self/last-super/幂等与单条 `ACCOUNT_SYNC_DISABLE` 审计。
