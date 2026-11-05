# 16: 全量验收：真实工作簿 + 端到端 + 旧数据迁移

**What to build:** 用真实工作簿跑完整主链：导入 → 配方复用 → 草稿 → 发布 → 撤回 → 重新发布 → 调代 → 规则 → 按年级排课 → 教师视图换课 → 删除/撤销，并跑全量门禁。

**Blocked by:** 15 (契约收口：删除旧代码与数据).

**Status:** done

- [x] 真实工作簿首次导入：560 槽、546 有课、14 空槽、48 教师、16 课程
- [x] 第二次同格式导入不调用 AI
- [x] 导入 → 草稿 → 发布 → 撤回 → 重新发布全流程通过
- [x] 调代直接生效并可撤销
- [x] 规则积木创建、例外、删除通过
- [x] 按年级排课不移动其他年级
- [x] 教师视图绿/灰换课可用
- [x] 删除/停用/7 天撤销可用
- [x] `scripts/verify.sh --full` 退出码 0
- [x] 旧已发布课表、调代记录、规则、导入会话迁移后不丢失
- [x] 验收数字记录到本票，不重复写进 HANDOFF

**Notes:** 这是收口票；不通过就不算完成本轮收敛。

---

## 实现记录（2026-11-05）

**基线**：`main @ 7f9a20f`（票 15 已合并，未 push，`origin/main` 仍 `50ac999`；V55 从未在生产库执行过）。**未连生产、未部署、未 push**。

### 验收口径（用户拍板）
「旧数据不丢失」按 **行数 / 主体不丢** 验收，不要求被票 15 删掉的旧列还在。数字只记本票与 `hold/16-acceptance-report.md`，**HANDOFF 零改动**。

### 关键判断（主代理亲自核实）
1. **「调代可撤销」= 工作台「恢复基线」**：`SubstitutionWorkbenchDtos.java:30-47` 的 `baselineTeacherId` + `useConfirmFlow.ts:105,303,444` + `SubstitutionWorkbenchView.vue:208-217` + `ActionChooser.vue:78-84`。对已调代的课再发一单把任课教师换回基线教师，旧 EFFECTIVE 行转 CANCELLED、新行 EFFECTIVE、历史保留。`backend-map.md:291` 旧笔记按 `undo/cancel` 关键词判「无入口」是漏判。票 16 不新增独立 undo 端点。
2. **「按年级排课不移动其他年级」已有后端硬断言**：`ScheduleByGradeIntegrationTest.java:242-265` 逐 `periodId` 比对，故不重复实现，只复跑。
3. **本机无 Docker / 无原生 PostgreSQL / WSL 无发行版**：`verify.sh --full` 跑 H2(PostgreSQL 模式)，全量 Flyway 到 V55；真实 PG 的迁移可执行性由 CI `-Dtestcontainers=true` 覆盖（本机跳过）。真实 PG 上的存量数据保全留待上线前按部署跑册（先备份）复核。

### 交付工件
| 文件 | 作用 |
|---|---|
| `backend/src/test/java/com/paiban/scheduling/TrimMigrationDataPreservationTest.java`（新增） | V54→V55 分段迁移，验 4 类主体行数/主体值不丢 + 7 张红线表/列保留 + 17 列 3 表确认已删 |
| `backend/src/test/java/com/paiban/acceptance/FullChainAcceptanceTest.java`（新增） | 560 槽合成 fixture 串主链 (a)–(i)：预览 → 入库 546 → 配方复用不调 AI → 发布/撤回/重发 546 → 代课直接生效 → (f2) 恢复基线撤销 → 规则例外/删除 → 7 天回收站恢复 → 旧数据仍在 |
| `scripts/verify-ticket16.sh`（新增） | 4 步定向复跑入口（不跑 Playwright 全量、不连生产、不跑 Docker；未传物理文件 gate 时明确提示） |
| `ManualImportIntegrationTest.java`（修改） | 修 gate 打开后暴露的过时 `pendingTeachers` 断言，补反向对照用例 |
| `hold/16-acceptance-report.md`（新增） | 11 项证据矩阵、真实工作簿、V55、定向与全量数字、code-review 两轴结论 |

