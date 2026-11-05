# 票 16 全量验收记录

基线：`main @ 7f9a20f`（票 15 已合并，未 push，`origin/main` 仍 `50ac999`；票 15 的 V55 迁移从未在生产库执行过）。

验收口径：旧数据按「**行数/主体不丢**」验收，不要求被票 15 删掉的旧列还在。

日期：2026-11-05

## 一、票面 11 项 → 证据矩阵

| # | 票面项 | 证据类型 | 证据位置（文件:行号 或 命令） | 结果 |
|---|---|---|---|---|
| 1 | 真实工作簿首次导入：560 槽、546 有课、14 空槽、48 教师、16 课程 | 物理文件定向实跑 + 合成 fixture 门禁 | `ManualImportIntegrationTest.java:305-339`（`@EnabledIfSystemProperty(named="paiban.realWorkbook")` 守卫，数字断言 `:329-335`）；实跑命令 `./mvnw -B -DforkCount=0 -Dtest=ManualImportIntegrationTest -Dpaiban.realWorkbook="D:/360MoveData/Users/liu/Desktop/自明排课系统-交互课表.xlsx" test`；默认门禁合成 560 槽断言见 `FullChainAcceptanceTest.java:112-120`、`ManualImportIntegrationTest.java:66-105` | **通过**：物理文件 8/8（560/546/14/48/16 全吻合）；合成 fixture 亦通过 |
| 2 | 第二次同格式导入不调用 AI | 后端全链 + E2E（配方复用，`ai_call_log` 计数 0） | `FullChainAcceptanceTest.java:131-148`（`recipeMatch.kind=EXACT`、`appliedRecipeId`、`aiCallLogs.count()==0`）；`frontend/e2e/manual-import.spec.ts:96-142`（`已套用配方` + `aiRequests==[]`） | **通过**：全量门禁 Playwright 168/168 含此用例 |
| 3 | 导入 → 草稿 → 发布 → 撤回 → 重新发布全流程通过 | 后端全链 + E2E | `FullChainAcceptanceTest.java:122-170`（入库 546 → 发布 546 → 撤回 0 → 重发 546）；`frontend/e2e/withdraw.spec.ts`、`frontend/e2e/scheduling.spec.ts` | **通过**：全量门禁 Playwright 168/168 含此用例 |
| 4 | 调代直接生效并可撤销 | 后端全链（(f) 直接生效 + (f2) 恢复基线撤销）+ E2E 直接生效 | `FullChainAcceptanceTest.java` (f)+(f2)；`backend/src/main/java/com/paiban/substitution/web/SubstitutionWorkbenchDtos.java:30-47`（`baselineTeacherId`/`currentTeacherId`）；`frontend/src/components/substitution/workbench/useConfirmFlow.ts:105,303,444`（`startRestoreBaseline`）；`frontend/src/views/SubstitutionWorkbenchView.vue:208-217`（`restorable`）；`frontend/src/components/substitution/workbench/ActionChooser.vue:78-84`（`restore-baseline` 按钮）；`frontend/e2e/substitution.spec.ts`（直接生效） | **通过**：(f2) 实跑断言旧行 `CANCELLED`、新行 `EFFECTIVE`、任课教师回到基线；口径见下 |
| 5 | 规则积木创建、例外、删除通过 | E2E + 后端全链 | `frontend/e2e/rule-builder-full.spec.ts`（创建/例外/删除）；`FullChainAcceptanceTest.java:199-244`（全局默认 + 教师例外 + 删除 + 回收站恢复） | **通过**：全量门禁 Playwright 168/168 含此用例 |
| 6 | 按年级排课不移动其他年级 | 后端硬断言（逐 periodId 比对）+ E2E | `backend/src/test/java/com/paiban/scheduling/ScheduleByGradeIntegrationTest.java:242-265`；`frontend/e2e/schedule-by-grade.spec.ts` | **通过**：定向 6/6；全量门禁含两处 |
| 7 | 教师视图绿/灰换课可用 | E2E | `frontend/e2e/teacher-view-swap.spec.ts:24-86` | **通过**：全量门禁 Playwright 168/168 含此用例 |
| 8 | 删除/停用/7 天撤销可用 | E2E + 后端全链 | `frontend/e2e/lifecycle.spec.ts`、`frontend/e2e/lifecycle-extended.spec.ts`、`frontend/e2e/recipe-management.spec.ts`；`FullChainAcceptanceTest.java:225-244`（7 天回收站恢复） | **通过**：全量门禁 Playwright 168/168 含此用例 |
| 9 | `scripts/verify.sh --full` 退出码 0 | 本地全量门禁实跑 | `VITEST_MAX_WORKERS=4 PAIBAN_MVN_EXTRA_ARGS=-DforkCount=0 bash scripts/verify.sh --full`，日志终行 `==> verify: ok` | **通过**：`VERIFY_EXIT=0`；后端 1013（0 失败/0 错误/7 跳过）、前端 94 文件 681 用例、Playwright 168/168（19.6 分钟）、8 项仓库级检查全绿 |
| 10 | 旧已发布课表、调代记录、规则、导入会话迁移后不丢失 | 后端迁移保全测试（行数/主体） | `TrimMigrationDataPreservationTest.java` 3 个用例；`V55__trim_legacy_columns_and_tables.sql`；`scripts/verify-ticket16.sh` 第 2/4 步 | **通过**：3/3，4 类主体行数与主体值在 V54→V55 前后一致；7 张红线表与 `deadline_at`/`locked` 保留；17 列 3 表确认已删 |
| 11 | 验收数字记录到本票，不重复写进 HANDOFF | 文档 | 本文件 + `16-full-acceptance.md` 实现记录；`.scratch/HANDOFF.md` 未改动 | **通过**：数字只落本票与本记录，HANDOFF 零改动 |

