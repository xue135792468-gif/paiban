# 15: 契约收口：删除旧代码与数据

**What to build:** 在行为层已经收敛之后，物理删除不再使用的旧代码、路由、组件、约束类型、表和列：教室、晚自习、每日考勤、飞书、平台切换、初中/高中模式、通知中心、AI 操作历史、审批链/修订链/开课截止、锁定带来的限制（锁本身保留）。

**Blocked by:** 02, 04, 06, 07, 10, 11, 12, 13, 14.

**Status:** done

- [x] 教室相关代码、路由、组件、表/列删除；无残留引用
- [x] 晚自习/上午下午硬分区代码删除；作息只保留节次与时间
- [x] 飞书、平台切换、通知中心、AI 操作历史、模型评测代码删除
- [x] 审批链/修订链/开课截止/历史转换删除；调代只保留直接生效
- [x] 草稿锁定保留，但“有锁不能删草稿”等限制删除
- [x] Flyway 新迁移只加不改；旧数据备份可用
- [x] 删除后全量测试通过

**Notes:** 这是最后一步，先冻结 1–2 个月确认没人需要再物理删。禁止改写历史迁移。

---

## 实现记录（2026-11-05，用户确认后开工）

**基线**：`main @ 50ac999`（PR #27 已合并）。**实施分支**：`feature/product-trim-contract-15`，worktree `E:/paiban-wt/trim-contract-15`（主仓库 main 未直接改动）。

### 8 问定稿（用户逐条拍板，优先于票面字面）
| | 决定 |
|---|---|
| Q1 | 只留 PDF 导出（班级/教师/全校，去掉教室端点）；只读 CLI 整套删 |
| Q2 | `Course.requireOutdoor` 直接删 |
| Q3 | `SPORT_NOT_FIRST_AFTERNOON` 退役，约束目录 19→18 类 |
| Q4 | 导入里「跳过考勤 sheet」关键词不动（web 无考勤实现） |
| **Q5** | **开课截止 `deadlineAt` 整套保留** —— 推翻票面「删除开课截止」。查证它长在「直接生效」主链上，是防「改已上完的课」的时间护栏；两轴 code-review 都独立指出「自助路径漏了守卫」并已补回 |
| Q6 | 库里 7 类旧规则行留着可查，只删代码枚举 |
| Q7 | 通知表（`notification`/`notification_log`）先留着，只删页面/铃铛/查询接口/权限常量 |
| Q8 | NL 约束 3 张表不动（线上在用） |

### 落地范围
- **整文件删 121 个**：教室整套（Room/RoomRepository/RoomFill*/RoomSlot/RoomRequirement/RoomsView/RoomFillDialog）、飞书整套（20 后端类+前端页+8 测试）、平台切换、AI 操作历史、模型评测（7 类+2 测试+7 报告）、只读 CLI（主类+13 快照+脚本+跑册）、历史转换（后端+前端面板+e2e）、通知中心页、审批/修订配套测试、ADR 0027/0031/0018。
- **局部改 333 个**：作息去掉 `PeriodSection`/`period.section`；8 个退役枚举；`Course` 两字段；含锁不能删草稿（409+确认弹窗，锁本身保留）；审批链/修订链状态流转；飞书/初高中/通知中心的前端与配置；根文档（README/GLOSSARY/AGENTS/CODING_STANDARDS/installation-guide，ADR 历史一律不改写）。
- **V55 新迁移**：`V55__trim_legacy_columns_and_tables.sql`，先删索引/约束再删 13 列、2 表（`room`、转换表）。历史 V1–V54 未改。明确不 DROP：通知表、`ai_call_log`/`ai_import_log`、`recent_deletion`、`import_recipe`、`timetable_publish`、`deadline_at`、`approved_at`。
- **修复两处「删过头」**：① 删修订链时把「再次调整」能力一起删了 → 恢复为「直接生效 + 覆盖」（旧 EFFECTIVE 行转 CANCELLED + 写新行，不建修订链）；② 自助 `create` 直接生效丢了开课截止 → 补回 `DeadlineChecker`。
- **兼容层**：`GenerateDefaultPeriodsRequest` 同时支持 `periodCount` 与旧三段参数；默认作息恢复 `4+4+2`、三段起始时间 8:00/14:00/19:00，label 统一「第N节」。

### 门禁结果
- 后端全量单测 `./mvnw -B -DforkCount=0 test`：**1012 项，0 失败 / 0 错误 / 6 跳过**（跳过为无 Docker 的 `PostgresContainerIT`）。
- 前端 `npm run build` / `npm run lint` / `npx vitest run --no-file-parallelism`：**94 文件 681 用例全过**。
- 仓库级门禁：migrations / module-boundaries / sfc-size / local-dates / e2e-helpers / blank-checks / ticket-status / AI-HTTP-timeouts 全绿。
- `bash scripts/verify.sh --full`：见 `.scratch/product-trim/hold/15-deletion-inventory.md` 第 8 节最终数字。
- `/code-review` 两轴：Standards 2 硬违规（1 修、1 核实为既有不修）+ 3 判断项；Spec (a)0 /(b)0 /(c)1（已修）。详见 worktree `.scratch/trim15/code-review.md`。

### 边界
- 未碰票 16；未合并 main、未 push、未部署、未连生产数据库、未备份/删线上数据。
- 提交只含本票代码/迁移/文档；`.scratch/product-trim/` 与主仓库会话前既有的截图/原型改动不进提交。

状态于 2026-11-05 复盘时校准：票 15 已实现并随 `7f9a20f` 合并、V55 已在生产执行；复选框为历史快照，不再回填。
