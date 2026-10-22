# 排课系统（Paiban）

导航页：先看这里，再翻代码。

## 文档入口

- `README.md` — 启动、测试、部署与实现说明
- `CODING_STANDARDS.md` — 评审时的判断标准（审计、事务、权限、测试、前端拆分）
- `docs/adr/` — 21 份决策记录，改行为前先找对应 ADR
- `GLOSSARY.md` — 领域术语：行政班、授课任务、基线、调代
- `.scratch/phase1-mvp/` — 一期规格、15 张票据与 PR 记录
- `.scratch/phase2-ai/` — 二期规格、6 张票据、跨票据接口契约
- `.scratch/phase3-feishu/` — 三期规格、5 张票据、飞书集成契约
- `.scratch/phase4-onboarding/` — 四期规格、5 张票据、引导与帮助中心契约（`frontend/src/onboarding`、`frontend/src/help`）

## 后端模块（backend/src/main/java/com/paiban）

| 目录 | 职责 |
|---|---|
| `identity/` | 登录、JWT、RBAC；权限判断走 `UserService.hasPermission` |
| `foundation/` | 学期/作息、年级/班级/教师/教室/课程、Excel 导入 |
| `scheduling/` | 授课任务、约束、Timefold 求解、草稿；任务生命周期在 `service/SchedulingService.java` |
| `timetable/` | 基线发布（发布后只读）、四视角视图、导出 |
| `substitution/` | 调代申请/审批、叠加视图 |
| `notification/` | 模板、站内通知、钉钉降级 |
| `integration/` | 钉钉（可选，关闭后系统完整可用） |
| `onboarding/` | 初始化向导 |
| `ai/` | 供应商/模型/任务分配、统一网关与策略、`ai_call_log`；`ai/imports/` 是三通道导入管道，`ai/history/` 是操作历史与导出 |

求解器只有一条线程（`common/config/AsyncConfig.java`），同一时刻只跑一个任务；取消与排队逻辑都绑在这个约束上。

## 本地开发

- 构建前 `source .tools/env.sh`：JDK 21、Maven、E 盘仓库；`scripts/verify.sh` 会在 `.tools/env.sh` 存在时自动 source
- `scripts/verify.sh` — 本地闸门，与 CI 同一条路径；`--full` 用隔离端口 18080/15173 跑 Playwright，不会复用本地 dev 服务
- 多票据 worktree：用 `scripts/worktree-add.sh <name> <branch> [base]` 创建、`scripts/worktree-remove.sh <name>` 清理；脚本会处理 `frontend/node_modules` junction，不要直接 `rm -rf`（会顺着 junction 删主仓库依赖）。Maven 用 `.tools/env.sh` 的绝对路径（worktree 内没有 `.tools`）
- 性能基准（ADR-0017，约 3 分钟）：`cd backend && ./mvnw -Dperf=true -Dtest=PerformanceBenchmarkIT test`
- Git Bash 下 `git show branch:path` 会被路径转换改写，前面加 `MSYS_NO_PATHCONV=1`
