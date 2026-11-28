# 本地开发与排障手册

日常高频命令留在根目录 [AGENTS.md](../../AGENTS.md)；遇到环境、Windows Shell、工作树、内存、Surefire 或迁移版本问题时读本页。

## Windows Shell 与前置自检

- 若系统 `bash` 指向未安装的 WSL，运行仓库脚本使用 `C:\Users\liu\AppData\Local\Programs\GitPortable\bin\bash.exe`。
- 从 PowerShell 调用需要引号或多行参数的 Bash 命令时，通过标准输入管道传入（`@' ... '@ | & "C:\Users\liu\AppData\Local\Programs\GitPortable\bin\bash.exe" -s`），避免 `bash -c "..."` 被 PowerShell 剥掉内层双引号。
- PowerShell 下直接调前端 CLI 时使用 `npx.cmd`（例如 `npx.cmd vitest run ...`），避免被 `npx.ps1` ExecutionPolicy 拦截。
- 无 Docker 机器上检查内存、端口与磁盘用 `bash scripts/doctor.sh --no-docker`（PowerShell 用 `doctor.bat --no-docker`）。默认 `scripts/doctor.sh` 在无 Docker 时返回退出码 `2`，不要用 `bash scripts/doctor.sh && ...` 串联构建或测试命令。

## 测试、内存与隔离端口

- `scripts/verify.sh` 阶段顺序为「快检查 → 前端 → 后端 → E2E」：`--quick` 仅编译后端、不跑 Playwright；`--full` 用隔离端口 `28080/25173`（可用 `PAIBAN_E2E_BACKEND_PORT` / `PAIBAN_E2E_FRONTEND_PORT` 覆盖）；`--deploy` 在 `--full` 基础上要求 Docker 运行 `PostgresContainerIT`。
- 最终冻结验证与收据入口：`scripts/verify-run.sh <run-id> [--full|--deploy|--quick]`，细节见 [`docs/agents/verification.md`](./verification.md)。直接调用 `verify.sh` 时将日志写进工作树 `.runtime/` 并单独保存 `$?`，避免 `| tee`/`| tail` 吞掉失败退出码。
- 单条 Playwright E2E：`bash scripts/e2e.sh <spec>`（自动 `source .tools/env.sh`、启用隔离端口并转换 Git Bash 下的 Maven 路径）。隔离实机验证也可用 `.agents/skills/verify-paiban/scripts/control-paiban.mjs`。
- 性能基准（ADR-0017，约 3 分钟）：`cd backend && ./mvnw -Dperf=true -Dtest=PerformanceBenchmarkIT test`。
- **低内存环境（常见 < 2GB）**：定向 Vitest 使用 `npx.cmd vitest run --no-file-parallelism <路径>`，全量门禁使用 `VITEST_MAX_WORKERS=4 bash scripts/verify.sh --full`；Vitest 与 E2E 不并发，重活前先跑 `bash scripts/doctor.sh --no-docker`。
- **Surefire / Mockito 抖动**：本机若 Surefire 分叉被杀或 Mockito 报「could not self-attach / inline mock maker」，定向测试加 `-DforkCount=0`，全量门禁加 `PAIBAN_MVN_EXTRA_ARGS=-DforkCount=0 bash scripts/verify.sh --full`；不手工拼 `JAVA_TOOL_OPTIONS`。
- **增量编译缺类**：全量测试期间不改源码或测试文件；若出现 `NoClassDefFoundError: com/paiban/support/ScheduleFixture`，先 `cd backend && ./mvnw -B -DskipTests compile` 再重跑。
- **发布重建 ID**：发布课表会重建 `timetable_entry` 的 id；测试里按槽位或课程回查（`ScheduleFixture.publishedEntries`），不复用 `DraftLesson` id。

## 工作树、分支与迁移

- 并行会话各自用 `scripts/worktree-add.sh <name> <branch> [base]` 建 worktree（默认基于 `origin/main` 且拒绝落后基线），主仓库只用于合并。
- 清理 worktree 必须用 `scripts/worktree-remove.sh <name>`（先卸载 `frontend/node_modules` 和 `.tools` 的目录联接再删除）；直接 `rm -rf` 会顺着 junction 删主仓库依赖。
- 串行依赖的票据链在集成分支上顺序推进；仅当多张票无冲突时并行开 worktree。每个 worktree 只跑定向测试；全量 `verify.sh` 在评审修复与文档落盘后、发布前统一跑一次，之后只允许文档提交。
- 并行分支新增 Flyway 迁移前先核对所有分支/worktree 已占用的最高版本并顺延续号（允许跳号）；`scripts/check-migrations.sh` 会在本地闸门与 CI 拦截重复版本。
- Git Bash 下执行 `git show branch:path` 时在命令前加 `MSYS_NO_PATHCONV=1`，防止 MSYS 自动改写路径。
