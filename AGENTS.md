# 排课系统（Paiban）

导航页：先看这里，再翻代码。

## 文档入口

- `.scratch/HANDOFF.md` — **接手进度先读这份**：线上状态、红线、待办票与推进路线
- `README.md` — 启动、测试、部署与实现说明
- `CODING_STANDARDS.md` — 评审时的判断标准（审计、事务、权限、测试、前端拆分）
- `GLOSSARY.md` — 领域术语：行政班、授课任务、基线、调代
- `docs/adr/` — 架构决策记录（历史记录只读保留，改行为前先查对应 ADR）
- `docs/runbooks/deploy.md` — 部署跑册：备份、后台构建（该机 sshd 会掐掉前台长命令）、`up -d` 与验收判定
- `.scratch/` 历史归档（仅在追溯旧阶段背景时按需查看）：`phase1-mvp/`、`phase2-ai/`、`phase3-feishu/`、`phase4-onboarding/`、`workbook-background/`、`auto-schedule-publishable/`

## 后端模块（backend/src/main/java/com/paiban）

| 目录 | 职责 |
|---|---|
| `identity/` | 登录、JWT、RBAC；权限判断走 `UserService.hasPermission` |
| `foundation/` | 学期/作息、年级/班级/教师/课程、Excel 导入 |
| `scheduling/` | 授课任务、约束、Timefold 求解、草稿；任务生命周期在 `service/SchedulingService.java` |
| `timetable/` | 基线发布（发布后只读）、班级/教师/全校三视角视图、导出 |
| `substitution/` | 调代直接生效、置换环方案与独立校验、操作日志；纯求解器在 `substitution/plan` |
| `notification/` | 外发通知模板与钉钉通道（站内通知页已下线，表保留） |
| `integration/` | 钉钉（可选，关闭后系统完整可用） |
| `onboarding/` | 初始化向导 |
| `imports/` | 手动选列导入、导入配方 `ImportRecipeService` 与指纹匹配 |
| `ai/` | 供应商/模型/任务分配、统一网关与策略、`ai_call_log`；`ai/imports/` 是三通道导入管道，`ai_import_log.batches_json` 存批次数据 |

求解器只有一条线程（`common/config/AsyncConfig.java`），同一时刻只跑一个任务；课表 AI 分批解析另有独立线程 `importExecutor`（`ai/imports/AiImportService.java`、`ai_import_log.batches_json`）。

## 本地开发

- 开工、规划或建 worktree 前先 `git fetch origin main` 并确认基线包含 `origin/main`；首次检出执行 `git config core.hooksPath .githooks`（pre-commit 兼容 worktree，跑 ESLint、SFC 大小、ticket 状态、本地日期、e2e helper 与模块边界检查；pre-push 拦截落后于 `origin/main` 的推送）。
- 构建与验证默认先 `source .tools/env.sh`（JDK 21 / Maven / E 盘仓库），迭代期跑 `scripts/verify.sh --quick`，提交/发布前跑 `scripts/verify.sh --full`（或 `scripts/verify-run.sh <run-id> --full`，规范见 `docs/agents/verification.md`）。
- 提交时只 `git add <path>`（不用 `git add -A`），提交后立即跑 `git status --short` 检查是否漏文件或误带未跟踪改动。
- 遇到 Windows PowerShell / GitPortable Bash 传参、`doctor.sh --no-docker`、Surefire `-DforkCount=0`、低内存 Vitest 并发、Flyway 跳号、单条 E2E、性能基准或创建/删除 worktree（含 junction 安全移除）时，先读 [`docs/agents/local-dev.md`](docs/agents/local-dev.md)。

## Agent skills

- **Issue tracker**: `.scratch/<feature>/` 记录票据与规格；**`Status:` 与复选框可能是历史快照，开工前以 `HANDOFF.md`、`git log` 和线上事实为准**。详见 [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md)。
- **Triage labels**: `needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`。详见 [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md)。
- **Domain docs**: 根 `GLOSSARY.md` + `docs/adr/`。详见 [`docs/agents/domain.md`](docs/agents/domain.md)。