说明：上表按 `16-full-acceptance.md` 的勾选顺序编号。

**口径说明（第 4 项）**：票面「可撤销」按产品现有「恢复基线」入口验收 —— 对已调代的课再发一单，把任课教师换回基线教师（工作台 `candidates` 返回 `baselineTeacherId`，用它作 `substituteTeacherId` 走 `previews` + `confirm`）；旧 EFFECTIVE 行转 `CANCELLED`、新行 `EFFECTIVE`，历史保留，**不是删除调代记录、也不是独立 undo 端点**。收敛规格 `spec.md:54`（User Story 17「撤销今天的调代」）与票 15 定稿（Q5 保留「直接生效 + 再次调整覆盖」）共同构成依据；票 16 为收口验收票，不新增功能。`.scratch/product-trim/notes/backend-map.md:291` 旧笔记按 `undo/cancel` 关键词判定「无撤销入口」，实为未识别该 UI 语义，已在本次验收中纠正。

**口径说明（第 1 项）**：D 盘物理文件用例默认被 `@EnabledIfSystemProperty` 跳过（CI 无该文件，属既定设计），但主代理已用 `-Dpaiban.realWorkbook=<path>` 单独实跑通过；`scripts/verify-ticket16.sh` 在未传该变量时会明确打印「本次未跑 D 盘物理工作簿用例」，不冒充已验。

## 二、新增验收工件

