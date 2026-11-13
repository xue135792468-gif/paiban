> 2026-11-13 最新补查：08手机真实入口重开。默认本人课表在窄屏无侧栏/其他导航，原E2E直接goto掩盖缺口；主控真实红例1失败且源冻结，详见dingtalk-multiuser/whole-mobile-navigation-qc.md（feature目录内为同名文件）。唯一fixer Turing第一批H1重构及Java定向已交付，第二阶段补手机导航并改原整链为真实点击。原三阶段截图不是全移动端导航验收。最终full未跑，08不放行，未push/部署，09拒绝清理保留。

> 2026-11-13 最新：原八票源码均已整合，含08的固定审源be48fc0、接收单2c09fdb；主控验收见t08-main-acceptance.md（HANDOFF中链接为dingtalk-multiuser/t08-main-acceptance.md）。01–07为implemented，08三项专项验收已具备，仍待整特性两轴终审、统一fixer整改与最终一次full verify，不能标done。Standards预审H1交Turing；Spec预审四项观察已由原轴自行核对撤回，原稿/更正均保留。未push/部署，真实PG/租户/手机未验，09删除拒绝继续保留。

> 2026-11-13 08已施工：07最终双轴放行文档b7cac2e。主控负责dingtalk-t08-chain后端整链；Galileo负责dingtalk-t08-upgrade存量迁移；Dalton负责dingtalk-t08-browser手机/电脑真实UI整链。基于同源016859b，结束合最新集成。原八票最终全量仍未跑，未push/部署。

> 2026-11-13 最新：07已由主控单票放行，固定最终审源016859b，双轴及增量见t07-review.md，验收见t07-main-acceptance.md。01/02/04/03/05/06/07均implemented，08为当前frontier；下方07未放行是历史快照。最后两行E2E定位补核无新问题，四组11项实际通过。最终八票全量未跑，未推送/部署；已拒绝清理继续按09保留。

# 钉钉多用户实施与质量闸门

日期：2026-11-11（Asia/Shanghai）

> 2026-11-13 最新frontier仍07：原两轴已完成并主控判定，详见t07-review.md；单一fixer Planck在dingtalk-t07-review-fixes收口确认项，之后主控四组E2E与两轴增量。原始验收与截图见t07-main-acceptance.md，尚非单票放行。08只准备，t08-coordination-plan.md仅分工门槛；Gemini旧库机械摘录已证不可靠，文件加主控警示，施工仅读真实源/元数据。最终全量未跑，未push/部署。以下未放行与开工记录均按发生时点保留。

> 2026-11-13 最新：06已单票放行，见t06-review.md / t06-main-acceptance.md。原八票01/02/04/03/05/06均implemented，当前frontier=07；08仅准备。07工程分工见t07-coordination.md。下方未放行文字均为历史快照；最终full verify未跑、未推送/部署。

07现已认领in-progress，后端Sagan与前端Linnaeus分别在dingtalk-t07-backend、dingtalk-t07-ui执行；主控负责手册和整合浏览器。基点源码877dee9、06放行文档e7d0bfc。尚未合入07源码，未运行07验收。

> 2026-11-13 当前 frontier：01/02/04/03/05 均 implemented 且单票复查放行；05 最终见 t05-review.md。06 已从 05 放行源启动四个独立写集工作树（core / transport / ui / log-api），接口与质量闸门见 t06-wire-contract.md / t06-quality-plan.md；07/08 仍仅准备。以下进度段按发生时间保留，不据旧“待放行”文字重新施工。原八票最终全量尚未执行；未推送、未部署。

> 06 当前已整合，独立QA也已合入；主控源码90bd973后端33与浏览器6通过。两轴复查及主控QC1/QC2待统一整改，尚未单票放行，具体见 t06-main-acceptance.md；这不改变07/08的准备状态。

用户已调用 implement-spec 授权完整八票实现，要求重要或高风险票完成后先双轴 code-review 再进入下一票。仅交付集成分支，不包含生产部署。

- 集成分支：`codex/dingtalk-multiuser`，起点 `2e5e585f6ed80d5024af6b734162a70829b067cf`。
- 规格及单票：本目录 spec.md 与 issues/01–08，产品定稿不再重复确认。
- 已确认测试边界：spec.md Testing Decisions；HTTP→返回与最终数据库/审计/通知，旧库迁移保全；原因码组件测试与手机老师→电脑审批→老师状态的 Playwright。采用 TDD 纵向 red→green，不测私有方法或内部调用次数。
- 每票独立分支/worktree，基于集成最新提交；依赖链默认串行，以避免 UserService、SettingsView 和迁移写集冲突。仅独立的只读审查并行。
- 01、02、03、04、05、06、07 均设置双轴复查闸门；每次固定比较基点为该票开工前集成 SHA，最终比较集成起点。Standards 与 Spec 独立报告，主模型复核证据并决定整改。
- 初始 frontier：01；01 后开放 02/05/07；02 后开放 03/04；05 后开放 06；03/04/05/06/07 后开放 08。执行优先 01→02→03→04→05→06→07→08，必要时仅在复查完成后调整独立票顺序。
- 执行默认 group/deepseek high；机械搜索只用 group/gemini3.8；UI 设计顾问用 ai-studio/gemini-4-argon，不调用 Hark。
- 迁移新增前核查所有 worktree/分支版本占用，不改旧迁移。main 已到 V59，票面 V59“计划中”是历史信息。
- 每票仅定向测试；所有评审整改、单票说明落盘后冻结源码，最后一次完整 verify。真实 PostgreSQL 未跑的必须明确标记；不得以 H2 代替真实 PG 验证。
- 任务文件与临时进程限当前工作区，结束清理临时文件与进程。worktree 使用 scripts/worktree-add.sh/remove.sh，保护依赖 junction。

