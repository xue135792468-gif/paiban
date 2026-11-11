# 05 有界并发验收交付（测试独有写集）

2026-11-11。分支 `codex/dingtalk-t05-concurrency`，工作树
`.worktrees/dingtalk-t05-concurrency`。开基 `10b3e8b`，普通 merge 主控给定的
不可变核心检查点 `16851f22f296f665fabbb88915f84b88523625e0`（9 files，+669/−30），
本地合并提交 `d9120dd`。**没有 merge 任何浮动分支，没有 amend，没有 push，没有部署。**

**这不是 05 整票完成，也没有生产实现范围。**本切片只补并发回归：生产源码、迁移、
既有测试、前端、其它 docs 一律未改。锁与状态守卫由 James 的单写者实现提供，
**本类钉的是已实现行为的回归，不是最初 TDD 红例**（首跑即绿，如实记为新增回归）。

## 写集

| 文件 | 说明 |
|---|---|
| `backend/src/test/java/com/paiban/substitution/SubstitutionApprovalConcurrencyTest.java` | 新增；5 例已落代码，其中 3 例在当前检查点可跑且首跑绿 |
| `.scratch/dingtalk-multiuser/t05-concurrency-delivery.md` | 本文件 |

未纳入写集、仅供运行期使用的未跟踪物：`.runtime/`（本树 TEMP/TMP/TMPDIR）与
`logs/t05-concurrency/` 下的原始日志。为让 `.runtime/` 不出现在 `git status`，本树在
共享的 `.git/info/exclude` 追加了一行 `.runtime/`——这是本地 git 元数据，不入库、
不改任何仓库文件。

## 行为与断言口径

不复制 03 的整套 fixture。只复用实际公开入口：`ScheduleFixture`（未来周一 +
`TestData.semesterStarting` + `publishedIn`）、`ApiIntegrationTest` 的登录/`as()`，
以及 `TestData.teacher`。课节 id 一律按「班级已发布条目里 teacherId 命中的那条」查，
**不硬编码任何 `DraftLesson`/`timetable_entry` id**。日期取该条目 `dayOfWeek`
在未来那一周对应的真实授课日。

HTTP 并发只断言稳定不变量：状态码、「唯一生效行/唯一终态历史/唯一成功审计」、
落库真实 actor。谁先拿到学期行锁由运行时决定，断言按**胜出方归属**判断，不假设顺序、
不用 sleep 拟合时序。参与审批的账号各自新建（教务管理员角色，持
`substitution:approve`），落库 `approver_user_id`、历史 `actor`、审计 detail 三处
互相核对，确认不是 `admin` 或对方账号冒充。

| 用例 | 强制交错 | 不变量 | 当前状态 |
|---|---|---|---|
| `staleSubmittedSnapshotCannotApproveAfterAnotherApprovalCommitted` | 同一 `EntityManager` 先读 SUBMITTED；另一事务（HTTP）批准提交；旧快照再调 `approve` | 锁前/锁后都断言一级缓存仍是旧 SUBMITTED；`refresh` 后 409「已被处理」；仅 1 条 EFFECTIVE 行、1 条 EFFECTIVE 历史、1 条 APPROVED 审计；审批人是真实胜出账号 | **已跑，绿** |
| `stalePendingSnapshotCannotApproveAfterTheRequesterWithdrewElsewhere` | 同一 `EntityManager` 先读 SUBMITTED；申请人经 HTTP 撤回提交；旧快照再调 `approve` | 409；最终 WITHDRAWN；零生效行；历史恰为 SUBMITTED→WITHDRAWN；无 APPROVED 成功审计 | 已写，**待撤回实现** |
| `twoAdminsApprovingConcurrentlyOverHttpYieldOneEffectAndOneRefusal` | 两个不同管理员账号 `start` latch 同时 POST approve | 一 200 一 409；单一生效行；SUBMITTED/EFFECTIVE 两条历史；SUBMITTED/APPROVED 两条审计；`approver_user_id` 与胜出账号一致 | **已跑，绿** |
| `twoConcurrentHttpSubmitsForOneInstanceLeaveOnePendingRequestAndNoEffect` | 任课老师本人 + 持代发权限的管理员，对**同一授课实例**同时 POST create | 一 200 一 409；该 entry+date 只有 1 张 SUBMITTED；零生效行；单条 SUBMITTED 历史与审计；`operator_user_id` 等于胜出账号 | **已跑，绿** |
| `concurrentApproveAndRejectOverHttpLeaveExactlyOneDecision` | 两个不同管理员同时 POST approve / reject | 一 200 一 409；最终状态与胜出方一致；EFFECTIVE 则单一生效行，REJECTED 则零生效行；历史/审计各只有一次终态决策 | 已写，**待驳回/撤回完整实现** |

「同一实例只能有一张待审批」用「老师本人 + 管理员代发」是因为一个授课实例只有一个
任课老师；两个不同教师账号对同一实例都通过归属检查在业务上不成立。两条请求都
真实走 `POST /api/substitution-requests`，靠学期行锁后的查重决定胜者。

