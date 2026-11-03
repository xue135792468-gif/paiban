# 接手说明（2026-10-25 会话交接）

> **2026-11-02 草稿拖拽换位、联动方案与批量锁定：九票已实现、两轴复审、分阶段全量验收通过，未合并 main、未部署**：集成分支 `codex/draft-swap-locks`（worktree `E:/paiban-wt/draft-swap-locks`，基点 `4353a16`）。九票 01–09 全部 done；后端 `./mvnw -B verify` 1195 项 0 失败（8 跳过，`PostgresContainerIT` 因本机无 Docker 未跑），前端 549 项单测通过，整套 Playwright 161/161 通过（同一提交）。实现：持久化课节锁定与版本/行锁协议、整单变更与稳定违规加权、草稿与调代共用策略、同班本周闭环搜索与一次性 token、直接拖拽交换/自动方案/整单撤销、单节与批量锁定、显式来源草稿继续优化与锁定继承、继续优化入口与新旧稿比较。完整证据见 [统一验收](draft-swap-locks/acceptance.md)。剩余：真实 PostgreSQL 迁移/行锁待同提交 CI 或 `--deploy`；`verify.sh --full` 曾出现一次无关 `admin-reschedule` 导航超时，单独整套 E2E 复跑全绿；合并与部署需用户明确授权。线上仍是上一轮已部署版本。

> **2026-11-02 草稿拖拽换位、联动方案与批量锁定：九票已发布，尚未实现**：用户完成grill-with-docs的15项选择，确认方案后明确“开始拆票发布”。[规格](draft-swap-locks/spec.md)、[九票路线](draft-swap-locks/ticket-plan.md)和[跨票契约](draft-swap-locks/contracts.md)为后续入口，决策见ADR-0034。全部票ready-for-agent、未认领，初始frontier为01/03；共享编辑器与SchedulingService的票按路线串行集成。合法A/B一拖交换，失败自动列最多三种联动方案；批量锁定时间与教师；与调代共用课节上限/排序，日期窗口仅用于发布后；指定当前草稿继续优化并保留锁生成新稿。最终算法判断由主模型负责。本轮只发布文档，不自动实施、合并功能或部署；线上仍为上一轮已部署版本。

> **2026-11-02 发布验收收口**：两处旧E2E条件等待已修正，8953961完整CI通过，票据已done，详见[测试稳定性门禁](acceptance-stability/release-gate.md)。业务源码保持已部署版本，无需重建应用；备份、数据保护和线上入口见下方部署回执。后续仅文档收口提交。

> **2026-11-02 每周留空与算法修正已合并上线**：用户明确授权合并部署，PR #21已合并main；应用镜像构建自d76709c，与已验收PR内容一致。数据库备份和回退镜像已保存；线上健康、数据保护与新规则入口已检查，详见[部署回执](weekly-no-class-and-algorithm-fixes/deployment.md)。原有正式课表和约束未改。下方“未部署”均为前阶段快照；旧页面如仍显示旧内容，按Ctrl+F5或使用回执中的发布入口。

