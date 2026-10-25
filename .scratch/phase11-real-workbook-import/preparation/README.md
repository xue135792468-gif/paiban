# 待办准备审查与执行入口（2026-10-25）

本文件由本轮 ask-matt 复查产生。用户当前授权是**完成准备材料，修复由新的 agent 执行**。本轮仅做代码阅读、需求确认与文档整理；没有继续修代码、重新测试、部署或修改线上数据。

## 路由结论

| 原票 | 是否继续 grill | 规格与票据处理 | 新 agent 入口 |
|---|---|---|---|
| 11 保存作息毁草稿 | 不需要；是明确的数据完整性缺陷 | 保留现票，补引用审查、迁移与回归边界 | repair-spec.md 的 11 + 原票 |
| 12 导入班级人数为 0 | 不需要；默认 45 已由手工建班与实体确定 | 保留现票，覆盖整本导入与映射新建两条入口 | repair-spec.md 的 12 + 原票 |
| 13 校验和发布不一致 | 不需要；属于一致性缺陷 | 保留现票，明确整草稿检查与局部移动检查的区别 | repair-spec.md 的 13 + 原票 |
| 07 补教室 | 两种产品方案本轮已问定，无剩余问题 | 补统一规格，保留一个完整纵向票；不按前后端拆 | repair-spec.md 的 07 + 原票 |
| 08 补缺失节次 | 推算失败策略本轮已问定，无剩余问题 | 补统一规格，保留一个完整纵向票 | repair-spec.md 的 08 + 原票 |
| 09 可疑教师名 | 不需要整场 grill；原票允许入库后更名 | 保留现票，明确只提示、不扩展工作簿编辑能力 | repair-spec.md 的 09 + 原票 |
| 10 多条约束与布局 | 本轮决定已确认；视觉仍需原型确认 | 独立 spec + 已获用户认可的 6 张票 | ../../constraint-entries/spec.md |
| 02 整本导入进度/停止/重试遗留 | 当前需求可复用八期，不重新整场 grill | 独立下一批 spec + 3 张草案；本轮不纳入修复范围 | ../../workbook-background/spec.md |
| 14 自然语言约束 | **下一期需 grill-with-docs** | 本轮只记录问题树，不发布实现 spec 或 agent-ready 票 | next-phase-questions.md |

原始 issue 的状态与历史描述保留，不将它们当作本轮已完成的证据。票 10 原文件的两项待问已由本轮记录解决；新的执行规格以 constraint-entries 为准。票 02 原未完成项仍保持未完成。

## 用户已确认的决定

1. 六项修复继续按 11 → 12 → 13 → 07 → 08 → 09 的顺序交给新 agent；本轮只准备。
2. 07：用户显式确认后，为每个行政班创建固定普通教室；只填缺失项，不强行处理专用教室或冲突。
3. 08：先展示拟新增时间，再确认写入；没有可靠参照、内部空档放不下、跨午夜等情况转人工。
4. 10：分组折叠卡片，卡内一行一条；阈值采用全局默认＋具体对象覆盖；同类条目共用启用、软硬与权重。
5. 10：批准拆成原型、教师禁排首条纵向切片、其余目标型、阈值型、模板、完整页面六张票。原型须经用户确认后才通过第一道门。
6. 14：下一期，本轮只保留问题清单。
7. 02 遗留：独立下一批，本轮只准备三张草案，不随六项修复实施。

## 顺序、依赖与放行

- 六项修复的排队顺序是用户指定的执行顺序，**不是全部相互存在代码依赖**。11、12、13 可以独立复现和修复。
- 07 使用 13 统一后的整草稿检查；12 要先完成以使导入人数及容量判定可信。这两项是本批集成放行前置。
- 08 放行前必须完成 11 的课节引用保护；补齐动作本身只能追加，不允许借保存全量作息重建已有课节。
- 09 不依赖 07/08 的代码接口，仍按指定顺序交付。
- 票 10 不依赖六项修复的新 API；安排在它们之后是批次优先级。其六张子票的真实前置见各票。
- 下一批整本后台导入与六项修复无硬代码依赖，使用同一导入模块时顺序集成，避免同时改状态机。
- 14 的“确认应用”依赖多条约束的稳定保存契约；只读建议或语料调研不需要等布局验收，但目前都未排进本批。

## 暂停中的候选修复：不能当成完成成果

