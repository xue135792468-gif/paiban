# 05 有界并发验收交付（测试独有写集）

2026-11-12。分支 `codex/dingtalk-t05-concurrency`，工作树
`.worktrees/dingtalk-t05-concurrency`。开基 `10b3e8b`；全程只普通 merge 主控给定的
不可变提交，没有 merge 浮动分支，没有 amend，没有 push，没有部署。

合并历史（按顺序）：

| 顺序 | 内容 | 本地提交 |
|---|---|---|
| 1 | 核心检查点 `16851f22f296f665fabbb88915f84b88523625e0`（9 files，+669/−30） | `d9120dd` |
| 2 | 完整生命周期 `8f457acb0bbf186fe84f150f486a057fe8d8cd1e`（撤回/驳回/惰性作废） | `86781bf` |
| 3 | 集成 `e3b990f`（主控 QA `2950110`，同一生产 core + 主控 RaceTest/browser/准备 docs） | `25bf8c4` |

**这不是 05 整票完成，也没有生产实现范围。**本切片只补并发回归：生产源码、迁移、
既有测试、前端、其它 docs 一律未改。锁与状态守卫由单写者实现提供，**本类是新增回归，
不是 TDD 红例**（已实现行为在修正测试卫生后首跑即绿）。

## 写集

| 文件 | 说明 |
|---|---|
| `backend/src/test/java/com/paiban/substitution/SubstitutionApprovalConcurrencyTest.java` | 新增；恰好 5 例 |
| `.scratch/dingtalk-multiuser/t05-concurrency-delivery.md` | 本文件 |

未纳入写集、仅供运行期使用：`.runtime/`（本树 TEMP/TMP/TMPDIR）与
`logs/t05-concurrency/` 下的原始日志。为让 `.runtime/` 不出现在 `git status`，本树在
共享的 `.git/info/exclude` 追加了一行 `.runtime/`——这是本地 git 元数据，不入库、
不改任何仓库文件。

## 五个边界（不凑数量，不加第 6 例）

| 用例 | 强制交错 | 不变量 |
|---|---|---|
| `staleSubmittedSnapshotCannotApproveAfterAnotherApprovalCommitted` | 同一 `EntityManager` 先读 SUBMITTED；另一事务（HTTP）批准提交；旧快照再调 `approve` | 锁前/锁后都断言一级缓存仍是旧 SUBMITTED；`refresh` 后 409「已被处理」；仅 1 条 EFFECTIVE 行、1 条 EFFECTIVE 历史、1 条 APPROVED 审计；审批人是真实胜出账号 |
| `stalePendingSnapshotCannotApproveAfterTheRequesterWithdrewElsewhere` | 同一 `EntityManager` 先读 SUBMITTED；申请人经 HTTP 撤回提交；旧快照再调 `approve` | 409；最终 WITHDRAWN；零生效行；历史恰为 SUBMITTED→WITHDRAWN；无 APPROVED 成功审计 |
| `twoAdminsApprovingConcurrentlyOverHttpYieldOneEffectAndOneRefusal` | 两个不同管理员账号 `start` latch 同时 POST approve | 一 200 一 409；单一生效行；SUBMITTED/EFFECTIVE 两条历史；SUBMITTED/APPROVED 两条审计；`approver_user_id` 与胜出账号一致 |
| `twoConcurrentHttpSubmitsForOneInstanceLeaveOnePendingRequestAndNoEffect` | 任课老师本人 + 持代发权限的管理员，对同一授课实例同时 POST create | 一 200 一 409；该 entry+date 只有 1 张 SUBMITTED；零生效行；单条 SUBMITTED 历史与审计；`operator_user_id` 等于胜出账号 |
| `concurrentApproveAndRejectOverHttpLeaveExactlyOneDecision` | 两个不同管理员同时 POST approve / reject | 一 200 一 409；最终状态与胜出方一致；EFFECTIVE 则单一生效行，REJECTED 则零生效行；历史/审计各只有一次终态决策 |

两类交织分开写：第 1、2 条是**服务层强制旧快照**（同一 `EntityManager` 先读 SUBMITTED，
另一事务提交终态后旧快照再走 `approve`），证明锁后 `refresh` 与状态守卫本身；第 3–5 条
是**HTTP 不变量**，只断言状态码与落库/历史/审计，胜出方归属由响应决定，不假设谁先拿到
学期行锁。全部不用 sleep 拟合时序。

HTTP 并发只断言稳定不变量，参与审批的账号各自新建（教务管理员角色，持
`substitution:approve`），落库 `approver_user_id`、历史 `actor`、审计 detail 三处互相
核对。「同一实例只能有一张待审批」用「老师本人 + 管理员代发」：一个授课实例只有一个
任课老师，两个不同教师账号都通过归属检查在业务上不成立。

夹具只复用公开入口：`ScheduleFixture`（未来周一 + `TestData.semesterStarting` +
`publishedIn`）、`ApiIntegrationTest` 的登录/`as()`、`TestData.teacher`。课节 id 一律
按「已发布条目里 teacherId 命中的那条」查，**不硬编码 `DraftLesson`/`timetable_entry`
id**。

