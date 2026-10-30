# 接手说明（2026-10-25 会话交接）

> **2026-10-30 工作台 UI 重做已上线（三票全部完成，main `f31bb90`，Flyway v43）**：用户选定 **v3a 留白版** 原型（`C:\Users\liu\.zcode\v2\visualizations\742542c2d05adefebf901d486182e3b320ecfaf178ae5e74b62defbfd3e8ebd0\v3a-airy.html`，同目录 v3b 仅作对照）。规格与 3 张票在 [.scratch/workbench-ui-redesign/](workbench-ui-redesign/spec.md)：01 向导骨架+选课/动作表单（含拖拽预选）→ 02 确认页（结论/跨科门/课表变化可视化/提交确认）→ 03 五个既有 e2e 迁移+无障碍/320px+帮助文案+全量门禁。**后端接口、数据库、业务规则零改动**。原型证据：v3a/v3b 各 40/40 交互断言、0 控制台错误；三个布局 bug（切换入口被推到首屏外、原因框撑高、序号压字）已修并在真实浏览器复验。**2026-10-30 追记：三票已实现、评审、合并 `main`（`f31bb90`，9 个提交）并部署到生产。** 实现于 `feature/workbench-ui-redesign`（`438938a..f31bb90`，实现说明见各票末尾）；两轴 code-review 后修复了凭证过期字段（改用 `expiresAt`）、修订横幅类型丢失、e2e helper 去重与票状态门禁。门禁：本地 `verify.sh --full` 退出码 0（后端 1012、前端单测 438、Playwright 137，约 36 分钟），main CI 四 job 全绿（backend 含真实 PostgreSQL `PostgresContainerIT`，替代本机缺 Docker 的 `--deploy` 门禁）。**部署记录**：备份 `~/backups/paiban/paiban-pre-20261030T004707Z.sql.gz`（73663B，gzip 校验通过）；服务器 `git pull --ff-only` 到 `f31bb90`，后台重建 backend/frontend 后 `up -d`；Flyway 保持 **v43**（本次无迁移）；四容器 healthy；本机与公网 health 200；冒烟：线上 bundle 含 `wizard-stepper` 与「三步向导」帮助文案、旧界面标记（`generate-swap-plans`）0 处，三条工作台路由 401（已注册）；tester2 登录后只读验证：48 位教师、何昌盛《体育与健康》八（13）2026-10-28 课 → 代课候选 47（13 忙碌）、调课目标槽位 8，均 200。生产数据未变（546 节已发布课表、0 条调代申请、1 个学期）。截图在 `.scratch/workbench-ui-redesign/acceptance/`；后端/数据库/接口零改动。**同日补充（`06b1327`）：调代新增「今天及以后」限制**——工作台第①步默认本地今天（修复 `today()` 用 UTC 导致凌晨显示昨天的 bug），日期选择器与过去课卡禁用（半透明、不可点、不可拖），教师自助「调代课」表单同样限制；`admin-reschedule` 的旧 UI 路径（点过去课卡触发 409）随新限制改为断言不可点，API 级 409/零写入原样保留。门禁 1012/441/138 退出码 0；main CI 四 job 全绿（上一轮纯文档提交的 backend 红为 `SchedulingIntegrationTest.cancellingAJobFreesTheSolverForTheNextSubmission` 时序 flake，本轮已绿）；备份 `paiban-pre-20261030T034948Z.sql.gz`（73828B，gzip 通过）；部署后 Flyway 43、四容器 healthy、两处 health 200、bundle 含新限制文案且旧标记 0、生产数据未变（546 节 / 0 调代 / 1 学期）。**