- 原始代码基点：`95fefdd514ac33b358c7d5b284685ff06cdd3905`。
- 主仓库：`E:\排课系统`，准备文档分支 `codex/backlog-preparation`。
- 候选修复隔离在 `E:\paiban-wt\import-repairs`，分支 `codex/import-repairs`，HEAD 同上，没有提交或部署。
- 候选区改了 SemesterService、DraftLessonRepository、TimetableImportServiceTest；另有未跟踪 V24 外键迁移。不要用 `git diff` 的三文件统计误认为迁移不存在。
- 原回归先红：保存后课节 id 期望 1，实际 41。证据：候选区 `.scratch/ticket11-red.log`。
- 后续已启动的定向测试日志现已完整读取：27 项中 1 项失败，`BUILD FAILURE`。失败断言是清空 slots 期望 409，实际 400；现有请求契约有 `@NotEmpty`。该用例没有到达“删除被引用课节”的服务层判断。
- 新 agent 应在有效的非空作息请求中移除一个被占用槽位来测试这条行为，另保留一个槽位；不要为满足错误断言而取消请求校验。
- 本轮未重跑测试。候选修复仍须重新读 diff、确认迁移编号、继续 TDD、独立评审和完整闸门，不可盲目 cherry-pick/宣称绿色。
- 主仓库原有未跟踪 `docs/reports/` 与旧 worktree `integration` 均保留，不属于本次准备成果。

## 事实复查中发现的漏项

- **12 不止一个入口**：整本自动建班和映射“新建班级”都会显式写 0；手工建班缺人数则使用实体默认 45。预览临时渲染模型中的 studentCount=0 不等于数据库业务值，不能机械全库替换数字。
- **08 缺节发现过晚**：整本预览只检查“有没有任何作息”；具体星期/节次缺失是在提交时才发现。应从已保存预览计算差集，并刷新预览，不重新调用模型。
- **09 工作簿预览是只读**：不能在这张提示票里偷偷加入整本预览编辑、分类重算和审计改造。原票已允许入库后在教师页改名，可采用此路径。
- **09 样例不等于规则命中**：“信息技术”和“信息科技”并非互为子串。首版不做模糊相似度；将“信息技术”作为明确可疑占位词纳入合成验收，持续仅提示。
- **10 不只改求解器**：草稿检查、调代硬校验与软评分、只读 CLI、模板和全目录参数语义都要审查。
- **10 原票“导入不受影响”过宽**：导入写入不执行排课硬约束，导入草稿在后续校验/发布时仍受约束。
- **02 不能直接复制旧批次计划**：旧管道“一班一批”，整本管道为工作表＋范围＋结构版本；跨表去重与审计需要适配。
- 八期明确不支持缺班的部分入库；下一批草案沿用这个边界，停止保留结果供检查/重试，不代表可以提交不完整课表。

## 给执行 agent 的最短指令

> 先读 .scratch/HANDOFF.md 与 .scratch/phase11-real-workbook-import/preparation/README.md，再按 repair-spec.md 和原票，以 /implement＋TDD 顺序处理 11、12、13、07、08、09。先检查暂停候选 worktree，不能视为已完成。每票做独立 Standards/Spec 评审，跑 scripts/verify.sh --full，按项目流程备份、部署和冒烟。票 10 只在六项修复后进入独立规格，先做原型并等用户确认；整本后台导入和自然语言约束均为下一批/下一期。本准备文档本身不授权当前会话继续修复。

## 证据入口

以下 Java 模块简称相对 backend/src/main/java/com/paiban，其余路径相对仓库根。阅读时核对当前版本，不把本记录当实时线上状态。

- foundation/service/SemesterService、foundation/web/SemesterDtos；迁移 V7、V8、V10、V22；scheduling/domain/DraftLessonRepository。
- ai/imports/ImportMappingService 的 create/ensureClasses、foundation/domain/AdminClass、foundation/web/FoundationDtos。
- scheduling/service/DraftValidator、timetable/service/TimetablePublishService、scheduling/service/SchedulingService。
- ai/imports/workbook/WorkbookImportFlow 与 WorkbookExtractor；ai/imports/AiImportService；frontend/src/components/aiImport/useImportSession.ts。
- scheduling/domain/ConstraintType、solver/ConstraintSettings、constraint/ConstraintChecker、solver/PaibanConstraintProvider；substitution/plan/PlanVerifier、service/PlanSnapshotFactory；frontend/src/views/ConstraintsView.vue。
- .scratch/phase8-import-background/spec.md、docs/adr/0026-real-workbook-import.md。

## 准备材料复核

两路只读复核分别检查导入/补齐与约束路径。发现并已补齐：07 检查整个学期的发布基线；无计划失败采用显式重新规划而非批次重试；同类只有一个启用开关；02–04 每张约束子票都要求配置改变后的调代重验。另检查目录覆盖为 24 类、六张正式子票和三张延期草案的依赖及文件入口。

本记录是需求与文档复核，不能替代执行阶段的代码评审、迁移验证或完整应用测试。