## 过期清扫并发归主线程

主控发现并修复的第 6 项真实缺陷（`expireOverdue` 锁前取 SUBMITTED、锁后只 `refresh`
不复查状态，可能把已批准/撤回/驳回覆盖成 EXPIRED）由**主线程**在独立
`SubstitutionExpiryRaceIntegrationTest.java` 负责，变异红 / 恢复绿已在 `cab9002` 确认。
该用例不占本类文件，本交付保持**恰好五例**；本写集没有改主线程的 Race 文件。

## 测试卫生缺陷与修正（真 exit 1 保留）

五例首跑 `EXIT=1`：`Tests run: 5, Failures: 0, Errors: 1`。唯一 ERROR 在
`stalePendingSnapshotCannotApproveAfterTheRequesterWithdrewElsewhere:227`：
`ExecutionException: IllegalStateException: 等待并发事务时被中断`。

原因是我新加的统一清理 `releaseAndShutdown` 先 `countDown()` 再 `shutdownNow()`：
撤回用例里主线程刚放行 latch 就进入 `finally`，`shutdownNow` 立刻中断**还在恢复执行**
的 worker（它随后要在旧快照上调用 `approve`），`Latch.await` 抛
`InterruptedException`，最终 `Future.get` 抛出包装异常。

修法只在测试卫生层：先释放全部 latch，再 `pool.shutdown()` 优雅等待 worker 正常跑完；
只有 60 秒未终止才 `shutdownNow()`（再等 10 秒）；中断兜底同样先 `shutdownNow()` 再抛。
旧快照时序、`approve` 调用、409 断言与全部最终不变量原样保留，没有删例、没有放宽。
修正后五例 `EXIT=0`。这条失败是测试清理打断并发，不是生产回归。

## 测试证据（真实退出码）

工作树内 GitPortable Bash（`C:/Users/liu/AppData/Local/Programs/GitPortable/bin/bash.exe`，
避免默认 bash 落到 WSL），`source .tools/env.sh`，`TEMP/TMP/TMPDIR` 指向本树
`.runtime/tmp`，`-DforkCount=0` 单 Maven、低内存定向。

| 阶段 | 命令 | 结果 | 日志 |
|---|---|---|---|
| 编译 | `./mvnw -B -DskipTests test-compile` | BUILD SUCCESS | `t05-compile.log`，SHA256 `0EEC52B4696E66DA44A801043A1066D92C75A6FCCFCBC372146265FCC387E81D` |
| 3 条边界首跑 | `./mvnw -B -DforkCount=0 -Dtest='…#3 methods' test` | `Tests run: 3, Failures: 0, Errors: 0`；BUILD SUCCESS | `t05-first-run-3-boundaries.log`，SHA256 `4E6A02E5C1C576A1F02D6F831AADB2166A010ED2CA154C65C1379D7B5825E422` |
| 3 条边界复跑 | 同上 | `EXIT=0`；3/0/0/0；BUILD SUCCESS | `t05-confirm-run-3-boundaries.log`（SHA256 `33A83E6E15CBFF52DF2DE6EDB495AE390C9069AB2E30391E861345C6A13B3636`）、`…exit.txt` |
| 5 例首跑（失败，保留） | `./mvnw -B -DforkCount=0 -Dtest=SubstitutionApprovalConcurrencyTest test` | `EXIT=1`；5 run / 0 failures / **1 error**（测试卫生，见上） | `t05-full-five-run1.log`，SHA256 `7514C86D3AA31A16584DBD74C03E1AC646332102F64A24BE060086C2F51D7C3D`、`…run1.exit.txt` |
| 5 例修正后全绿 | 同上（集成 `e3b990f` 已并入） | `EXIT=0`；`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`，79.59 s；BUILD SUCCESS | `t05-full-five-run2.log`，SHA256 `9081291F629E6483F3793E55A0892DFE9FFB76C9E6167B85EB097E59BC644EE1`、`…run2.exit.txt` |

旧 3 例日志与失败 run1 全部保留，没有覆盖或删除。

## 运行纪律

- 开跑前核过资源：编译前 free 5.43 GiB / CPU 9%；3 例复跑前 5.45 GiB / CPU 8%；
  5 例前 5.04 GiB / CPU 10%。只启一个 Maven，不跑全量 `verify.sh`、不跑 Playwright/E2E、
  不起常驻服务、不连真实外网/真实企业钉钉。
- 跑完 `java` 进程数为 0。`.runtime/` 按 policy 保留未删除，也未移动或删除整个 worktree。

## 范围与未验证

- 未改生产源码、迁移、既有测试、前端、其它 `.scratch`/`docs`；未 stage 他人文件；
  未改主线程的 `SubstitutionExpiryRaceIntegrationTest.java`。
- 未跑 `scripts/verify.sh` 全量、Playwright/E2E、Postgres 容器。证据是 H2
  （`MODE=PostgreSQL`、`LOCK_TIMEOUT=15000`、独立内存库
  `paiban_substitution_concurrency`），**不声称覆盖真实 PostgreSQL 的锁等待/死锁检测**。
- 真钉钉通知、前端待审批面板、整链验收属于 06/08，不在本交付。
