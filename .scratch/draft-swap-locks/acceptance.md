# 草稿拖拽换位、联动方案与批量锁定：统一验收

日期：2026-11-02（2026-11-03 合并部署）
分支：`codex/draft-swap-locks`（集成分支，基点 `main` `4353a16`；已通过 PR #22 合并 main）
状态：九票已实现、两轴复审、分阶段全量验收；**已合并 main 并部署生产（Flyway 47）**，部署回执见 [deployment.md](deployment.md)。

## 1. 交付范围

| 票 | 能力 | 状态 |
|---|---|---|
| 01 | 持久化课节锁定、草稿版本协议、所有写入口保护 | done |
| 02 | 稳定违规身份/权重、整单变更、旧冲突修复差分 | done |
| 03 | 草稿与调代共用方案策略、设置名称校正 | done |
| 04 | 同班本周闭环搜索、独立全表复核、一次性 token 原子应用 | done |
| 05 | 直接拖拽交换、自动列方案、整单撤销 | done |
| 06 | 单节/多选/按天/整班批量锁定、键盘入口 | done |
| 07 | 显式来源草稿继续优化、锁定继承、真实发布资格 | done |
| 08 | 继续优化入口、任务状态、新旧草稿比较 | done |
| 09 | 两轴复审、集成修复、全量验收与交付记录 | done |

## 2. 关键实现口径

- **锁定身份**：`(assignmentId, lessonIndex)`；跨草稿继承不使用行 id。锁定固定 period/teacher，room 仍可补；所有草稿写入口经 `DraftWriteGuard` 的草稿行锁与版本检查。
- **整单变更**：`POST /timetable-drafts/{id}/changes` 一次事务应用全部变化；先应用后判；稳定违规 key + weight 比较，允许修旧问题、禁止新增或加重；软分变化单独返回。
- **闭环方案**：只在本班本周、只动未锁课；直接交换优先；失败自动搜索最多 3 个闭环；独立全表复核；预算耗尽与真正无解分开；token 10 分钟一次性，应用前重算课表/规则/设置/锁定/授课任务指纹。
- **继续优化**：`sourceDraftId` 显式模式，启动时冻结来源快照并校验 `weeklyLessons/lessonIndex/班级/课程/教师`；锁定课用求解硬约束保持时间；新稿另建、原稿不动；完成后按整稿校验返回 `resultPublishable/resultBlockers`，任务完成不再等于可发布。
- **比较**：`assignmentId:lessonIndex` 配对，详情 DTO 已暴露 `lessonIndex`；只统计未锁变化，锁定课变化单独红字提示。

## 3. 验证证据

### 后端

```bash
cd /e/paiban-wt/draft-swap-locks
source .tools/env.sh
cd backend && ./mvnw -B verify
```

结果：`Tests run: 1195, Failures: 0, Errors: 0, Skipped: 8`，`BUILD SUCCESS`。
8 个跳过项为 4 个 `PostgresContainerIT`（本机无 Docker）、2 个 `DeepSeekManualIT`、2 个 `PerformanceBenchmarkIT`；真实 PostgreSQL 已由 PR #22 的 backend CI job 实跑通过。

新增/重点覆盖：
- `DraftLockIntegrationTest`：双线程持有草稿行锁时，锁定写请求确实等待；版本过期/混合批次零写入。
- `DraftValidatorChangeAssessmentTest`：聚合桶 2→3 节、配对冲突换对手均判为加重；修旧不新增允许。
- `DraftCyclePlanIntegrationTest`：三课环、token 重放/过期/伪造、锁定与授课任务变化使预览失效、并发旧版本只落一次。
- `SourceDraftOptimizationIntegrationTest`：显式来源、同科多节 `lessonIndex`、教师保留、全锁拒绝、锁定冲突不宣称可发布、取消无新稿。

### 前端

```bash
cd /e/paiban-wt/draft-swap-locks/frontend
npm run lint && npx vue-tsc -b && npx vitest run
```

结果：lint/typecheck 通过；`Test Files 71 passed`、`Tests 549 passed`。

### 端到端

`scripts/verify.sh --full` 的 E2E 阶段跑出 `160 passed / 1 failed`，唯一失败是 `admin-reschedule.spec.ts` 的 `page.goto('/login')` 在 180 秒内未完成（无关导航超时）。

随后在同一提交上单独执行整套 Playwright：

```bash
cd /e/paiban-wt/draft-swap-locks && bash scripts/e2e.sh
```

结果：`161 passed (16.4m)`，`exit 0`。失败用例未复现，且所有本次新增 spec（`draft-drag`、`draft-locks`、`draft-optimize`、`settings-policy`）均通过。

### 复审后最终修复