> **2026-10-29 管理员调代工作台九票已实现、评审、合并 `main`（PR #20，merge `94f0372`）并部署到生产（服务器 `4e2acef`，Flyway v43）**：01 日期实例（V38）、02 历史转换预检（V39）、03 管理员代课/预览/亲自确认（V40）、04 修订链换代课人/恢复基线（V41）、05 同班调课明确目标日期（V42）、06 拖拽入口、07 已调课实例再调整与冻结、08 全站独立滚动、09 帮助与集成验收。两轴 code-review 后的修复：批准路径一律受开课截止（含首次批准）、候选方案按操作者隔离（V43）、SUPERSEDED 历史展示（记录接口 `status=ALL` 可选）、h2 控制台按环境放行、共享 `SubstitutionLookups` 去重、前端权限常量集中；门禁修复：e2e 种子改未来学期、onboarding 前置、滚动/重姓 flaky 加固。最终门禁：后端 1012（0 失败）、前端 417、Playwright 117 全绿；PR CI 与 main CI 四 job 全绿（backend 含真跑 `PostgresContainerIT` 4 条）。**部署记录**：备份 `~/backups/paiban/paiban-pre-20261029T103312Z.sql.gz`（72011B，gzip 校验通过）；服务器部署 `HEAD=4e2acef`（其后仅 2 个纯文档/测试提交，服务器已 `git pull` 到 `7c475b9`，无需重建）；Flyway 自 37 应用 V38–V43 共 6 个迁移至 **v43**；四容器 healthy；本机与公网 health 200；冒烟确认三条新路由已注册（401 非 404）且前端 bundle 含工作台/帮助文档。**生产历史调代转换未执行**（独立教务确认门：预检 → 差异清单 → 教务签字）。**回滚注意**：迁移均为加列/放宽约束，代码可回退；但部署后新产生的调代行带 `lesson_date`，旧代码不识别，回滚需同时清理部署后新增调代行（当前 0 行）。修复明细见 [review-fixes.md](admin-substitution-workbench/review-fixes.md)，验收截图在 `admin-substitution-workbench/acceptance/`。生产历史调代预检（2026-10-29，只读）：旧格式记录 count = 0；预检 summary = {'totalRows': 0, 'convertible': 0, 'blocked': 0, 'alreadyConverted': 0, 'changes': 0}；无写入。

> **2026-10-29 管理员调代工作台规划已确认并发布，尚未实现**：见 [正式规格](admin-substitution-workbench/spec.md)、[九票依赖总览](admin-substitution-workbench/ticket-plan.md) 与 [决策记录](admin-substitution-workbench/decisions.md)。支持管理员代任意教师发起/亲自确认、左右课表对照、发布后同班环拖拽，以及开课前再次调整；全站独立滚动单独成票。初始 frontier 为 **01、08**，其余按 Blocked by 推进。ADR-0030 已接受；日期实例语义与现有持续覆盖不同，历史转换须先出差异清单并完成教务数据确认。本次仅发布文档和九张 ready-for-agent 票据，未认领、未编码、未部署；原线上状态保持下述记录的证据边界。

> **2026-10-28 票 04（重复提交单飞）已实现、评审、合并并部署（代码 `a355dde`，Flyway v37，无迁移）**：`COMMITTING` 短事务认领 + 失败释放 + 启动恢复；两轴独立评审无阻塞；官方门禁后端 869（5 skipped）、前端 401、Playwright 91 全绿；CI 四 job（含真实 PostgreSQL `PostgresContainerIT`）全绿。部署备份 `~/backups/paiban/paiban-pre-20261028T041007Z.sql.gz`；四容器 healthy、两处 health 200。生产冒烟：1 行工作簿导入 → 第一次确认 200（草稿 6）、第二次 409、只新增 1 份草稿。详见[票 04](workbook-background/issues/04-commit-single-flight.md)。

> **2026-10-27 票14 已实现、合并 `main` 并部署（代码 `b21cb9a`，Flyway v37）**：正式规格与 12 张实现票（`.scratch/natural-language-constraints/`）完成 01→12：课程禁排、25 类逐项控制、配置版本/冲突、AI 只读建议、C01–C22 评测工具、校对加入与审计、跨设备工作区。合并后全量门禁后端 860、前端 401、Playwright 90 全绿；真实 PostgreSQL 临时库 V1–V37 与 CI `PostgresContainerIT` 均通过；四容器 healthy、两处 health 200。真实评测已 **PASSED**：`gemini-3.8-flash-low` **54/54、12/12**；任务已绑定、`ai.features.natural-language-constraints=true`、`/api/constraint-proposals/availability` 返回 `available:true`。详见[票12交付记录](natural-language-constraints/issues/12-integrate-and-accept.md)；票13 结构重构已完成（共享匹配器/类型化参数/审计服务/面板/helper，门禁 863/401/90），剩余核心规划/持久化/模板拆分见票14。

> **2026-10-26 整本导入后台化（02 遗留）已实现、合并 `main` 并部署（代码 `b21cb9a`，Flyway v37）**：三张草案 01 → 02 → 03 已完成，每票 TDD、独立评审。上传立即返回 `PARSING`，规划/取值在 `importExecutor` 串行；计划与运行身份落库（Flyway **V30**：`plan_json` / `plan_version` / `run_id`）；停止保留已完成范围并把未覆盖范围列为可重试失败；重试只重放失败批次、成功批次不重调模型；晚响应只进审计。合并后全量门禁通过；备份 `~/backups/paiban/paiban-pre-v37-20261027T105735Z.sql.gz`；四容器 healthy、两处 health 200。评审遗留的票 04（重复提交单飞）已于 **2026-10-28** 实现：`COMMITTING` 短事务认领 + 失败释放 + 启动恢复，`WorkbookCommitRaceTest` 4 例覆盖；读 `.scratch/workbook-background/spec.md` 与 `issues/`，决策见 `docs/adr/0029-workbook-background-runs.md`。