### 验收数字
**真实工作簿物理文件**（`D:/360MoveData/Users/liu/Desktop/自明排课系统-交互课表.xlsx`，113429 B）：
`./mvnw -B -DforkCount=0 -Dtest=ManualImportIntegrationTest -Dpaiban.realWorkbook="..." test` → **Tests run: 8, 0 失败 / 0 错误 / 0 跳过，BUILD SUCCESS**；560 槽、546 有课、14 空槽、48 教师、16 课程、周五第 7 节建议规则全部吻合。

**`scripts/verify-ticket16.sh`** → `==> ticket16: ok`，四段各 `BUILD SUCCESS`：
1/4 `ManualImportIntegrationTest` 8 项（2 跳过：未传 gate 时物理文件用例）；2/4 `TrimMigrationDataPreservationTest` 3 项；3/4 `FullChainAcceptanceTest` 2 项（复审删重复年级用例后为 1 项，已重跑 4/4 通过）；4/4 `ScheduleByGradeIntegrationTest` 6 项。

**`scripts/verify.sh --full`**（`VITEST_MAX_WORKERS=4 PAIBAN_MVN_EXTRA_ARGS=-DforkCount=0`）→ **退出码 0**，终行 `==> verify: ok`：
- 后端 `./mvnw -B verify -DforkCount=0`：**1013 项，0 失败 / 0 错误 / 7 跳过**（跳过含 `PostgresContainerIT`、`DeepSeekManualIT`、`PerformanceBenchmarkIT`），`BUILD SUCCESS`
- 前端 lint + build：通过
- 前端单测：**94 文件 / 681 用例全过**
- 仓库级 8 项检查（SFC 体积 / local-dates / e2e-helper 去重 / 模块边界 / 迁移版本 / 票状态 / isBlank / AI 超时）：全绿
- Playwright：**168/168 通过，19.6 分钟**（隔离端口 28080/25173）

**V55 旧数据保全**：3/3 通过。已发布课表 2 行、调代申请 2 行 + 生效行 1 行、规则配置 2 行 + 参数行 2 行（含退役 `ROOM_CONFLICT`）、导入会话 1 行（含 `batches_json`）在 V54→V55 前后行数与主体值一致；7 张红线表、`deadline_at`、`locked` 保留；17 列 / 3 表 / 4 索引 / 2 约束确认已删；`flyway_schema_history` 有成功 V55 记录。

### 验收中发现的真实问题（已修）
- 物理工作簿 gate 打开后 `realWorkbookMatchesTheSpecNumbers:338` 断言 `pendingTeachers` 为空失败。根因是票 05 P1「占位教师不静默创建」落地后，空学期里「信息技术」合理进入待定列表（`ManualImportService.java:269-280`）；该断言写在门槛之前且默认跳过，日常门禁从未暴露。已改为断言含「信息技术」并补「预置该教师后为空」反向对照，非放宽产品行为。
- 报告中 `SubstitutionWorkbenchView.vue` 路径写错（正确在 `frontend/src/views/`）、`startRestoreBaseline` 行号写错、称 FCT 覆盖「换课」但无 swap 断言 —— 均已按事实修正。

### `/code-review` 两轴
- **Standards**：硬违规 0；判断项 7。已修 4 项（删 FCT 复制的年级用例与约 170 行 helper、课程名 02→03、Trim Javadoc 夸大真实 PG 覆盖、脚本默认跳过物理文件却报 ok）；其余 3 项判定为测试内自洽/装饰性断言，保留。
- **Spec**：(a)5 / (b)3 / (c)4。已修报告路径与措辞、回填第 9/11 项真实数字；第 1 项物理文件已单独实跑、第 10 项额外验「旧列已删」是删除范围反证、「恢复基线」口径已显式写明，均判定为误报/保留。
- 复审后定向重跑：`FullChainAcceptanceTest`(1) + `TrimMigrationDataPreservationTest`(3) = **4 项，0 失败 / 0 错误，BUILD SUCCESS**。

### 边界 / 遗留
- 未连生产数据库、未备份或删除线上数据、未把 V55 执行到任何真实库、未部署、未 push。
- 真实 PostgreSQL 上的**存量数据保全**与 V55 生产执行需上线前按 `docs/runbooks/deploy.md`（先 `pg_dump` 备份并 `gzip -t` 验证）在临时库复核；本机因无 Docker 判为跳过。
- 未改 `.scratch/HANDOFF.md`；未改任何业务代码 / Flyway 迁移 / 前端源码。提交只 stage 本任务路径。
