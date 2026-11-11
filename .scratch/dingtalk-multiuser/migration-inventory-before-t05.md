# 票 05 开工前数据库迁移占号盘点（只读快照）

生成时间：2026-11-11  
目标工作区：`E:/排课系统/.worktrees/dingtalk-integration`  
目标分支 / HEAD：`codex/dingtalk-multiuser` @ `3bc1c2f1c743bac6cd55fcc918cc2cf33b53b8f2`  
性质：只读机械盘点，为尚未开工的票 05 准备前置证据；不声明施工，不预占迁移，不修改任何源码。

---

## 1. 资源状态与环境检查

- 物理内存总量：约 16.49 GB
- 物理可用内存：约 3.57 GB（系统资源健康，无需并发重负载操作）
- 仓库 Worktree 数量：13 个

---

## 2. 盘点取证命令与范围

1. **Worktree 列表与未提交迁移文件排查**：
   ```powershell
   git worktree list --porcelain
   # 遍历 13 个 worktree 执行：
   git -C <worktree-path> status --porcelain backend/src/main/resources/db/migration
   ```
2. **全量分支 / 远端引用排查**：
   ```powershell
   git for-each-ref --format="%(refname)" refs/heads refs/remotes
   # 共检测到 177 个 refs
   # 对各 ref 执行 git ls-tree -r --name-only <ref> backend/src/main/resources/db/migration 并提取 V*.sql
   ```

---

## 3. 未提交迁移文件（Worktree 工作区扫描）

排查全部 13 个 worktree，仅发现 1 处未提交/未跟踪迁移文件：
- `E:/paiban-wt/import-repairs`:
  - `?? backend/src/main/resources/db/migration/V24__draft_lesson_period_reference.sql`（版本号 V24；主线程核实同名文件内容不同，不能据版本较低就判无冲突）
- 其余 12 个 worktree（包括主工作区 `E:/排课系统` 及当前集成工作区 `E:/排课系统/.worktrees/dingtalk-integration`）的 `db/migration` 目录均无未提交或未跟踪的 `V*.sql`。

---

## 4. 全分支迁移版本分布与最高版本

全量 177 个 local/remote refs 中，去重后的迁移版本总数为 61 个（V1 至 V62，历史存在个别跳号）。

### 最高 5 个版本汇总

| 版本号 | 迁移文件名 | 存在的分支引用数（部分代表分支） |
| :--- | :--- | :--- |
| **V58** | `V58__prebuilt_time_groups.sql` | 28 refs（含 `codex/dingtalk-multiuser`, `codex/dingtalk-t01` 等） |
| **V59** | `V59__bump_constraint_version_after_reset.sql` | 28 refs（含 `codex/dingtalk-multiuser`, `codex/dingtalk-t01` 等） |
| **V60** | `V60__roles_super_admin_school_admin_teacher.sql` | 19 refs（含 `codex/dingtalk-multiuser`, `codex/dingtalk-t01` 等） |
| **V61** | `V61__account_audit.sql` | 16 refs（含 `codex/dingtalk-multiuser`, `codex/dingtalk-t02` 等） |
| **V62** | `V62__app_user_login_type.sql` | 6 refs（含 `codex/dingtalk-multiuser`, `codex/dingtalk-t03`, `codex/dingtalk-t03-concurrency` 等） |

### 重复版本与冲突检查
- Gemini 原报告的“全仓无冲突”结论不予采信：文件名扫描不能证明内容相同。主线程发现旧 import-repairs 工作区未跟踪的同名 V24 与当前集成内容不同（见下）。该旧工作区不属于本次合并来源，未改动；候选 V63 不受它的版本高度影响，但这不代表旧分支可直接合并。

---

## 5. 盘点结论与下一版本建议

1. **真实最高已占号**：`V62`（`V62__app_user_login_type.sql`）。
   - 当前集成主线 `3bc1c2f`（分支 `codex/dingtalk-multiuser`）已合并 T03 并包含 V62。
   - 此前 T03 阶段盘点的 V61 记录已正式过期。
2. **候选下一版本**：`V63`。
3. **特别声明与边界**：
   - 本文件仅为 **2026-11-11 该时刻的静态快照**，不代表正式预占 V63。
   - 票 05 尚未开工。后续若正式进入票 05 施工并编写迁移脚本前，主线程仍须再次比对 worktree 及远程分支，核对是否存在并行漂移。

## 主线程复核（2026-11-11 20:14）

refs 数量独立复核为177。以 git hash-object 核对同名 V24：当前集成为 fa11f5c7dd2054b5e17e68328553330be9aea55a；E:/paiban-wt/import-repairs 未跟踪文件为 06b2f2867840b5722d7c161b9f6b148b5d286ed1，内容确实不同。本次不修改、不归并旧工作区文件。下一迁移仍须在开工时复查最高占号；这里只准备 V63 候选，未宣称旧分支的迁移安全。

## 03末尾刷新（2026-11-11约22:24）

Gemini3.8只读扫描报告：181 refs、14 worktrees（含物理未跟踪迁移），最高V62，V63未占用；历史refs另有V25/V28/V59同版本不同内容，旧import-repairs未跟踪V24仍保留。不得据此声称所有旧分支可无冲突合并。主线程独立核对当前集成物理V60/V61/V62、无V63；本次05唯一新迁移候选仍V63。子代理未修改文件、未跑测试或真实服务；这只是占号盘点，不是升级验收。