> **2026-10-25 准备复查更新：先读 [准备审查入口](phase11-real-workbook-import/preparation/README.md)。**
> 用户已暂停本会话修复，改为完成准备材料、交由新的 agent 执行。以下旧路线中的待问与 ready 状态须以新入口为准：六项修复已有补充规格；票 10 已问定并拆成 6 票，先做原型等用户确认；整本后台化另备下一批 3 张草案；14 下一期再 grill。
> 票 11 有未提交、未通过验证的候选代码留在 `E:\paiban-wt\import-repairs`，不是已完成修复。勿忽略未跟踪的外键迁移或把现有失败测试当作绿色证据。新入口记录了基点、失败原因和接手要求。
>
> **2026-10-25 执行结果（六项修复已上线）**：11 → 12 → 13 → 07 → 08 → 09 已全部实现于集成分支 `feature/phase11-repairs`，每票独立 worktree + TDD + 独立合并，末轮过 code-review（Standards/Spec 两轴）并集中修复一次。门禁：后端 `./mvnw -B test` 634 绿、前端 lint/build/unit 241 绿、SFC 体积检查通过、`scripts/verify.sh --full` 全绿（Playwright 50 例，含新增 5 例）。**已合并 `main`（`a8babf5`）并部署上线**：Flyway 到 **v25**（V24 `draft_lesson`→`period` 外键、V25 0 人班级数据修复），四容器 healthy；部署前在服务器临时库上做了真实 PostgreSQL 的全量迁移验证，生产 preflight 悬空引用 0，备份 `~/backups/paiban/paiban-pre-v24-20261025T130850Z.sql.gz`。线上冒烟 15 项全绿（导入 → 补教室 → 发布 → 调代 SWAP → EFFECTIVE）。`E:\paiban-wt\import-repairs` 的候选代码已被正式实现取代，可删。

新会话先读这份，再按最下面的路线推进。工作仓库 `E:\排课系统`（`main` 已推到 `origin`），线上服务器 `~/paiban`。

准备材料位于主仓库的 `codex/backlog-preparation` 分支；线上状态和下面的数据库数字为原交接快照，执行前重新只读核对。

## 现在的状态

- **代码**：`main` 已含十一期（真实工作簿导入）+ 真实模型容错 + 三个审计修复；线上跑 `abcda58` 之后的版本（代码等同 `e9834b7` 之后的 `37090be`，文档提交无需重建）。
- **数据库（测试数据，用户同意保留）**：学期 1（2025-09-01~2026-01-20，当前）+ 40 节作息、48 教师、16 课程、14 行政班（人数已被临时改成 45）、0 教室、2 份导入草稿（各 546 节）、0 条已发布课表。
- **账号**：`admin`（原口令未改）；测试账号 `tester1..tester4`（admin）、`teacher1`、`head1`，口令在服务器 `~/ops/paiban-test-accounts.txt`（600）。
- **AI**：`data_import` = `deepseek / cn:minimax-m3.1-flash`（vision=true）。这个 flash 模型不稳定，管线已加容错（NDJSON、`name`、中文类别、宽表降级）。

## 红线（踩过，别再踩）

1. **有草稿的学期不要点「保存作息表」**：`replacePeriods` 会删掉重建 `period` 行（id 全变），`draft_lesson.period_id` 没有外键 → 所有草稿静默失效（实测 1092 行悬空）。这条就是票 `11`。
2. 线上改数据前先备份：`sudo -n docker exec paiban-postgres-1 pg_dump -U paiban -d paiban | gzip > ~/backups/paiban/paiban-<UTC>.sql.gz`。
3. 发布课表要求每节课都有教室；真实文件没有教室列，先补教室（票 `07`）。
4. SSH 会限流：远程步骤合并成一次连接；长任务用 `setsid nohup` + 日志。

## 待办（按建议顺序）

