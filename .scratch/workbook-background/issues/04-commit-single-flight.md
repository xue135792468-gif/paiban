# 票据 04: 整本入库的重复提交只允许一个在飞

**What to build:** 同一会话的两次并发「确认入库」应只有一次真正写入；第二次返回明确冲突，不得各建一份草稿后由乐观锁把后一次保存打掉。
**Blocked by:** None（本批 01-03 已实现的提交前批次完整性闸门是前置）
**Status:** done
**Execution:** 已实现（2026-10-28），采用候选方案 2（`COMMITTING` 短事务认领）
**Spec:** ../spec.md

## 现象与证据

`WorkbookImportFlow.commit` 先读一次会话状态，然后依次写教师、课程、班级、课表，最后才用请求开始时读到的 `version` 保存会话。两个并发请求都能通过开头的状态检查；课表那一类会各建一份 `DRAFT`，后一次 `logs.save` 才因乐观锁失败。

这是本批之前就存在的提交竞态，不是 01-03 引入的；票据 03 的「提交竞争」要求把它当成待办而不是忽略。单目标导入的 `commit` 有同样形态。

## 候选方案（择一，需评审）

1. 会话级单飞：在进程内按会话 id 占位，重复请求直接 409；实现小，但多实例部署不成立。
2. 新增 `COMMITTING` 状态：提交前短事务认领，失败/成功后落回 `PREVIEW`/`COMMITTED`；可跨实例，但前端状态、重启恢复与权限文案都要补。
3. 以数据库行锁/唯一约束把「一个会话最多一份草稿/一次提交结果」变成结构约束；需要先确认草稿写入的事务边界是否允许长锁。

## 验收

- 两个并发提交只有一个 200，另一个 409；数据库只有一份新草稿。
- 首个提交失败（行错误或类别异常）后可再次提交，不会被占位卡死。
- 重启后不会留下永久 `COMMITTING`。
- 单目标课表提交的同类竞态一并评估，不默认在整本票里顺手改。

## Comments

- 2026-10-26 由 01-03 的 code-review（Spec 轴）发现，未在本批实现；本批已有「任何未完成批次拒绝提交」和 `run_id` 防护，但重复提交本身仍未单飞。

## 实现说明（2026-10-28）

**选型：** 候选方案 2。`AiImportLog` 新增 `COMMITTING` 状态；`WorkbookImportFlow.commit` 在写任何业务数据前，先在一个短事务里重新读取会话、校验状态、写入 `COMMITTING` 并 `saveAndFlush`。乐观锁（`@Version`）是唯一的仲裁者：两个请求都从 `PREVIEW` 出发时，只有一个能完成认领，另一个要么读到 `COMMITTING`、要么在 flush 时丢版本，统一返回 409，不执行任何类别写入。

- **失败可重试：** 认领后的任何异常都会走 `releaseCommitClaim` 把状态放回 `PREVIEW`（审计里写「入库未完成，可再次确认：…」）；类别本身的行错误仍按原逻辑落成 `PREVIEW`，不会卡在 `COMMITTING`。
- **重启恢复：** `recoverInterruptedSessions` 新增扫描 `COMMITTING`，把上次进程留下的占位放回 `PREVIEW` 并注明「服务重启中断了入库，请重新确认」，不会留下永久 `COMMITTING`。
- **并发入口：** `AiImportService.requireEditable` 与 `reparse` 都拒绝 `COMMITTING`；`stop`（仅 `PARSING`）、`abandon`（仅 `PARSING`/`PARTIAL`）、`retry`（仅 `PARTIAL`）本来就不会碰这个状态。
- **前端：** `importStatus.ts` 增加「入库中」标签、锁定态与可恢复态；确认按钮在 `COMMITTING` 时显示「入库中…」并禁用。帮助文档补充一句并发确认的行为。
- **单目标课表提交评估（按要求不改）：** 单目标 `commitSingleTarget` 在**一个** `transactionTemplate` 里同时建草稿和保存会话，后到请求的乐观锁失败会让整个事务回滚，草稿不会残留；因此同类竞态只影响错误文案（`ObjectOptimisticLockingFailureException` 已由 `GlobalExceptionHandler` 映射 409），不产生重复数据，不在本票顺手改。
- **已知边界：** 启动恢复会把**所有**残留 `COMMITTING` 视为本进程崩溃遗留。当前部署是单实例；若将来多实例部署，需要额外的认领时间戳/心跳，避免重启实例误清另一个实例正在进行的提交。