## 过期清扫并发归主线程，不在本写集

主控读 James 未提交的 `expireOverdue` 发现的第 6 项真实缺陷（锁前取 SUBMITTED、
锁后只 `refresh` 不复查状态，可能把已批准/已撤回/已驳回覆盖成 EXPIRED）由**主线程**
亲自写独立 `SubstitutionExpiryRaceIntegrationTest.java` 并验证。该用例不占本类文件，
本交付保持**恰好五例**，不为凑数量加例，也不把共享 fixture 抽成公共框架。

本写集因此不含任何引用 `expireOverdue` 的编译期代码；当前检查点 `16851f2` 上本类
可正常 testCompile，五个边界中包含的两个「待完整实现」用例只用公开 HTTP 字符串
路径（`/withdraw`、`/reject`），不引用尚未合入的服务方法。

## 待补齐的运行

主控给含撤回/驳回完整实现的固定 core SHA 后，本树再普通 merge 该 SHA，然后跑
**全部五例**并更新本节计数、退出码和最终不可变 SHA。第 2、5 条依赖撤回与驳回端点，
当前未运行，不能据此声称撤回/驳回并发已验收。

## 测试证据（真实退出码）

工作树内 GitPortable Bash（`C:/Users/liu/AppData/Local/Programs/GitPortable/bin/bash.exe`，
避免默认 bash 落到 WSL），`source .tools/env.sh`，`TEMP/TMP/TMPDIR` 指向本树
`.runtime/tmp`，`-DforkCount=0` 单 Maven、低内存定向。

| 阶段 | 命令 | 结果 | 日志 |
|---|---|---|---|
| 编译 | `./mvnw -B -DskipTests test-compile` | BUILD SUCCESS | `logs/t05-concurrency/t05-compile.log`，SHA256 `0EEC52B4696E66DA44A801043A1066D92C75A6FCCFCBC372146265FCC387E81D` |
| 3 条边界首跑 | `./mvnw -B -DforkCount=0 -Dtest='SubstitutionApprovalConcurrencyTest#staleSubmitted…+twoAdminsApproving…+twoConcurrentHttpSubmits…' test` | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`；BUILD SUCCESS | `logs/t05-concurrency/t05-first-run-3-boundaries.log`，SHA256 `4E6A02E5C1C576A1F02D6F831AADB2166A010ED2CA154C65C1379D7B5825E422` |
| 3 条边界复跑（补明确退出码） | 同上 | `EXIT=0`；`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`；BUILD SUCCESS；66.33 s→63.13 s | `logs/t05-concurrency/t05-confirm-run-3-boundaries.log`（SHA256 `33A83E6E15CBFF52DF2DE6EDB495AE390C9069AB2E30391E861345C6A13B3636`）、`…-boundaries.exit.txt`（`EXIT=0`） |

首跑与复跑都绿，说明检查点 `16851f2` 的「锁前定位 → 学期锁 → 申请行锁 →
`refresh` → 状态守卫」在旧快照下确实生效；本类是**新增回归**，不是 TDD 红例。
首跑那次 `$?` 回显被 PowerShell 引号吃掉（打出 `EXIT=True`），所以补了一次复跑取证；
两次日志都保留，没有覆盖或删除。

## 运行纪律与本轮实际动作

- 开跑前核过资源（本机总内存 15.73 GB）：首跑前 free 5.43 GiB / CPU 9%；复跑前
  free 5.45 GiB / CPU 8%。只启一个 Maven，不跑全量 `verify.sh`、不跑 Playwright/E2E、
  不起常驻服务、不连真实外网/真实企业钉钉。
- 跑完 `java` 进程数为 0（无自启进程残留）。`.runtime/` 按 policy **保留未删除**，
  也未尝试移动或删除整个 worktree。
- 当前检查点没有 `withdraw`，第 2、5 条按契约只用 HTTP 字符串路径准备，未运行，
  不能据此声称撤回/驳回并发已验收。

## 范围与未验证

- 未改生产源码、迁移、既有测试、前端、其它 `.scratch`/`docs`；未 stage 他人文件。
- 未跑 `scripts/verify.sh` 全量、Playwright/E2E、Postgres 容器。证据是 H2
  （`MODE=PostgreSQL`、`LOCK_TIMEOUT=15000`、独立内存库
  `paiban_substitution_concurrency`），**不声称覆盖真实 PostgreSQL 的锁等待/死锁检测**。
- 第 2、5 条待 James 完整实现（撤回/驳回端点）；过期清扫竞态由主线程在独立的
  `SubstitutionExpiryRaceIntegrationTest.java` 负责，不在本写集。收尾 SHA 必须是
  「并入主控指定最新 core/integration tip」后的不可变 SHA，届时补跑五例全集并更新
  本节计数与退出码。