| 工件 | 验票面的哪几项 |
|---|---|
| `backend/src/test/java/com/paiban/scheduling/TrimMigrationDataPreservationTest.java` | 第 10 项：V55 迁移前后旧已发布课表、调代记录、规则、导入会话按行数/主体比对不丢失（不要求已删旧列还在）；兼验票 15 红线表/列未被误删 |
| `backend/src/test/java/com/paiban/acceptance/FullChainAcceptanceTest.java` | 第 1、2、3、4、5、8、10 项的后端侧：560 槽预览 → 入库 546 → 配方复用不调 AI → 发布/撤回/重发 → 调代直接生效 → 恢复基线撤销 → 规则例外/删除 → 7 天回收站恢复 → 旧数据仍在 |
| `scripts/verify-ticket16.sh` | 串起第 1、6、10 项与主链验收的定向复跑入口（只读检查 + 调用现有测试，不跑 Playwright 全量、不连生产、不跑 Docker） |
| `ManualImportIntegrationTest.java`（修改） | 修第 1 项 gate 打开后暴露的过时断言，并补「占位教师已在教师表 → 只 warning 不待定」反向对照 |

## 三、真实工作簿

- 物理文件：`D:/360MoveData/Users/liu/Desktop/自明排课系统-交互课表.xlsx`，113429 字节，只读探查确认存在。
- gate：`ManualImportIntegrationTest.java:305` 的 `@EnabledIfSystemProperty(named="paiban.realWorkbook", matches=".+")`，不指定则跳过。
- 复跑命令：`PAIBAN_REAL_WORKBOOK="D:/360MoveData/Users/liu/Desktop/自明排课系统-交互课表.xlsx" bash scripts/verify-ticket16.sh`
- 实跑结果：`./mvnw -B -DforkCount=0 -Dtest=ManualImportIntegrationTest -Dpaiban.realWorkbook="..." test` → `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`；560/546/14/48/16 与「周五第 7 节建议规则」全部吻合。
- **验收中发现并修复的过时断言**：原 `realWorkbookMatchesTheSpecNumbers` 结尾写 `pendingTeachers` 应为 `isEmpty()`，但票 05 的 P1「占位教师不静默创建」落地后，空学期里「信息技术」合理进入待定列表（`ManualImportService.java:269-280` 只把「会被新建」的占位名放进 `pendingTeachers`）。该断言写在占位门槛之前、且仅在 gate 打开时才跑，故日常门禁从未暴露。现已改为断言 `pendingTeachers` 含「信息技术」，并新增反向对照用例（预置该教师后 `pendingTeachers` 为空）。这是修正过时断言，不是放宽产品行为。
- 默认门禁用合成 560 槽 fixture：`FullChainAcceptanceTest.java:348-372`、`ManualImportIntegrationTest.java:66-105`、`AiRecipeSuggestionIntegrationTest.java:85-124`、`WorkbookImportIntegrationTest.java:292-306`。

## 四、V55 与旧数据

- `V55__trim_legacy_columns_and_tables.sql` 全文要点：17 个 `DROP COLUMN`、3 个 `DROP TABLE`（`room` / `substitution_conversion_item` / `substitution_conversion`）、4 个 `DROP INDEX`、2 个 `DROP CONSTRAINT`、**0 条 DELETE**。
- 保留清单（迁移文件头注释）：`notification` / `notification_log`、`ai_call_log` / `ai_import_log`、`recent_deletion` / `import_recipe` / `timetable_publish`、`substitution_request.operator_user_id`、`substitution_preview.deadline_at`、`substitution_plan.approved_at / approved_by`、`substitution_plan_move` 业务列、`draft_lesson.locked`、`constraint_config` / `constraint_entry` 的旧规则行、`notification_log.platform`。
- 本机无 Docker、无原生 PostgreSQL、WSL 无发行版；`verify.sh --full` 的后端与 E2E 跑在 H2(PostgreSQL 模式)，全量 Flyway 已执行到 **V55**（`TrimMigrationDataPreservationTest` 另在独立 UUID 库上做 V54→V55 分段迁移）。真实 PostgreSQL 的**迁移可执行性**由 CI `-Dtestcontainers=true` 的 `PostgresContainerIT` 覆盖（空库跑全量迁移、断言表存在），本机判为跳过。
- **真实 PG 存量数据保全已于 2026-11-05 在云服务器补测通过**，见第九节（生产库副本、临时库、零写入生产）。
- 验收口径：不要求被票 15 删掉的旧列还在，只要求行数/主体不丢。