| 票 | 内容（大白话） | 状态 |
|---|---|---|
| `11-save-periods-breaks-drafts` | 保存作息表会让所有草稿"找不到课节"，无提示 | 已上线（2026-10-25，main `a8babf5`） |
| `12-import-classes-zero-students` | 导入建班人数=0，之后班级改不动；容量约束失效 | 已上线（2026-10-25，main `a8babf5`） |
| `13-draft-validation-vs-publish` | 草稿校验说通过、发布却拒绝（两套标准） | 已上线（2026-10-25，main `a8babf5`） |
| `07-rooms-before-publish` | 发布前没有教室，导入完发布不了；要一键补教室 | 已上线（2026-10-25，main `a8babf5`） |
| `08-fill-missing-periods` | 文件里的节次比作息多，只能手工加格子 | 已上线（2026-10-25，main `a8babf5`） |
| `09-flag-suspicious-teacher-names` | "信息技术"这类占位名被当成老师 | 已上线（2026-10-25，main `a8babf5`） |
| `10-multiple-constraint-targets` | 约束多条、对象覆盖与折叠布局 | 已上线（2026-10-26 部署；constraint-entries 六票全部 done，证据见 `.scratch/constraint-entries/issues/` 与 git log） |
| `14-natural-language-constraints` | 自然语言生成约束＋课程禁排＋逐项控制＋跨设备恢复 | 已上线并放行（2026-10-27，main `b21cb9a`，Flyway v37）；3.8-low 54/54、12/12，任务已绑定、开关已开 |
| 票 02 遗留 | 整本导入没有批次进度/停止/重试（同步跑完） | 已上线（2026-10-27，main `b21cb9a`，Flyway v37）；见 `.scratch/workbook-background/` |

票据都在 `.scratch/phase11-real-workbook-import/issues/`；规格与 ADR 在该目录 `spec.md` 和 `docs/adr/0026-real-workbook-import.md`。

## 新会话怎么推进（按 ask-matt 的路由）

- **六项修复已执行完毕。**
  11 → 12 → 13 → 07 → 08 → 09 已全部实现、评审并**上线**（main `a8babf5`，Flyway v25）。不要再重复实现；线上问题按「实现说明」与当天日志排查。各票实现说明写在 `.scratch/phase11-real-workbook-import/issues/*.md`。
  部署前须知：V24 给 `draft_lesson.period_id` 加外键，若线上存在历史悬空引用，迁移会直接失败（有意闸门）；迁移注释里带 preflight 查询，必须先查、按记录修复后再重建。
- **10 的旧问题已答，六张新票已全部实现并上线（2026-10-26）。**
  读 `.scratch/constraint-entries/spec.md`。constraint-entries 六票全部 done，2026-10-26 已部署，证据见 `.scratch/constraint-entries/issues/` 与 git log；无需再排期或重复实现。
- **02 整本后台化已上线（2026-10-27，main `b21cb9a`，Flyway v37）。**
  读 `.scratch/workbook-background/spec.md` 与 `issues/`；实现说明在各票末尾，决策见 ADR-0029。评审遗留的票 04（重复提交单飞）已于 2026-10-28 **合并并部署（main `a355dde`）**，生产冒烟 200/409/一份草稿通过。
- **14 已上线（2026-10-27，main `b21cb9a`，Flyway v37）。**
  读 `.scratch/natural-language-constraints/spec.md`、`ticket-plan.md` 与 `issues/01..13`；真实评测已 **PASSED**（`gemini-3.8-flash-low` 54/54、12/12），任务已绑定、功能开关已开；不要用替身结果冒充。
- **票13 结构重构已完成并部署（2026-10-27，main `6ce8a2c`）**：共享 `ConstraintEntryMatcher`、`ConstraintParams`、`ConstraintRunCompletion`、`ConstraintAuditService`、AI 面板状态映射、E2E `pickOption` 收口；完整门禁 863/401/90。剩余“规划/持久化/模板”核心拆分见 [票14](natural-language-constraints/issues/14-core-service-split.md)（**2026-10-28 实现完成，分支 `feature/ticket-14-core-service-split`（`f87f1b6`），待合并/部署；纯结构重构，门禁 872/401/91**）。
- **Retro 已完成（2026-10-28）**：见 [.scratch/retro/2026-10-28-ticket04.md](retro/2026-10-28-ticket04.md)。本轮把票据状态检查、节次别名、surefire self-attach、并发测试 seam 规则、工作簿冒烟配方与 E2E 选择器稳定化落成了代码/脚本；剩余的是票 14 拆分与十期钉钉评审后续。

## 交差门槛（每票）

1. `cd backend && ./mvnw -B test` 全绿；前端 `npm run lint && npm run build && npm run test:unit`。
2. 用户可见的改动：`scripts/verify.sh --full`（含 Playwright）全绿，关键页面截图留存。
3. 部署：`cd ~/paiban && git pull --ff-only` → 重建 backend/frontend → `up -d`，确认 Flyway 版本与四容器 healthy。
4. 上线后冒烟：导入 → 补教室 → 发布 → 调代一单（申请 SWAP 必须带 `targetPeriodId`）。
5. 回写当天日志（`D:\work Buddy-cn\2026-10-11-22-22-40\.workbuddy\memory\`）与 `MEMORY.md`，重跑 `~/ops/state-snapshot.sh`。

历史归档：2026-10-25 及之前的确认记录见 git 历史。