Fresh Review 发现连续撤销两次旧 move/swap 时第二次仍使用第一次写入的版本号，已改为使用当前版本（`context.expectedVersion ?? lastVersion ?? entry.version`），并补“连续两次 swap 后连续撤销”单测；方案应用的审计改为记录真实 Principal。最终修复后定向复核：`useDraftAdjust.spec.ts` 26 项通过、`DraftChangeIntegrationTest` + `DraftCyclePlanIntegrationTest` 18 项通过、`draft-drag.spec.ts` E2E 8/8 通过；完整套件未再重复 40 分钟全量，生产差异仅这两处。

### 2026-11-03 生产反馈修复：同班拖拽被别班课程误拦

- 现象：真实课表里目标时间常有其他班的课，旧逻辑把“同一时间存在任何别班课”当成跨班落点，导致同班 A→B 拖拽直接交换也被拦，只能绕到“换课”按钮。
- 根因：`useDraftDrop` 用全量 `detail.lessons` 判断落点，未区分“用户在当前视图里实际看到的课”。
- 修复：网格 drop 时携带目标格可见的 `lessonIds`；落点判断只认可见课——有 1 节同班课则直接交换，没有任何可见课则普通移动（别班同时间有课是正常的），只有可见的别班课才提示走显式入口。
- 去重：同班交换只保留拖拽；“换课”按钮改为“跨班换人”，候选抽屉只列跨班教师交换；“选择位置”保留为键盘/点击等价入口。
- 证据：新增 E2E `同班交换不受同一时间其他班课程影响（真实课表常见）`（修复前无 `/changes` 请求、修复后 200 并完成交换），`draft-drag` 9/9、`swap` 4/4、`draft-locks` 8/8；前端全量 71 文件 / 553 单测通过。
- 截图：[拖拽重叠场景](acceptance/draft-drag-overlap.png)、[跨班换人入口](acceptance/draft-cross-class-entry.png)。
- 状态：已在本地修复分支验证；截至本记录**尚未合并 main、未部署生产**。

### 仓库级检查

`check-module-boundaries.sh`、`check-migrations.sh`、`check-sfc-size.sh`、`check-e2e-helpers.sh`、`check-blank-checks.sh`、`check-ticket-status.sh` 在门禁中通过。

## 4. 两轴复审与修复

### Standards

- E2E 公共 helper 收敛到 `helpers.ts`，删除 `draft-drag`/`draft-locks`/`draft-optimize`/`swap` 的重复定义；无写入拖拽包 `toPass` 重试。
- `useDraftActions.rename` 不再把服务端错误当“取消”吞掉；比较加载失败显示 `serverMessage`。
- `useDraftLocks` 解锁未排课节不再比服务端更严。
- `DraftChangeResult` 补 `tokenUsed`，过期的 `lessonIndex` 注释更新。

### Spec

- 违规“加重”按稳定 key + weight 判断；聚合桶 2→3 节、配对冲突换对手都会拒绝。
- 旧 move/swap 撤销携带写入产生的 `expectedVersion`，过期撤销 409。
- 来源快照 `capture` 使用草稿行锁，避免混读。
- 搜索预算有硬上限（节点 1,000,000 / 时间 5,000ms）。
- 发布 `publish` 先锁草稿行，避免校验后并发编辑。
- `/changes` 成功写审计。
- 详情 DTO 补 `lessonIndex`；旧 `scheduling.spec.ts` 改为 05 新交互；比较/锁定/审计口径对齐。
- 补 04 锁定/授课任务变化 token 失效测试、01 双线程行锁交错测试、07/04 教学班外键夹具修复。

## 5. 未验证 / 已知限制

- **真实 PostgreSQL**：本机无 Docker；PR #22 backend CI 已真实跑 `PostgresContainerIT` 通过，生产部署后 Flyway 44→47、schema 与四容器健康均已复核。
- **全量 E2E 偶发**：全量 `verify.sh --full` 出现过一次无关 `admin-reschedule` 导航超时；单独整套 E2E 在同提交 161/161 通过。未把该偶发记为产品缺陷。
- **发布已执行**：PR #22 已合并 main，生产已部署（Flyway 47）；未改生产课表、未触发排课、未发布新草稿。
- **旧客户端**：`DELETE` 已发布草稿状态码由 409 变 400；新增字段均为追加。

## 6. 提交与分支

- 集成分支：`codex/draft-swap-locks`
- 票分支：`codex/t01-persistent-locks`、`codex/t02-atomic-changes`、`codex/t03-shared-policy`、`codex/t04-cycle-plans`、`codex/t05-direct-drag`、`codex/t06-lock-ui`、`codex/t07-source-draft`、`codex/t08-optimize-ui`
- 复核修复提交见 `git log 4353a16..codex/draft-swap-locks --oneline`
