# 03-A2 交付：登录类型 / 密码边界（有界切片）

2026-11-11。分支 `codex/dingtalk-t03-identity`，工作树
`.worktrees/dingtalk-t03-identity`，基点为 integration `83a8350`，交付前已 merge
integration tip `fc3b325`（A1 HTTP adapter）。

**这不是 03 整票完成。** 本切片只做「独立类型 / 密码边界」，规模约 0.5 票：
自动开号本体、首登唯一同名配对、绑定保护、最后的 PASSWORD 超管守卫均由后续 A3 / 主线程承担。

## 写集（全部在此目录内）

| 文件 | 改动 |
|---|---|
| `identity/domain/LoginType.java` | 新增枚举 `PASSWORD` / `DINGTALK` |
| `identity/domain/AppUser.java` | 新增 `loginType`（默认 `PASSWORD`，`@Enumerated(STRING)`）；`passwordHash` 允许 null |
| `db/migration/V62__app_user_login_type.sql` | 新迁移（见下） |
| `identity/web/IdentityDtos.java` | `UserSummary` 追加 `loginType`，保留旧 7 参构造器（默认 `PASSWORD`） |
| `identity/service/UserService.java` | `create` 显式落 `PASSWORD`；`resetPassword` 锁后读类型、拒绝 `DINGTALK`；`toSummary` 输出类型 |
| `integration/auth/LocalAuthProvider.java` | 密码入口在 `AuthenticationManager` 之前按类型拒绝 `DINGTALK` |
| `identity/security/DbUserDetailsService.java` | 无哈希账号用**内存**空密码哨兵装载 JWT authorities（不写库、不合成密码） |
| `identity/web/AccountAuditDtos.java` | `AccountSnapshot` 追加 `LoginType loginType` / `String dingtalkUserId`，保留旧 6 参构造器 |
| `identity/service/AccountAuditService.java` | `snapshotOf` 从教师实时读 UID，与姓名同一次查询 |
| `test/.../LoginTypeBoundaryTest.java` | 新 HTTP 类（4 例） |
| `test/.../LoginTypeMigrationTest.java` | 新迁移类（2 例） |

未触碰：`AppUserRepository`、`AccountMutationGuard`、`FoundationService` / Provider / Planner /
BindingPolicy、`DingTalkClient` / Http / Fake 及其测试、`IntegrationController` /
`DingTalkAuthProvider`、任何前端、根级全局配置、其它票据。

## 迁移 V62

```sql
alter table app_user add column login_type varchar(16) not null default 'PASSWORD';
alter table app_user alter column password_hash drop not null;
alter table app_user add constraint ck_app_user_login_type check (login_type in ('PASSWORD','DINGTALK'));
alter table app_user add constraint ck_app_user_hash_matches_type
    check ((login_type = 'DINGTALK' and password_hash is null)
        or (login_type = 'PASSWORD' and password_hash is not null));
```

- 版本号核实：主仓库与全部 worktree / `E:\paiban-wt` 下 `V62+` 只有本文件一个（最高旧版本 V61）。
- 既有账号一律 `PASSWORD`，哈希逐字保留；`DEFAULT 'PASSWORD'` 让不带该列的旧 raw INSERT 继续可用。
- 类型/哈希组合由数据库兜底：`DINGTALK` 带哈希、`PASSWORD` 无哈希都被拒；
  业务层仍按类型先拒绝，不依赖约束报错。

## 外部行为

1. 钉钉免登账号用密码登录 → `401`，message 含「请在钉钉中打开」；未知账号仍是原来的「用户名或密码错误」。
2. 管理员对钉钉账号重置密码 → `409`，message 含「请在钉钉中打开」；账号哈希仍为 null，账号审计里没有 `ACCOUNT_RESET_PASSWORD` 事件。
3. `UserSummary`（`/api/auth/me`、`/api/users`）总是带 `loginType`；`admin` 密码登录仍是 200 且类型为 `PASSWORD`。
4. 无哈希账号凭 JWT 可正常装回权限上下文（`/api/auth/me` 200），哨兵只存在内存里。
5. 改身份（`PUT /api/users/{id}`）不改 `loginType` 与哈希；审计前后快照都带 `loginType` 与教师 `dingtalkUserId`（历史 JSON 缺字段读作 null，不重写）。

## 测试证据（`-DforkCount=0`，日志在 `.tmp/`）

| 阶段 | 命令 | 结果 |
|---|---|---|
| red 1 | `-Dtest=LoginTypeMigrationTest` | exit=1，`No migration with a target version 62`（2 errors） |
| green 1 | 同上 | exit=0，`Tests run: 2` |
| red 2 | `-Dtest=LoginTypeBoundaryTest#dingTalkAccountIsRefusedOnPasswordLogin` | exit=1，`JSON path message doesn't match` |
| green 2 | 同上 | exit=0 |
| red 3 | `... #resettingPasswordOnDingTalkAccountIsRefusedAndLeavesNoAudit` | exit=1，`Expected status code <409> but was <500>`（约束兜底暴露成 500） |
| green 3 | 同上 | exit=0 |
| red 4 | `... #jwtRequestsReportTheAccountLoginType` | exit=1，`Expected status code <200> but was <401>` |
| green 4 | 同上 | exit=0 |
| red 5 | `... #changingRoleKeepsDingTalkTypeAndRecordsTheBindingInTheAudit` | exit=1，`JSON path items[0].before.loginType doesn't match` |
| green 5/全类 | `-Dtest=LoginTypeBoundaryTest` | exit=0，`Tests run: 4` |
| 回归 | `-Dtest="LoginTypeBoundaryTest,LoginTypeMigrationTest,AuthRbacIntegrationTest,AccountAuditIntegrationTest,AccountAuditUpgradeDataPreservationTest,IdentityDataInitializerTest,DingTalkH5IdentityHttpTest"` | exit=0，`Tests run: 49, Failures: 0, Errors: 0` |

未跑：`scripts/verify.sh` 全量、Playwright / E2E、Postgres 容器迁移（按票据约定留到八票收尾）。
`ddl-auto=validate` 由上述 Spring 上下文启动隐式验证（test profile 即 validate）。

## 明确未实现（不属于本切片，不要按 03 整票验收）

- **最后的 PASSWORD 超级管理员守卫**：未实现。留给 A3 整合 `AccountMutationGuard` / repository 时做；
  现在只保证「按类型判断」的数据基础，没有「至少一个启用 PASSWORD 超管」的判定。
- 自动开号、唯一同名配对、绑定保护（`DingTalkBindingPolicy`）、免登失败原因码 —— A3 / 主线程。
- 前端登录类型展示与「隐藏重置密码入口」—— B 切片。
- `/api/auth/dingtalk` 未改动。
