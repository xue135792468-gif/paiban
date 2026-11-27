# 03 有界并发验收交付（测试独有写集）

2026-11-11。分支 `codex/dingtalk-t03-concurrency`，工作树
`.worktrees/dingtalk-t03-concurrency`。开基 `30ba7c3`（A3 minimal 已绿），
开工前后各 ff 到集成 `51dfe3a`、`3bc1c2f`，与主线程同一基点；集成新增的
`DingTalkIdentityMatchingIntegrationTest` 与匹配交付文档不涉本类，生产代码无差异。

**这不是 03 整票完成，也没有生产实现范围。**本切片只补 4 条并发回归，生产源码、
旧测试、迁移、其它文档一律未改；锁实现由 A3（`DingTalkAccountProvisioner` +
`AccountMutationGuard`）提供，**本类钉的是已实现锁的回归，不是初始 TDD 红例**。

## 写集

| 文件 | 说明 |
|---|---|
| `backend/src/test/java/com/paiban/integration/DingTalkProvisionConcurrencyIntegrationTest.java` | 新增；4 例 |
| `.scratch/dingtalk-multiuser/t03-concurrency-delivery.md` | 本文件 |

## Seam 与场景

- 服务层：`DingTalkAccountProvisioner.resolveAccount`（公开 Spring bean，事务边界），
  用 `TransactionTemplate` 在外部事务里先 `TeacherRepository.findById` 预加载未绑定教师，
  形成一级缓存里的「UID 为空」旧快照，再用 `CountDownLatch` 让先到方提交、后到方才调用
  `resolveAccount`。不 mock 任何内部 Repository。
- HTTP 层：`POST /api/auth/dingtalk` + `FakeDingTalkClient`，两个请求用 start latch
  同时发出，只断言状态码与落库行数，不假设真实并发时序。

| 用例 | 强制交错 | 不变量 |
|---|---|---|
| `sameUidStalePreloadReusesTheCommittedAccountAndKeepsOnlyTheTwoAudits` | 两事务均预加载同一未绑定教师；先方提交绑定+开号，后方在其旧快照上调用 | 前后方返回同名；仅 1 个 DINGTALK 账号（hash=null）；教师 UID 正确；恰好 2 条系统审计（AUTO_PROVISION + LINK），LINK 后快照带 UID |
| `secondUidCannotOverrideTheBindingCommittedWhileItHeldTheUnboundSnapshot` | 同上，后方是另一 UID、同名唯一教师 | 后方 `401 / NOT_PROVISIONED`，不建账号、不覆盖教师 UID；仍只有 1 账号、2 审计，LINK 指向先到 UID |
| `twoConcurrentHttpLoginsWithTheSameVerifiedUidYieldOneAccountAndOneSession` | 两个 HTTP 请求同 authCode | 两个 200 且 `user.id` 相同（同账号；方法名里的 session 指同一账号/登录路径，**不断言 token 唯一**）；教师仅 1 账号、UID 正确、审计 2 条 |
| `twoConcurrentHttpLoginsForOneUniqueNameLeaveOneBindingAndRejectTheOtherUid` | 两个 HTTP 请求不同 UID、同名唯一教师 | 恰好一方 200；失败方 `401 / NOT_PROVISIONED`；教师 UID 等于胜出方；1 账号、2 审计 |

旧快照断言写在 `resolveAccount` 调用之前（`stale.getDingtalkUserId()` 必须为 null），
证明交错确实发生；业务断言全部走公开 HTTP（`/api/users`、`/api/teachers`、
`/api/account-audits`）与仓储落库行数。

## 测试证据（真实退出码）

工作树内 GitPortable Bash，`source .tools/env.sh`，`TEMP/TMP/TMPDIR` 指向本树
`.scratch/dingtalk-multiuser/tmp`（已清空，policy 拒绝对本目录递归删除，见下）。

| 阶段 | 命令 | 结果 | 日志 |
|---|---|---|---|
| 第 1 条先绿 | `./mvnw -B -DforkCount=0 -Dtest='DingTalkProvisionConcurrencyIntegrationTest#sameUidStalePreloadReusesTheCommittedAccountAndKeepsOnlyTheTwoAudits' test` | exit 0；`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`；25.65 s；BUILD SUCCESS | `.scratch/dingtalk-multiuser/logs/t03-concurrency-first-green.log`（SHA256 `200D6FF2F0D738B4CF64843C5740142F9A48B4A6D23872F97BBA1309014F181C`） |
| 4 例首跑（失败，保留） | `./mvnw -B -DforkCount=0 -Dtest=DingTalkProvisionConcurrencyIntegrationTest test` | exit 1；`Tests run: 4, Failures: 0, Errors: 1`；BUILD FAILURE | `.scratch/dingtalk-multiuser/logs/t03-concurrency-final-rollbackonly.log`（SHA256 `3288C4706BABBB738366837F07C8324C3EE8BC30DB4076999AF7FA5065A01214`） |
| 4 例全绿 | 同上 | exit 0；`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`；42.56 s；BUILD SUCCESS | `.scratch/dingtalk-multiuser/logs/t03-concurrency-unique-final-green.log`（SHA256 `54E184492EAC34F4DF125A0857787535B548C1988BBB9E649551F2A66DC8CF6A`） |

### 失败那一跑是测试边界错误，不是生产回归

唯一 ERROR：

```
DingTalkProvisionConcurrencyIntegrationTest.secondUidCannotOverrideTheBindingCommittedWhileItHeldTheUnboundSnapshot:214
? Execution org.springframework.transaction.UnexpectedRollbackException:
  Transaction silently rolled back because it has been marked as rollback-only
```

原因：第二条用例在 `TransactionTemplate` callback **内部** `catch (ApiException)`，内层
`@Transactional` 代理已把事务标成 rollback-only，外层再提交即抛
`UnexpectedRollbackException`，原来的 NOT_PROVISIONED 断言根本没跑到。修法只在测试内：
`try/catch` 移到 `transactionTemplate.execute` **外面**，callback 直接让 `ApiException`
传播并在外层读取 `status` 与 `details.code`；旧快照断言、barrier 与最终落库/审计断言原样保留。
没有改生产 `noRollbackFor`，也没有放宽断言。

## 范围与未做

- 未改生产源码、旧测试、迁移、其它 `.scratch`/docs；未新增模块、框架或抽象。
- 未跑 `scripts/verify.sh` 全量、Playwright/E2E、Postgres 容器；未联网、未触发生产。
- 未 push、未自行 merge 到集成；集成分支由主线程/merger 处理。
- 夹具清理：`@AfterEach` 只删本类自建教师关联的账号与这些教师本身，随后恢复
  dingtalk settings 与 Fake 目录，不动 seed admin、不整表清账号、不影响其它 UID。
- 临时目录 `.scratch/dingtalk-multiuser/tmp` 下的 Maven/JVM 临时文件已按 policy 要求
  核过路径，但 `Remove-Item -Recurse` 被执行策略拒绝；该目录被 git ignore，未提交，
  留待主线程按工作区卫生统一清理，不触碰其它 workspace 的 `.tmp`。

## 残余判断

- 证据是 H2（MVStore、`LOCK_TIMEOUT=15000`）而非真实 PostgreSQL；行锁阻塞语义在
  PG 上更直接，本类不声称覆盖 PG 锁等待/死锁检测。
- HTTP 用例只钉「状态码 + 落库行数」这类稳定不变量；具体谁先拿锁由运行时决定，
  断言按胜出方归属判断，不依赖固定先后。
- 同 UID HTTP 用例不断言 token 唯一，只断言两个请求拿到同一个账号。
