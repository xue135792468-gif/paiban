# 接手说明（2026-10-25 会话交接）

> **2026-10-25 准备复查更新：先读 [准备审查入口](phase11-real-workbook-import/preparation/README.md)。**
> 用户已暂停本会话修复，改为完成准备材料、交由新的 agent 执行。以下旧路线中的待问与 ready 状态须以新入口为准：六项修复已有补充规格；票 10 已问定并拆成 6 票，先做原型等用户确认；整本后台化另备下一批 3 张草案；14 下一期再 grill。
> 票 11 有未提交、未通过验证的候选代码留在 `E:\paiban-wt\import-repairs`，不是已完成修复。勿忽略未跟踪的外键迁移或把现有失败测试当作绿色证据。新入口记录了基点、失败原因和接手要求。

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
| `11-save-periods-breaks-drafts` | 保存作息表会让所有草稿"找不到课节"，无提示 | ready-for-agent |
| `12-import-classes-zero-students` | 导入建班人数=0，之后班级改不动；容量约束失效 | ready-for-agent |
| `13-draft-validation-vs-publish` | 草稿校验说通过、发布却拒绝（两套标准） | ready-for-agent |
| `07-rooms-before-publish` | 发布前没有教室，导入完发布不了；要一键补教室 | ready-for-agent |
| `08-fill-missing-periods` | 文件里的节次比作息多，只能手工加格子 | ready-for-agent |
| `09-flag-suspicious-teacher-names` | "信息技术"这类占位名被当成老师 | ready-for-agent |
| `10-multiple-constraint-targets` | 约束多条、对象覆盖与折叠布局 | 已确认语义并拆 6 票，见 `.scratch/constraint-entries/`；先原型确认 |
| `14-natural-language-constraints` | 自然语言生成约束：只有灰开关，功能没实现 | 下一期，保留 grill 问题树，本轮不实现 |
| 票 02 遗留 | 整本导入没有批次进度/停止/重试（同步跑完） | 下一批草案见 `.scratch/workbook-background/`，仍未完成 |

票据都在 `.scratch/phase11-real-workbook-import/issues/`；规格与 ADR 在该目录 `spec.md` 和 `docs/adr/0026-real-workbook-import.md`。

## 新会话怎么推进（按 ask-matt 的路由）

- **本会话当前只准备，不执行修复。后续 agent 先读 preparation/README，再读 repair-spec。**
  11 / 12 / 13 是明确缺陷；07 / 08 的产品边界已在本轮问定并补规格；09 沿用只提示和入库后更名。保留六张原票，按 11→12→13→07→08→09 走 `/implement`＋TDD，不再重复 triage。先核对 11 的暂停候选，不能视为已完成。每票完整评审/验证后再按下方门槛交付。
- **10 的旧问题已答，使用独立规格与六张新票。**
  读 `.scratch/constraint-entries/spec.md`。第一票 `/prototype` 出可点页面，经用户确认才放行生产实现；其余按 Blocked by 顺序推进。需求准备完成不代表原型已批准。
- **02 整本后台化为下一批，仅有准备草案。**
  读 `.scratch/workbook-background/spec.md`；不勾掉原票遗留，不与六项修复混做。
- **14 下一期再 grill。**
  先读 `preparation/next-phase-questions.md`。届时用 `/grill-with-docs` 确认范围与语料，再 `/to-spec` → `/to-tickets`。确认应用依赖 10 的稳定保存契约，语料调研与只读建议没有布局完成的硬依赖。
- **收尾跑一次 `/retro`**：把"保存作息表毁草稿"这类坑变成确定性检查（例如给 `draft_lesson.period_id` 加外键/一致性校验），别只写在文档里。

## 交差门槛（每票）

1. `cd backend && ./mvnw -B test` 全绿；前端 `npm run lint && npm run build && npm run test:unit`。
2. 用户可见的改动：`scripts/verify.sh --full`（含 Playwright）全绿，关键页面截图留存。
3. 部署：`cd ~/paiban && git pull --ff-only` → 重建 backend/frontend → `up -d`，确认 Flyway 版本与四容器 healthy。
4. 上线后冒烟：导入 → 补教室 → 发布 → 调代一单（申请 SWAP 必须带 `targetPeriodId`）。
5. 回写当天日志（`D:\work Buddy-cn\2026-10-11-22-22-40\.workbuddy\memory\`）与 `MEMORY.md`，重跑 `~/ops/state-snapshot.sh`。

## 本轮确认与下一道门

用户已确认六项修复的准备、07/08 的补齐方式、10 的布局/阈值/控制层级/组合语义与六票拆分，14 下一期、02 独立下一批。完整记录在 preparation/README。

当前没有阻塞准备材料的未答问题。票 10 的可点击原型尚未制作、尚未经用户确认；这是后续第一票的验收门槛。本轮没有生产修复或部署成果。
