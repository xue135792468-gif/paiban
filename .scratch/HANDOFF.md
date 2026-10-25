# 接手说明（2026-10-25 会话交接）

新会话先读这份，再按最下面的路线推进。工作仓库 `E:\排课系统`（`main` 已推到 `origin`），线上服务器 `~/paiban`。

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
| `11-save-periods-breaks-drafts` | 保存作息表会让所有草稿"找不到课节"，无提示 | ready-for-agent |
| `12-import-classes-zero-students` | 导入建班人数=0，之后班级改不动；容量约束失效 | ready-for-agent |
| `13-draft-validation-vs-publish` | 草稿校验说通过、发布却拒绝（两套标准） | ready-for-agent |
| `07-rooms-before-publish` | 发布前没有教室，导入完发布不了；要一键补教室 | ready-for-agent |
| `08-fill-missing-periods` | 文件里的节次比作息多，只能手工加格子 | ready-for-agent |
| `09-flag-suspicious-teacher-names` | "信息技术"这类占位名被当成老师 | ready-for-agent |
| `10-multiple-constraint-targets` | 约束一条只能设一个对象；页面太挤；要 +/− 与折叠 | needs-info（2 个问题待用户答） |
| `14-natural-language-constraints` | 自然语言生成约束：只有灰开关，功能没实现 | needs-triage（构想） |
| 票 02 遗留 | 整本导入没有批次进度/停止/重试（同步跑完） | 已记录，未做 |

票据都在 `.scratch/phase11-real-workbook-import/issues/`；规格与 ADR 在该目录 `spec.md` 和 `docs/adr/0026-real-workbook-import.md`。

## 新会话怎么推进（按 ask-matt 的路由）

- **07 / 08 / 09 / 11 / 12 / 13 = 已 agent-ready 的票，不需要 grill，也不要 triage。**
  直接 `/implement` 逐票（先 11，风险最高），或把这 6 张装成一批用 `/implement-spec` 一次跑完、一条集成分支上一版。每票先写测试；改完跑 `scripts/verify.sh --full`，再走部署脚本。
- **10 = 需要用户拍板的产品/界面决定。**
  开工前先答票里那 2 个问题（卡片 vs 表格、阈值覆盖规则）。布局建议先 `/prototype` 出可点的页面小样给用户看，顺眼后再 `/to-spec` → `/to-tickets` → `/implement-spec`。不需要整场 grill，语义已在票里写定。
- **14 = 新功能，需要先问清楚。**
  用 `/grill-with-docs`（在 repo 里跑，会沉淀进 GLOSSARY/ADR）把这几点问定：只给建议还是直接改、模型换不换、怎么验收（10–20 句真实说法的 eval）。之后 `/to-spec` → `/to-tickets` → `/implement-spec`。**依赖 10 完成**。
- **收尾跑一次 `/retro`**：把"保存作息表毁草稿"这类坑变成确定性检查（例如给 `draft_lesson.period_id` 加外键/一致性校验），别只写在文档里。

## 交差门槛（每票）

1. `cd backend && ./mvnw -B test` 全绿；前端 `npm run lint && npm run build && npm run test:unit`。
2. 用户可见的改动：`scripts/verify.sh --full`（含 Playwright）全绿，关键页面截图留存。
3. 部署：`cd ~/paiban && git pull --ff-only` → 重建 backend/frontend → `up -d`，确认 Flyway 版本与四容器 healthy。
4. 上线后冒烟：导入 → 补教室 → 发布 → 调代一单（申请 SWAP 必须带 `targetPeriodId`）。
5. 回写当天日志（`D:\work Buddy-cn\2026-10-11-22-22-40\.workbuddy\memory\`）与 `MEMORY.md`，重跑 `~/ops/state-snapshot.sh`。

## 需要用户先回答

1. 先修 6 个坑（11→12→13→07→08→09）再谈约束页，还是先看约束页原型？
2. 约束页：卡片列表还是表格？
3. 自然语言约束：记为下一期，还是本期就要？