> **2026-11-02 每周留空与三项算法修正：开发验收完成，未部署**：六票已实现并复审，集成分支codex/weekly-no-class，目录E:/排课系统/.worktrees/weekly-integration。主模型亲自判断本批算法修正合格；冻结版本完整门禁已通过，全部统计、失败历史和源码tree见[统一验收](weekly-no-class-and-algorithm-fixes/acceptance.md)，不在入口复制数字。本机无Docker，真实PostgreSQL前版CI已实跑通过；CI旧日期fixture也已修正并重新完整门禁通过，最终提交检查见[PR #21](https://github.com/xue135792468-gif/paiban/pull/21)。未合并main、未部署生产；仅保留集成worktree，执行者与任务临时进程已清理。主仓库既有截图/原型未动。下方“尚未实现”是旧规划快照。

> **2026-11-01 六票已正式发布（尚未实现）**：用户要求修复主模型亲自审查发现的三项算法问题，并新增“每周不上课时段”；已选择第一版只做每周固定、全校或部分班级、原课时排到其他位置，不含具体日期停课。用户澄清并确认全天连续节次：上午4节+下午3节+晚上2节为第1–9节，晚自习只是时间段标签。规格和六张正式票在 [weekly-no-class-and-algorithm-fixes/spec.md](weekly-no-class-and-algorithm-fixes/spec.md) 与 [ticket-plan.md](weekly-no-class-and-algorithm-fixes/ticket-plan.md)，均已 `ready-for-agent`；未认领、未编码，初始frontier为01/02/03，03→04→05，06收口。01/03共用文件默认串行，02只在资源和写集合允许时独立并行。算法最终判断由主模型负责。本轮仅拆票发布，不自动实施或部署。审查反例和实现定位在该目录 `audit-evidence.md`。

> **2026-11-01 追记（票 08 收尾 + 测试数据清理）**：票 08 完成：补了两个隔离测试（草稿行 null class/teacher 不崩且跳过；同班不同教师同节只由班级唯一性拦截），`WarmStartSource` 枚举与 `TimetableProblemBuilder.build()` 薄包装记为 `wontfix`（字符串仅三处、`build()` 仍被 `PerformanceBenchmarkIT` 使用）；票 04 的三场景说明改为引用现有 `DraftPublishReadiness`/`DraftValidator` 测试。线上清理：先备份 `paiban-pre-cleanup-20261101T092450Z.sql.gz`（180740B，gzip 通过），只通过 DELETE API 删除 **DRAFT** 草稿 2–14；保留已发布草稿 **1**（学期1）和 **15**（学期2），当前学期 1，两学期各 546 条已发布课表（`timetable_entry` 1092 行）。**没有**删除已发布学期 2（应用按设计拒绝删除已发布课表；如需彻底移除，需另做带备份的 SQL 级清理）。票 08 提交 `9308a40` 的 CI 首次因 runner 下载 Maven 发行包失败（`wget`，测试未开始），重跑 backend job 后 **四 job 全绿**。

> **2026-11-01 追记（CI 四 job 全绿，main `e7a1529`）**：用户完成 GitHub `workflow` scope 授权后，修掉 backend 的 Docker 假红。根因不是“没装 Docker”，而是 testcontainers 内嵌 docker-java 默认 Docker API 1.32，而 runner 守护进程要求 ≥1.40；`DOCKER_API_VERSION` 环境变量不生效，docker-java 读的是 JVM 系统属性 **`api.version`**。已在 `backend/pom.xml` 的 surefire `argLine` 设 `-Dapi.version=1.43`（测试 JVM 生效），并移除之前无效的 workflow env。CI run `41291310707` **backend / frontend / e2e / deploy-scripts 四个 job 全绿**，backend 真实跑通 `PostgresContainerIT`。

> **2026-11-01 追记（新学期端到端验收 + 跨学期固定教室修复，main `38e0d75`）**：在线上新建 semester 2 `Acceptance-2027-S2`，从学期 1 复制结构（224 授课任务），用服务器上保留的真实工作簿导入 **546 行**（session 6 / draft 13；0 班级/教师冲突，但 546 节缺教室）。首次自动排课因教室是全局旧名 `1-八年级八（1）-固定教室`、固定教室按新学期名精确匹配失败，room 为 null，warm start 的时段被构造启发式重排，草稿 14 出现 **148 冲突**、发布 409。修复 `RoomFillPlanner.fixedRooms`：精确匹配优先，找不到时按「年级+班名+`-固定教室`」唯一后缀复用旧学期教室；两个及以上候选不猜。重跑 job 13 / draft 15：`warmStartSource=DRAFT`、`0hard`、`valid=true`、`publishable=true`，发布 `entryCount=546`，班级视图 39 节；当前学期已切回 1。门禁：后端 **1034**（0 失败，PostgresContainerIT 4 条因本机无 Docker 跳过）、前端 **446**、SFC/local dates/isBlank/模块边界/迁移/票状态/AI 超时全绿；全量 Playwright 单独重跑 **138/138**。首次 `verify.sh --full` 的 e2e 有 1 条 `constraints-concurrent-save` 并发用例 180s 超时（136 passed），单独重跑 2/2 通过，判定为负载下的时序抖动。**遗留**：CI backend 仍红，原因是 runner Docker API 1.32 < 最低 1.40（不是没装 Docker）；需要在 backend job 设 `DOCKER_API_VERSION=1.43`，而改 workflow 需要 token 的 `workflow` scope（当前没有，需 `gh auth refresh -s workflow`）。测试学期 2 和 draft 13–15 仍留在生产，删除需人工确认；票 08 的 5 个非阻塞复审小项仍 `needs-triage`。

> **2026-11-01 追记（code-review follow-up 04/06/07/03 全部完成，main `936c779`，Flyway 44）**：按 triage 顺序完成：04 发布校验主显示/求解得分参考 + 移除 `moveLesson` 覆写；06 warm start 排除班级/教师同节冲突；07 记录 `warm_start_source`（V44）并在空白学期显示非阻塞提示；03 抽取 `WarmStartSeedService`、builder 依赖 8→6、草稿行改单次批量查询。最后一轮两轴复审又修了两个问题：`SchedulingView.vue` 曾增至 852 行（超 SFC 基线），抽出 `SchedulingJobMetrics` + 日期工具后降到 786 行；`SchedulingService.run` 整实体 save 可能覆盖并发取消，改为只更新 `warm_start_source` 的定向 update。门禁：后端 **1036**（0 失败）、前端 **446**、scheduling e2e **5/5**、`verify.sh --quick` 绿。部署 `936c779`：备份 113459B（gzip 通过），Flyway **44**，四容器 healthy、health 200；线上 job 11 / draft 12「全票验证」`warmStartSource=BASELINE`、`0hard`、546/546、0 冲突、每班一间、`valid=true`；唯一拦截 `SEMESTER_ALREADY_PUBLISHED`。最后一轮复审的 5 个非阻塞小项在票 08（`needs-triage`）。

> **2026-11-01 追记（用户决定：容量/教室不作为约束，main `b185864`）**：用户明确“人数无所谓，教室默认都装得下；教室不用管，肯定是有教室的”。固定教室解析（`RoomFillPlanner.fixedRooms`）改为只认 `学期-年级班名-固定教室` 名字：不再因人数为 0、容量不足、类型不是 NORMAL 而放弃本班教室；只保留“找不到同名教室”和“一房两班”两个退化条件。`plan(...)`（补固定教室创建动作）仍按容量推算，未改。票 02（planner 匹配去重）和票 05（固定教室数据质量/缺教室提示）因此关闭为 `wontfix`；票 04 收敛为只统一“求解得分 vs 校验硬冲突”的显示口径，不再要求固定教室进 `ConstraintChecker`。部署 `b185864` 后线上 job 10 / draft 11「简化后验证」：546/546、0 冲突、每班 1 间本班教室、`valid=true`；唯一拦截仍是 `SEMESTER_ALREADY_PUBLISHED`。剩余 follow-up：票 03（warm start 抽取）、04（分数口径）、06（空白学期/草稿冲突校验）。

> **2026-11-01 两轴 code-review + retro 护栏（main `4c7f37d`）**：按 Standards/Spec 两轴复审 `4bbffb3..HEAD`，修复：scheduling 改走 `SemesterBaselineQuery.publishedLessons` 端口（不再直接 import timetable）、warm start 草稿必须覆盖全部授课任务的周课时、`requiredRoomType=NORMAL` 按普通课钉本班教室（新增 `RoomRequirement.isSpecial`，builder/provider/room-fill 共用）、双栏比较/对照预览也隐藏教室、导出 `Cell` 去掉死字段、RoomFillPlanner 名称 trim 统一；补基线 warm start、NORMAL、空格复用测试。门禁：后端 **1026**（0 失败）、前端 **442**、scheduling e2e **5/5**。部署 `25a3298` 后线上 job 9 / draft 10「审查后验证」：**546/546 全部有时间+教室，班级/教师/教室冲突 0，14 班每班 1 间本班教室，`valid=true`、`hardConflicts=0`**；唯一拦截仍是 `SEMESTER_ALREADY_PUBLISHED`（流程限制）。复审遗留 5 张票 `issues/02`–`06`（planner 匹配去重、warm start 抽取、分数/校验双口径、固定教室数据质量、空白学期/草稿冲突校验），状态 `needs-triage`。Retro 已落地：新增 `scripts/check-module-boundaries.sh`（接入 verify 与 pre-commit）、e2e/verify 前清 `frontend/node_modules/.vite`、AGENTS 补冻结源码与假失败说明、ADR 数量修正。CI backend 仍只报 `PostgresContainerIT` 无 Docker（frontend/e2e/deploy-scripts 绿）。

> **2026-10-31 追记：自动排课已能达到直接发布标准（main `195fc09`，ADR-0032/0033）**：用户要求“自动排课排出来的必须能直接发布”。诊断出两层：①「课节未排」和单处冲突都只算 1 hard，求解器觉得留空和冲突一样糟；② Timefold 的 `forEach` 会跳过还有 null 规划变量的课节，所以“有时间但没教室”的课节既不触发冲突也不触发固定教室约束。60 秒和 300 秒都停在 4 个空洞。修复：未排课改为 10 hard，固定教室/教室类型改用 `forEachIncludingUnassigned`；初始解改用 `CHEAPEST_INSERTION`（教师冲突 2→0）；再引入 warm start——有 `timetable_entry` 用已发布基线，新学期用最早一份完整草稿（通常就是导入草稿），按 `(班级,课程,教师)` 灌入时段，教室仍由固定教室规则决定。线上 job 8 / draft 9「最终验证」：**546/546 全部有时间+教室，班级/教师/教室冲突 0，14 个班每班只用 1 间本班教室，校验 `valid=true`、`hardConflicts=0`**；唯一拦截是 `SEMESTER_ALREADY_PUBLISHED`（本学期已有基线，属流程限制，不是草稿质量问题）。门禁：后端 1022（0 失败）、scheduling e2e 5/5；前端本次未改。没有基线也没有完整草稿的新学期仍走评分修复 + CHEAPEST_INSERTION 的从零搜索，不保证一次到 0。

> **2026-10-31 自动排课固定教室已上线（main `c7e2928`，Flyway 仍 v43，ADR-0031）**：用户反馈“自动排课后班级和课表对不上”。只读核对确认班级、课程、教师归属 0 串班；根因是求解器把教室当自由变量，14 间同类型同容量固定教室被随意分配，而教室名带班名（`1-八年级八（13）-固定教室`），班级课表看起来像混入别班，教师视图同理。修复：`RoomFillPlanner.fixedRooms` 按既有 `学期-年级班名-固定教室` 命名解析每班教室；`Lesson.fixedRoom` + 结构性硬约束「班级固定教室」钉住普通课程；课程自带专用教室要求时豁免；缺房间/容量不足/一房两班时退化自由分配。课表（草稿与已发布）与 Excel/PDF 导出不再显示教室；AI 导入预览保留教室列。数据：清掉体育与健康（PLAYGROUND/requireOutdoor）与信息科技（OTHER）的教室类型要求。门禁：后端 1019、前端 442、Playwright 138 全绿；`verify.sh --full` 首轮 Playwright 因 esbuild 服务崩溃（环境抖动）未启动，清 `node_modules/.vite` 后单独重跑 138 全绿。部署：备份 `paiban-pre-20261031T135322Z.sql.gz`（79349B，gzip 通过）；服务器 `git pull --ff-only` 到 `c7e2928`，后台重建 backend/frontend 后 `up -d`；四容器 healthy、两处 health 200、Flyway 43。线上重跑 job 2 / draft 3「固定教室验证」：14 个班中 11 个班的 39 节课只落在 1 间本班教室，课表页与草稿页 DOM 均无「固定教室」字样；3 个班仍有 5 节未排完整（3 节缺时间、2 节缺教室），属“空着与冲突同分”的求解评分问题，未在本次处理。证据截图在 `.scratch/autoschedule-room-diagnosis/`。

> **2026-10-30 工作台 UI 重做已上线（三票全部完成，main `f31bb90`，Flyway v43）**：用户选定 **v3a 留白版** 原型（`C:\Users\liu\.zcode\v2\visualizations\742542c2d05adefebf901d486182e3b320ecfaf178ae5e74b62defbfd3e8ebd0\v3a-airy.html`，同目录 v3b 仅作对照）。规格与 3 张票在 [.scratch/workbench-ui-redesign/](workbench-ui-redesign/spec.md)：01 向导骨架+选课/动作表单（含拖拽预选）→ 02 确认页（结论/跨科门/课表变化可视化/提交确认）→ 03 五个既有 e2e 迁移+无障碍/320px+帮助文案+全量门禁。**后端接口、数据库、业务规则零改动**。原型证据：v3a/v3b 各 40/40 交互断言、0 控制台错误；三个布局 bug（切换入口被推到首屏外、原因框撑高、序号压字）已修并在真实浏览器复验。**2026-10-30 追记：三票已实现、评审、合并 `main`（`f31bb90`，9 个提交）并部署到生产。** 实现于 `feature/workbench-ui-redesign`（`438938a..f31bb90`，实现说明见各票末尾）；两轴 code-review 后修复了凭证过期字段（改用 `expiresAt`）、修订横幅类型丢失、e2e helper 去重与票状态门禁。门禁：本地 `verify.sh --full` 退出码 0（后端 1012、前端单测 438、Playwright 137，约 36 分钟），main CI 四 job 全绿（backend 含真实 PostgreSQL `PostgresContainerIT`，替代本机缺 Docker 的 `--deploy` 门禁）。**部署记录**：备份 `~/backups/paiban/paiban-pre-20261030T004707Z.sql.gz`（73663B，gzip 校验通过）；服务器 `git pull --ff-only` 到 `f31bb90`，后台重建 backend/frontend 后 `up -d`；Flyway 保持 **v43**（本次无迁移）；四容器 healthy；本机与公网 health 200；冒烟：线上 bundle 含 `wizard-stepper` 与「三步向导」帮助文案、旧界面标记（`generate-swap-plans`）0 处，三条工作台路由 401（已注册）；tester2 登录后只读验证：48 位教师、何昌盛《体育与健康》八（13）2026-10-28 课 → 代课候选 47（13 忙碌）、调课目标槽位 8，均 200。生产数据未变（546 节已发布课表、0 条调代申请、1 个学期）。截图在 `.scratch/workbench-ui-redesign/acceptance/`；后端/数据库/接口零改动。**同日补充（`06b1327`）：调代新增「今天及以后」限制**——工作台第①步默认本地今天（修复 `today()` 用 UTC 导致凌晨显示昨天的 bug），日期选择器与过去课卡禁用（半透明、不可点、不可拖），教师自助「调代课」表单同样限制；`admin-reschedule` 的旧 UI 路径（点过去课卡触发 409）随新限制改为断言不可点，API 级 409/零写入原样保留。门禁 1012/441/138 退出码 0；main CI 四 job 全绿（上一轮纯文档提交的 backend 红为 `SchedulingIntegrationTest.cancellingAJobFreesTheSolverForTheNextSubmission` 时序 flake，本轮已绿）；备份 `paiban-pre-20261030T034948Z.sql.gz`（73828B，gzip 通过）；部署后 Flyway 43、四容器 healthy、两处 health 200、bundle 含新限制文案且旧标记 0、生产数据未变（546 节 / 0 调代 / 1 学期）。**同日护栏改进（`2ad818a`，已随本次部署上线）**：pre-commit 无条件跑票状态/本地日期/e2e helper 三项仓库级检查；新增 `scripts/check-local-dates.sh`（禁 `toISOString` 截取日期，顺手修复 `TimetableView` 默认日期的同类 UTC 问题）与 `scripts/check-e2e-helpers.sh`（helpers 导出名不得在 spec 里重复定义，顺带折叠两处 `solveDraft` 副本），两个检查已接 `verify.sh` 与 CI 的 frontend job；`CODING_STANDARDS` 补「保留到用 `expiresAt`（缺失回退 `deadlineAt`）」「修订横幅必须展示 `revisionKind` 类型」；`deploy.md` 补 CI 运行器无 Docker 时的判定与 `gh run rerun --failed`；HANDOFF 清掉 2026-10-25 的过期尾段。全量门禁 1012/441/138 退出码 0；备份 `paiban-pre-20261030T073030Z.sql.gz`（gzip 通过）；部署后 Flyway 43、四容器 healthy、两处 health 200、数据未变（546/0/1）。CI：frontend/e2e/deploy-scripts 绿，backend 连续因运行器无 Docker 报 `PostgresContainerIT: Could not find a valid Docker environment`（重跑两次同因），后端代码与已全绿提交 `06b1327` 相同，按跑册以该次 CI 为有效验证。**

> **2026-10-29 管理员调代工作台九票已实现、评审、合并 `main`（PR #20，merge `94f0372`）并部署到生产（服务器 `4e2acef`，Flyway v43）**：01 日期实例（V38）、02 历史转换预检（V39）、03 管理员代课/预览/亲自确认（V40）、04 修订链换代课人/恢复基线（V41）、05 同班调课明确目标日期（V42）、06 拖拽入口、07 已调课实例再调整与冻结、08 全站独立滚动、09 帮助与集成验收。两轴 code-review 后的修复：批准路径一律受开课截止（含首次批准）、候选方案按操作者隔离（V43）、SUPERSEDED 历史展示（记录接口 `status=ALL` 可选）、h2 控制台按环境放行、共享 `SubstitutionLookups` 去重、前端权限常量集中；门禁修复：e2e 种子改未来学期、onboarding 前置、滚动/重姓 flaky 加固。最终门禁：后端 1012（0 失败）、前端 417、Playwright 117 全绿；PR CI 与 main CI 四 job 全绿（backend 含真跑 `PostgresContainerIT` 4 条）。**部署记录**：备份 `~/backups/paiban/paiban-pre-20261029T103312Z.sql.gz`（72011B，gzip 校验通过）；服务器部署 `HEAD=4e2acef`（其后仅 2 个纯文档/测试提交，服务器已 `git pull` 到 `7c475b9`，无需重建）；Flyway 自 37 应用 V38–V43 共 6 个迁移至 **v43**；四容器 healthy；本机与公网 health 200；冒烟确认三条新路由已注册（401 非 404）且前端 bundle 含工作台/帮助文档。**生产历史调代转换未执行**（独立教务确认门：预检 → 差异清单 → 教务签字）。**回滚注意**：迁移均为加列/放宽约束，代码可回退；但部署后新产生的调代行带 `lesson_date`，旧代码不识别，回滚需同时清理部署后新增调代行（当前 0 行）。修复明细见 [review-fixes.md](admin-substitution-workbench/review-fixes.md)，验收截图在 `admin-substitution-workbench/acceptance/`。生产历史调代预检（2026-10-29，只读）：旧格式记录 count = 0；预检 summary = {'totalRows': 0, 'convertible': 0, 'blocked': 0, 'alreadyConverted': 0, 'changes': 0}；无写入。

> **2026-10-29 管理员调代工作台规划已确认并发布，尚未实现**：见 [正式规格](admin-substitution-workbench/spec.md)、[九票依赖总览](admin-substitution-workbench/ticket-plan.md) 与 [决策记录](admin-substitution-workbench/decisions.md)。支持管理员代任意教师发起/亲自确认、左右课表对照、发布后同班环拖拽，以及开课前再次调整；全站独立滚动单独成票。初始 frontier 为 **01、08**，其余按 Blocked by 推进。ADR-0030 已接受；日期实例语义与现有持续覆盖不同，历史转换须先出差异清单并完成教务数据确认。本次仅发布文档和九张 ready-for-agent 票据，未认领、未编码、未部署；原线上状态保持下述记录的证据边界。

> **2026-10-28 票 04（重复提交单飞）已实现、评审、合并并部署（代码 `a355dde`，Flyway v37，无迁移）**：`COMMITTING` 短事务认领 + 失败释放 + 启动恢复；两轴独立评审无阻塞；官方门禁后端 869（5 skipped）、前端 401、Playwright 91 全绿；CI 四 job（含真实 PostgreSQL `PostgresContainerIT`）全绿。部署备份 `~/backups/paiban/paiban-pre-20261028T041007Z.sql.gz`；四容器 healthy、两处 health 200。生产冒烟：1 行工作簿导入 → 第一次确认 200（草稿 6）、第二次 409、只新增 1 份草稿。详见[票 04](workbook-background/issues/04-commit-single-flight.md)。

> **2026-10-27 票14 已实现、合并 `main` 并部署（代码 `b21cb9a`，Flyway v37）**：正式规格与 12 张实现票（`.scratch/natural-language-constraints/`）完成 01→12：课程禁排、25 类逐项控制、配置版本/冲突、AI 只读建议、C01–C22 评测工具、校对加入与审计、跨设备工作区。合并后全量门禁后端 860、前端 401、Playwright 90 全绿；真实 PostgreSQL 临时库 V1–V37 与 CI `PostgresContainerIT` 均通过；四容器 healthy、两处 health 200。真实评测已 **PASSED**：`gemini-3.8-flash-low` **54/54、12/12**；任务已绑定、`ai.features.natural-language-constraints=true`、`/api/constraint-proposals/availability` 返回 `available:true`。详见[票12交付记录](natural-language-constraints/issues/12-integrate-and-accept.md)；票13 结构重构已完成（共享匹配器/类型化参数/审计服务/面板/helper，门禁 863/401/90），剩余核心规划/持久化/模板拆分见票14。

> **2026-10-26 整本导入后台化（02 遗留）已实现、合并 `main` 并部署（代码 `b21cb9a`，Flyway v37）**：三张草案 01 → 02 → 03 已完成，每票 TDD、独立评审。上传立即返回 `PARSING`，规划/取值在 `importExecutor` 串行；计划与运行身份落库（Flyway **V30**：`plan_json` / `plan_version` / `run_id`）；停止保留已完成范围并把未覆盖范围列为可重试失败；重试只重放失败批次、成功批次不重调模型；晚响应只进审计。合并后全量门禁通过；备份 `~/backups/paiban/paiban-pre-v37-20261027T105735Z.sql.gz`；四容器 healthy、两处 health 200。评审遗留的票 04（重复提交单飞）已于 **2026-10-28** 实现：`COMMITTING` 短事务认领 + 失败释放 + 启动恢复，`WorkbookCommitRaceTest` 4 例覆盖；读 `.scratch/workbook-background/spec.md` 与 `issues/`，决策见 `docs/adr/0029-workbook-background-runs.md`。

> **2026-10-25 准备复查更新：先读 [准备审查入口](phase11-real-workbook-import/preparation/README.md)。**
> 用户已暂停本会话修复，改为完成准备材料、交由新的 agent 执行。以下旧路线中的待问与 ready 状态须以新入口为准：六项修复已有补充规格；票 10 已问定并拆成 6 票，先做原型等用户确认；整本后台化另备下一批 3 张草案；14 下一期再 grill。
> 票 11 有未提交、未通过验证的候选代码留在 `E:\paiban-wt\import-repairs`，不是已完成修复。勿忽略未跟踪的外键迁移或把现有失败测试当作绿色证据。新入口记录了基点、失败原因和接手要求。
>
> **2026-10-25 执行结果（六项修复已上线）**：11 → 12 → 13 → 07 → 08 → 09 已全部实现于集成分支 `feature/phase11-repairs`，每票独立 worktree + TDD + 独立合并，末轮过 code-review（Standards/Spec 两轴）并集中修复一次。门禁：后端 `./mvnw -B test` 634 绿、前端 lint/build/unit 241 绿、SFC 体积检查通过、`scripts/verify.sh --full` 全绿（Playwright 50 例，含新增 5 例）。**已合并 `main`（`a8babf5`）并部署上线**：Flyway 到 **v25**（V24 `draft_lesson`→`period` 外键、V25 0 人班级数据修复），四容器 healthy；部署前在服务器临时库上做了真实 PostgreSQL 的全量迁移验证，生产 preflight 悬空引用 0，备份 `~/backups/paiban/paiban-pre-v24-20261025T130850Z.sql.gz`。线上冒烟 15 项全绿（导入 → 补教室 → 发布 → 调代 SWAP → EFFECTIVE）。`E:\paiban-wt\import-repairs` 的候选代码已被正式实现取代，可删。

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
| `11-save-periods-breaks-drafts` | 保存作息表会让所有草稿"找不到课节"，无提示 | 已上线（2026-10-25，main `a8babf5`） |
| `12-import-classes-zero-students` | 导入建班人数=0，之后班级改不动；容量约束失效 | 已上线（2026-10-25，main `a8babf5`） |
| `13-draft-validation-vs-publish` | 草稿校验说通过、发布却拒绝（两套标准） | 已上线（2026-10-25，main `a8babf5`） |
| `07-rooms-before-publish` | 发布前没有教室，导入完发布不了；要一键补教室 | 已上线（2026-10-25，main `a8babf5`） |
| `08-fill-missing-periods` | 文件里的节次比作息多，只能手工加格子 | 已上线（2026-10-25，main `a8babf5`） |
| `09-flag-suspicious-teacher-names` | "信息技术"这类占位名被当成老师 | 已上线（2026-10-25，main `a8babf5`） |
| `10-multiple-constraint-targets` | 约束多条、对象覆盖与折叠布局 | 已上线（2026-10-26 部署；constraint-entries 六票全部 done，证据见 `.scratch/constraint-entries/issues/` 与 git log） |
| `14-natural-language-constraints` | 自然语言生成约束＋课程禁排＋逐项控制＋跨设备恢复 | 已上线并放行（2026-10-27，main `b21cb9a`，Flyway v37）；3.8-low 54/54、12/12，任务已绑定、开关已开 |
| 票 02 遗留 | 整本导入没有批次进度/停止/重试（同步跑完） | 已上线（2026-10-27，main `b21cb9a`，Flyway v37）；见 `.scratch/workbook-background/` |

票据都在 `.scratch/phase11-real-workbook-import/issues/`；规格与 ADR 在该目录 `spec.md` 和 `docs/adr/0026-real-workbook-import.md`。

## 新会话怎么推进（按 ask-matt 的路由）

- **六项修复已执行完毕。**
  11 → 12 → 13 → 07 → 08 → 09 已全部实现、评审并**上线**（main `a8babf5`，Flyway v25）。不要再重复实现；线上问题按「实现说明」与当天日志排查。各票实现说明写在 `.scratch/phase11-real-workbook-import/issues/*.md`。
  部署前须知：V24 给 `draft_lesson.period_id` 加外键，若线上存在历史悬空引用，迁移会直接失败（有意闸门）；迁移注释里带 preflight 查询，必须先查、按记录修复后再重建。
- **10 的旧问题已答，六张新票已全部实现并上线（2026-10-26）。**
  读 `.scratch/constraint-entries/spec.md`。constraint-entries 六票全部 done，2026-10-26 已部署，证据见 `.scratch/constraint-entries/issues/` 与 git log；无需再排期或重复实现。
- **02 整本后台化已上线（2026-10-27，main `b21cb9a`，Flyway v37）。**
  读 `.scratch/workbook-background/spec.md` 与 `issues/`；实现说明在各票末尾，决策见 ADR-0029。评审遗留的票 04（重复提交单飞）已于 2026-10-28 **合并并部署（main `a355dde`）**，生产冒烟 200/409/一份草稿通过。
- **14 已上线（2026-10-27，main `b21cb9a`，Flyway v37）。**
  读 `.scratch/natural-language-constraints/spec.md`、`ticket-plan.md` 与 `issues/01..13`；真实评测已 **PASSED**（`gemini-3.8-flash-low` 54/54、12/12），任务已绑定、功能开关已开；不要用替身结果冒充。
- **票13 结构重构已完成并部署（2026-10-27，main `6ce8a2c`）**：共享 `ConstraintEntryMatcher`、`ConstraintParams`、`ConstraintRunCompletion`、`ConstraintAuditService`、AI 面板状态映射、E2E `pickOption` 收口；完整门禁 863/401/90。剩余“规划/持久化/模板”核心拆分见 [票14](natural-language-constraints/issues/14-core-service-split.md)（**2026-10-28 实现完成，分支 `feature/ticket-14-core-service-split`（`f87f1b6`），待合并/部署；纯结构重构，门禁 872/401/91**）。
- **Retro 已完成（2026-10-28）**：见 [.scratch/retro/2026-10-28-ticket04.md](retro/2026-10-28-ticket04.md)。本轮把票据状态检查、节次别名、surefire self-attach、并发测试 seam 规则、工作簿冒烟配方与 E2E 选择器稳定化落成了代码/脚本；剩余的是票 14 拆分与十期钉钉评审后续。

## 交差门槛（每票）

1. `cd backend && ./mvnw -B test` 全绿；前端 `npm run lint && npm run build && npm run test:unit`。
2. 用户可见的改动：`scripts/verify.sh --full`（含 Playwright）全绿，关键页面截图留存。
3. 部署：`cd ~/paiban && git pull --ff-only` → 重建 backend/frontend → `up -d`，确认 Flyway 版本与四容器 healthy。
4. 上线后冒烟：导入 → 补教室 → 发布 → 调代一单（申请 SWAP 必须带 `targetPeriodId`）。
5. 回写当天日志（`D:\work Buddy-cn\2026-10-11-22-22-40\.workbuddy\memory\`）与 `MEMORY.md`，重跑 `~/ops/state-snapshot.sh`。

历史归档：2026-10-25 及之前的确认记录见 git 历史。
