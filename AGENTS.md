# 排课系统（Paiban）

导航页：先看这里，再翻代码。

## 文档入口

- `.scratch/HANDOFF.md` — **接手进度先读这份**：线上状态、红线、待办票与推进路线
- `README.md` — 启动、测试、部署与实现说明
- `CODING_STANDARDS.md` — 评审时的判断标准（审计、事务、权限、测试、前端拆分）
- `docs/adr/` — 21 份决策记录，改行为前先找对应 ADR
- `GLOSSARY.md` — 领域术语：行政班、授课任务、基线、调代
- `.scratch/phase1-mvp/` — 一期规格、15 张票据与 PR 记录
- `.scratch/phase2-ai/` — 二期规格、6 张票据、跨票据接口契约
- `.scratch/phase3-feishu/` — 三期规格、5 张票据、飞书集成契约
- `.scratch/phase4-onboarding/` — 四期规格、5 张票据、引导与帮助中心契约（`frontend/src/onboarding`、`frontend/src/help`）
- `docs/runbooks/deploy.md` — 部署跑册：备份、后台构建（该机 sshd 会掐掉前台长命令）、`up -d` 与验收判定

## 后端模块（backend/src/main/java/com/paiban）

| 目录 | 职责 |
|---|---|
| `identity/` | 登录、JWT、RBAC；权限判断走 `UserService.hasPermission` |
| `foundation/` | 学期/作息、年级/班级/教师/教室/课程、Excel 导入 |
| `scheduling/` | 授课任务、约束、Timefold 求解、草稿；任务生命周期在 `service/SchedulingService.java` |
| `timetable/` | 基线发布（发布后只读）、四视角视图、导出 |
| `substitution/` | 调代申请/审批、置换环方案与独立校验、只读 CLI；纯求解器在 `substitution/plan` |
| `notification/` | 模板、站内通知、钉钉降级 |
| `integration/` | 钉钉（可选，关闭后系统完整可用） |
| `onboarding/` | 初始化向导 |
| `ai/` | 供应商/模型/任务分配、统一网关与策略、`ai_call_log`；`ai/imports/` 是三通道导入管道，`ai/history/` 是操作历史与导出 |

求解器只有一条线程（`common/config/AsyncConfig.java`），同一时刻只跑一个任务；取消与排队逻辑都绑在这个约束上。

课表导入的分批解析另有一条线程（`importExecutor`），与求解器互不影响；会话状态机在 `ai/imports/AiImportService.java`，批次数据在 `ai_import_log.batches_json`（迁移 V20、V21）。

## 本地开发

- 构建前 `source .tools/env.sh`：JDK 21、Maven、E 盘仓库；`scripts/verify.sh` 会在 `.tools/env.sh` 存在时自动 source
- 首次检出后执行 `git config core.hooksPath .githooks`：pre-commit 只对 staged 的 `frontend/**` 跑 eslint（`npm run lint` 覆盖全量，本地钩子只挡增量）；pre-push 在分支未包含 `origin/main` 时阻止推送。
- `scripts/verify.sh` — 本地闸门，与 CI 同一条路径；`--full` 用隔离端口 28080/25173 跑 Playwright（可用 `PAIBAN_E2E_BACKEND_PORT` / `PAIBAN_E2E_FRONTEND_PORT` 覆盖），`--quick` 只做后端编译 + 前端 lint/build/unit，迭代期用
- 单条 E2E 用 `scripts/e2e.sh <spec>`：自动 source `.tools/env.sh`、隔离端口，并把 Git Bash 下的 Maven 路径转成 Windows 绝对路径
- 多票据 worktree：用 `scripts/worktree-add.sh <name> <branch> [base]` 创建、`scripts/worktree-remove.sh <name>` 清理；脚本会联接 `frontend/node_modules` 和 `.tools`（`--full` 需要），不要直接 `rm -rf`（会顺着 junction 删主仓库依赖）
- 多票并行时每票只跑定向测试；全量 `verify.sh` 只在集成分支合并完成后跑一次，合并时只跑受影响范围。
- 串行依赖的票据链在集成分支上顺序实现；只有真正可并行 frontier 才开 worktree
- 多个会话并行时各自开 worktree，主仓库只用于合并；提交只 stage 本任务路径（用 `git add <path>`，不要 `git add -A`），别把另一个会话的未提交改动带进自己的提交
- 性能基准（ADR-0017，约 3 分钟）：`cd backend && ./mvnw -Dperf=true -Dtest=PerformanceBenchmarkIT test`
- 提交后跑 `git status --short`：非空先确认是否漏 stage；按目录 stage 容易漏掉同任务里散落在其他目录的单文件
- 发布课表会重建 `timetable_entry` 的 id；测试里按槽位/课程回查（`ScheduleFixture.publishedEntries`），不要复用 `DraftLesson` id
- Git Bash 下 `git show branch:path` 会被路径转换改写，前面加 `MSYS_NO_PATHCONV=1`

## Agent skills

### Issue tracker

票据与规格以本地 markdown 记录在 `.scratch/<feature>/`，每张票据一个文件。See `docs/agents/issue-tracker.md`.

### Triage labels

沿用五个默认 triage 角色（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）。See `docs/agents/triage-labels.md`.

### Domain docs

单上下文：根 `GLOSSARY.md` + `docs/adr/`。See `docs/agents/domain.md`.