## 五、定向复跑数字（`scripts/verify-ticket16.sh`，`==> ticket16: ok`）

| 步骤 | 测试类 | 结果 |
|---|---|---|
| 1/4 真实工作簿数字 | `ManualImportIntegrationTest` | 8 项，0 失败 / 0 错误 / 2 跳过（物理文件用例未传 gate 时跳过） |
| 2/4 V55 旧数据迁移保全 | `TrimMigrationDataPreservationTest` | 3 项，0 失败 / 0 错误 |
| 3/4 全链主链验收 | `FullChainAcceptanceTest` | 2 项，0 失败 / 0 错误（复审后删重复年级用例，现为 1 项，已重跑 4/4） |
| 4/4 按年级排课不移动其他年级 | `ScheduleByGradeIntegrationTest` | 6 项，0 失败 / 0 错误 |

四段均 `BUILD SUCCESS`。物理工作簿单独实跑 8/8（见第三节）。

## 六、全量门禁数字（`scripts/verify.sh --full`，退出码 0）

```
VITEST_MAX_WORKERS=4 PAIBAN_MVN_EXTRA_ARGS=-DforkCount=0 bash scripts/verify.sh --full
VERIFY_EXIT=0
```

| 层次 | 结果 | 边界 |
|---|---|---|
| 后端 `./mvnw -B verify -DforkCount=0` | 1013 项，0 失败 / 0 错误 / 7 跳过，`BUILD SUCCESS` | 跳过项含 `PostgresContainerIT`（本机无 Docker）、`DeepSeekManualIT`、`PerformanceBenchmarkIT` |
| 前端 lint + build | 通过 | 未以构建代替运行 |
| 前端单元测试 | 94 文件 / 681 用例全过 | `VITEST_MAX_WORKERS=4` |
| 仓库级 8 项检查 | 全绿 | SFC 体积 / local-dates / e2e-helper 去重 / 模块边界 / 迁移版本 / 票状态 / isBlank / AI 超时 |
| Playwright | **168/168 通过，19.6 分钟** | 隔离端口 28080/25173 |
| 日志终行 | `==> verify: ok` | 日志 4210 行 |

## 七、code-review 两轴结论

按 `/code-review`（Standards + Spec 两轴，基线 `7f9a20f`）复审，主代理逐条判断并处置：

- **Standards**：硬违规 0 项；判断项 7 项。已修：FCT 整段复制 `ScheduleByGradeIntegrationTest` 的年级用例与 helper（删除，改由 `verify-ticket16.sh` 第 4 步跑原类）、`recipeBody` 样例课程名与 fixture 不符（课程02→课程03）、Trim Javadoc 夸大真实 PG 覆盖范围（改为准确表述）、脚本默认跳过物理文件却报 ok（补明确提示）。保留：合成 fixture helper 复用（测试内自洽，抽取会引入跨测试类耦合）、`isArray()` 装饰性断言（有效断言在后一行）。
- **Spec**：(a) 5 项 / (b) 3 项 / (c) 4 项。已修：报告证据路径错误（`SubstitutionWorkbenchView.vue` 正确路径为 `frontend/src/views/`）、报告称 FCT 覆盖「换课」但无 swap 断言（改为如实描述）、第 9/11 项当时留「待填」（现全部回填真实数字）。判定为误报/保留：第 1 项物理文件已单独实跑（脚本默认跳过是 CI 设计，已补提示）；「恢复基线」口径已在第一节显式写明，不新增独立 undo 端点；第 10 项额外验「旧列已删」是票 15 删除范围的反证，与「不丢失」互补且不要求旧列还在；MII 断言修正是 gate 打开后真实失败的过时断言，属必要修复。
- 复审后定向重跑：`FullChainAcceptanceTest`(1) + `TrimMigrationDataPreservationTest`(3) = **4 项，0 失败 / 0 错误**，`BUILD SUCCESS`。

