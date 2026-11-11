# 钉钉多用户实施与质量闸门

日期：2026-11-11（Asia/Shanghai）

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

每张票的实测结果只写单票交付记录；本文件记录依赖与评审入口，不复制测试统计。