**部署记录（2026-10-28）**：合并 `main` `a355dde`（提交 `ade61b3`），推送后 CI 四 job 全绿（backend 含真实 PostgreSQL `PostgresContainerIT`、e2e、frontend、deploy-scripts）。服务器 `~/paiban` HEAD 与 origin/main 一致；Flyway 仍为 v37（本票无迁移）；四容器 healthy；本机 HTTPS 与公网 health 均 200。部署前备份 `~/backups/paiban/paiban-pre-20261028T041007Z.sql.gz`（295KB，`gzip -t` 通过）。
**生产冒烟**：用 1 行 CSV 工作簿（八（1）/星期一/1/语文/金林墨）走 `POST /api/ai/import/excel?target=workbook` → PREVIEW；第一次确认 200（`draftId=6`），第二次确认 409「该导入已入库，不能再修改」；`timetable_draft` 只新增 1 份，`draft_lesson` 1 节。冒烟中发现并绕开一个与本票无关的既有坑：把生产作息 label「上午第1节」原样写进 CSV 时，课表导入的节次解析只认「第1节 / 1」，该行会被标成无法识别节次；冒烟改用 `1` 后通过。

**测试：** 新增 `WorkbookCommitRaceTest`：两个「都读到 PREVIEW」的会话快照（顺序与双线程各一）断言只有一次成功、另一方冲突、数据库只多一份草稿；失败后同一会话可再次提交；`COMMITTING` 经启动恢复回到 `PREVIEW`；另有一条 HTTP 并发用例断言两个请求恰好一个 200、一个 409 且只多一份草稿。修复前稳定变红（期望 2 份草稿、实际 3 份），修复后全绿。定向回归：导入模块 111 例全绿，前端相关单测 74 例全绿。

## 评审修复（2026-10-28，两轴独立评审）

评审结论为无阻塞问题；按评审建议补了以下低成本项：

- **页面轮询**：`useImportSession` 恢复出 `COMMITTING` 时也继续轮询，跨标签页恢复后会自动刷新到已入库，不会永久停在「入库中」。
- **历史状态**：`AiHistoryView` 改用 `importStatus.ts` 的共享中文词表（不再维护第二份局部表）；`AiHistoryService` 给 `COMMITTING` 单独摘要，且不再把进行中的入库标成成功。
- **认领结算去重**：`recoverCommitClaim` 与 `releaseCommitClaim` 合并为 `settleCommitClaim`，恢复与释放只差一句说明文案。
- **Playwright 覆盖**：新增「入库中的会话恢复后显示禁用按钮，并自动刷新到已入库」，用会话读 stub 稳定观察这个瞬时状态。
- **HTTP 契约**：`WorkbookCommitRaceTest` 增加真实 REST 并发用例（一个 200、一个 409、只多一份草稿）。

保留为后续、不在本票扩大的项：

1. `AiImportService.requireEditable`、`reparse` 与 `WorkbookImportFlow.requireCommitable` 各有一份状态门文案，未来可在票 14 的服务拆分里统一。
2. 认领没有租约/超时：进程挂起（非崩溃）时只能靠重启释放；当前单实例部署接受，ADR-0029 已记。
3. 后台晚响应仍会写 `batches_json` 并推进版本；现有状态门（提交要求全部批次完成）使它在实践中不可达，但若未来放宽提交前置，应给 `recordLateAnswer` 补 `COMMITTING` 保护。
4. `releaseCommitClaim` 写的「入库未完成」说明存在 `ai_explanation`，而 `PREVIEW` 的页面说明优先取 payload 文案，因此用户看到的是提交返回的错误提示；说明保留作审计。