## 八、边界（截至本机验收）

- 本机验收阶段：未连生产数据库、未备份/删线上数据、未执行 V55 到任何真实库、未部署、未 push。
- 未改 `.scratch/HANDOFF.md`（第 11 项要求数字只记本票）。
- 未改任何业务代码 / Flyway 迁移 / 前端源码；改动仅：3 个新增测试/脚本、1 个测试断言修正、2 个文档。
- 上述「未部署 / 未 push / V55 未上真实库」已在 2026-11-05 后续会话部分推进：push 完成、CI 已触发、真实 PG 临时库演练通过；**生产库本身仍为 v47、未部署、V55 未在生产执行**（见第九节）。

## 九、2026-11-05 后续补测：云服务器真实 PostgreSQL 存量数据保全

用户要求「把本机没有 Docker 所以没做的测试去云服务器上测」。做法：在 Lightsail 生产机上，用**生产库的完整副本**建临时库，在副本上跑 V48–V55，逐表比对行数/主体，验完删库。脚本 `scripts/verify-ticket16.sh` 的同伴 `~/ops/server-migtest.sh`（本仓库副本：`.scratch/product-trim/hold/server-migtest.sh`）。

**安全边界**：生产库只被 `pg_dump` 与只读 `select` 访问；所有写在临时库 `paiban_migtest_<UTC>`；不部署、不重建镜像、不 `up -d`；远程步骤合并为单次 SSH（OPS.md 红线）。

**前置事实**（`state-snapshot.sh` + 单次只读 SSH）：四容器 healthy、restart=0；生产 Flyway **v47**；内存可用 642 MB、load 0.15；`flyway/flyway:10` 镜像已在本地；服务器工作副本已 `git pull --ff-only` 到 `34b2754`（最高迁移 V55）；postgres 在 `paiban_paiban-internal` 网络、容器名可 DNS 解析。

**执行结果**（日志 `~/ops/paiban-migtest-20261105T144622Z.log`，`MIGTEST PASS`）：

| 检查 | 结果 |
|---|---|
| 源备份 | `paiban-migtest-source-20261105T144622Z.sql.gz`，141531 字节，`gzip -t` 通过 |
| Flyway | PostgreSQL **16.15**，`Successfully applied 8 migrations ... now at version v55`，退出码 0；`flyway_schema_history` 有 `version='55' and success=true` 记录 1 条 |
| 已发布课表 `timetable_entry` | **1092 → 1092**（主体行 course+class+day 非空同样 1092 → 1092） |
| 调代 `substitution` / `substitution_request` | 0 → 0 / 0 → 0 |
| 规则 `constraint_config` / `constraint_entry` | 6 → 6 / 0 → 0 |
| 导入会话 `ai_import_log` | 6 → 6 |
| 其它主体 | `ai_call_log` 39→39、`notification` 10→10、`notification_log` 2→2、`timetable_publish` 2→2、`timetable_draft` 3→3、`draft_lesson` 1638→1638 |
| V48/V49 新表 | `recent_deletion` / `import_recipe` 由「不存在」→「存在且 0 行」 |
| 旧列 | `room_id` / `section` / `required_room_type` 存在数 **3 → 0** |
| 3 张应删表 | 残留 **0**（room / substitution_conversion / substitution_conversion_item） |
| 7 张红线表 | 仍在 **7** |
| `deadline_at` / `locked` | **1 / 1**（保留） |
| 生产库 | 版本仍 **v47**、零写入；临时库由 trap 自动删除 |

**结论**：票 16 第 10 项「旧已发布课表、调代记录、规则、导入会话迁移后不丢失」在**真实 PostgreSQL 16 的真实生产数据副本**上同样成立，按「行数/主体不丢」验收通过。本机 H2 结论与 CI 空库迁移结论都得到真机印证。