## 当前事实

2026-11-11 只读核验：生产 e22e312，Flyway59，四容器healthy，health UP。教师162、钉钉绑定160、账号7、已发布课节1092、草稿课节2730，DINGTALK_SYNC审计2条。不得沿用旧HANDOFF“48教师、0绑定、从未确认同步”作为当前状态。

## 进度

- 实施准备：已建立独立集成工作树并纳入原规格与8张票。
- 01：**implemented（施工完成、待放行）**。设置字段权限切片已合入4fa09b6，前端角色与入口切片已合入1d6b895，角色迁移/种子/超管保护后端切片与 `RoleUpgradeDataPreservationTest` 已合入56a98c9。双轴复查（Standards 8 项 / Spec 5 项）已在 `codex/dingtalk-t01-review` 逐项整改并复核：S1–S8、F1–F5 与两项额外 QC 全部处置，逐项结论见 `t01-review.md`，红绿与命令证据见 `t01-review-fixes-delivery.md`。整合后的真实验证：旧库升级保全 1/1、正式 E2E 4/4（含多角色「只改姓名不丢身份」回归，并做过变异敏感性）。票面仍未置 done，其余票等待 01 放行。
- 02：**implemented（施工完成、待双轴复核）**。审计复用 `audit_log`（V61 加 `category` / `target_user_id` / `before_json` / `after_json` 与索引），六类现有账号动作逐目标落审计（批量导入逐账号 `ACCOUNT_IMPORT`），预留 03/04 三类动作；账号审计页 `/settings/account-audit` 与 `/api/account-audits` 走 `audit:view:account`，过滤分页在数据库层；旧 `foundation/audit-logs` 在数据库层排除账号事件，教务不能从旁路读到。交付与实测数字见 `t02-delivery.md`；e2e 由独立 QA 提供（`frontend/e2e/account-audit.spec.ts`），待整合后运行。

01、02 的整改后双轴复核及统筹处置已经放行，详见各自 review 文档。状态暂保留 implemented，整个规格最终完整 verify 通过后统一置 done。02 最新整改及实测见 `t02-review.md`，上方初次施工描述为阶段快照。

03 首次身份绑定的权限冲突已于2026-11-11收到明确产品答复：采用推荐保护（人工UID仅超管可改、管理员首次绑定需超管确认、普通老师自动配对不变），详见spec及t03-module-plan。当前已转入04施工，先完成04及双轴复核，再回到03，不并行改写共享集成/账号源码。

04 已完成A/B/C整合与实际HTTP、浏览器验收，状态implemented。首轮双轴复查后加固了教师UID并发写入窗口，两个轴的增量复核均确认无新硬问题，统筹已放行，下一票03；详见 `t04-delivery.md` 和 `t04-review.md`，本入口不重复统计。此前无变更、无进度返回的首个04 worker已停止，未丢弃代码。已合并的A/B/C1及安全测试工作树用项目脚本移除，主线程施工树待最终整合后移除。

任务卫生：02整改工作树已通过专用脚本移除，主要日志在集成树 `logs/t02-review/`；截图已跟踪。集成树的本任务 `.tmp/` 删除被工具自动审批策略拒绝（只返回 blocked by policy），目前原样保留，不能声称临时文件全部清理。

每张票的实测结果只写单票交付记录；本文件记录依赖与评审入口，不复制测试统计。

03已于2026-11-11通过施工、两轴整改增量复核与真实浏览器验收并由统筹放行，暂implemented；入口t03-delivery.md/t03-review.md。下一票05，仍待整票施工与双轴放行后再06。最终八票全量verify尚未运行。UI工作树任务runtime/截图副本联合清理被自动审批拒绝，保留现场及工作树，09记录为准。

05已从e091d0f启动：codex/dingtalk-t05-backend与codex/dingtalk-t05-ui独立写集；主模型负责锁/事务/权限取舍和测试调度。当前后端定向TDD优先，前端先准备红测试，避免内存不足并发重测。契约与旧调用方保全见t05-wire-contract、t05-transaction-plan、t05-caller-regression-map。06/07研究准备不等于施工。

2026-11-13 最终源已整合至7413a01，实际受影响浏览器32项通过，手机默认课表到申请的真实入口M1关闭；专项、兼容性说明和最终评审入口见t08-main-acceptance.md、whole-review.md。最后frontier为冻结源串行完整verify，完成前原八票均不标done。未push/部署，09拒绝删除保留。

2026-11-13 首轮完整verify真实失败，后端1265项仅RoleReshape角色目录断言1失败，退出1且源冻结；前端阶段未执行，08不done。只修测试断言范围的独立worker已启动，修复/复查后需新完整门禁，入口final-acceptance.md。