**过程中修复的两个脚本问题**（首次实跑暴露，已修后重跑通过）：① Flyway 容器走 TCP 被 `pg_hba` 的 `scram-sha-256` 拒绝 → 改为从 postgres 容器读取真实 `POSTGRES_PASSWORD`（不打印、不写死）；② `recent_deletion` / `import_recipe` 在 v47 生产库里尚不存在 → 改为标记为 NEW、只要求迁移后存在。首次失败的那次已自动清理临时库、生产库零改动。

**仍未做**：V55 **在生产库执行**、镜像重建与 `up -d`、四容器/两处 health 验收、真机冒烟 —— 这些属于部署，需用户确认上线窗口后按 `docs/runbooks/deploy.md` 执行。
→ **已于 2026-11-05 完成，见第十一节。**

## 十、push 与 CI（2026-11-05）

- `git push origin main` → `50ac999..34b2754 main -> main` 成功（推送前确认 `gh auth status` 含 `workflow` scope、本次改动不含 `.github/workflows/`）。
- CI run `42046237373`：`deploy-scripts` ✅、`frontend` ✅、**`backend` ✅（`-Dtestcontainers=true` 真跑 `PostgresContainerIT`）**、`e2e` 进行中（约 18 分钟）。历史范例：PR #27 四 job 全绿。
- 本地 `main` 与 `origin/main` 已一致（`git log origin/main..HEAD` 为空）。


## 十一、正式上线（2026-11-05 15:09–15:2x UTC）

用户授权「继续上线」，按 `docs/runbooks/deploy.md` 执行。

| 步骤 | 结果 |
|---|---|
| 资源闸门 | `free -m` 可用 **851 MB**（>400），四容器 healthy，生产 v47 |
| 部署前备份 | `~/backups/paiban/paiban-pre-20261105T150909Z.sql.gz`，**141533 B**，`gzip -t` 通过；备份时 `1092\|1638\|6` |
| 代码同步 | `git pull --ff-only` → `c44074f` |
| 后台构建 | `setsid` + `~/paiban-build.log`，**BUILD_EXIT=0**；回滚镜像 `paiban-*:rollback-weekly-20261102T001615Z` 保留 |
| `up -d` | **Flyway V48–V55 共 8 步全部 success=true，生产到 v55** |
| 四容器 | frontend / backend / nginx / postgres 全 healthy、restart=0 |
| 本机 health | `https://localhost/api/health` = **200**（`{"status":"UP"}`） |
| 生产数据 | `55\|1092\|1638\|6\|6\|2\|0`（flyway/timetable_entry/draft_lesson/ai_import_log/constraint_config/timetable_publish/substitution），与备份前一致 |
| 删除范围 | 已删表残留 **0**、`timetable_entry.room_id` 残留 **0** |
| 真机冒烟 | admin 登录 OK；当前学期 id=1；八（10）班课表读出科学/语文…；`/api/constraint-types` = **18 类**；`/api/rooms`、`/api/notifications/recent`、`/api/ai/history`、`/api/feishu/config` 全 **404** |

**一个「假红」判定**：跑册写的 `https://127.0.0.1:18443/api/health` 在这台机器不存在（`ss` 显示 nginx 只监听宿主机 80/443），正确形态是服务器内 `https://localhost/api/health` → 200。从 Windows 本机用裸公网 IP 打 HTTPS 会 `schannel: failed to receive handshake`（`server_name _` 自签证书 + 裸 IP 无 SNI；Git Bash curl 同样走 schannel），但 HTTP 80 从外部返回 **301** 证明流量到达 nginx；服务器内部用自己公网 IP 打 HTTPS 也超时（hairpin NAT）。**均为客户端/网络限制，非部署回归，不回滚。**

**结论**：票 15（物理删除 + V55）与票 16（全量验收）已正式上线生产；V55 首次在生产库执行成功，旧数据按「行数/主体不丢」通过。备份与回滚镜像均保留。
