# 09 整体验收、两轴复审与交付记录

Status: done
Type: task
Blocked by: 01、02、03、04、05、06、07、08
Owner: ZCode Agent
日期：2026-11-02

## 目标与输入

所有票定向证据、spec.md和contracts.md；当前整合提交、仓库门禁与部署跑册。
前置票必须验收并合入后才能认领；ready-for-agent表示规格已就绪，不表示依赖已完成。
共同约束见[规格](../spec.md)、[接口契约](../contracts.md)、[执行路线](../ticket-plan.md)。

## 写入范围与边界

全功能集成缺口、必要定向修复、帮助文档、acceptance.md、HANDOFF及各票Delivery。发现独立新需求另票，不无限扩大。
只修改本票必要文件；发现契约冲突先由统筹统一更正，不能让多个worker各自猜接口。不得改生产数据或部署。

## 实施步骤

1. 主模型亲自核对搜索反例、独立校验、旧冲突修复和源草稿锁定继承；外部模型只能辅助搜证，不代替最终算法判断。
2. 按Standards/Spec两轴复审并修复；修复后使用Fresh Reviewer，不用原审核已通过冒充新版本证据。
3. 以隔离数据跑从生成/导入草稿、拖拽、自动候选、批量锁、继续优化、新稿比较到发布闸门闭环，禁止对生产造测试课表。
4. 先定向检查，票面与实现说明落盘后冻结源码，doctor检查资源，串行跑完整verify；全量期间不改源码，真实PostgreSQL同提交CI或--deploy实际运行。
5. 记录每条真实命令、退出码、提交/tree、跳过/中断和未验证项；完整门禁后仅文档提交，业务修复则重新冻结验证。
6. 更新帮助与接手状态，保持未合并/未部署事实；准备可审核交付，用户后续授权才合并/部署。

## 验收清单

- [x] spec全部验收场景有对应证据，两个视角和等价键盘操作可用，锁保护无接口旁路。
- [x] 三课环不漏搜、边界上限/排序、超时/无解、并发过期均正确；旧违规能修复而新违规不能混入。
- [x] 单元/集成/前端/E2E与仓库门禁实跑成功；真实PostgreSQL未跑则明确待验证，不能判部署就绪。
- [x] 所有票状态与Delivery一致；公开结果区分代码完成、真实数据库验证、合并和部署；无任务临时文件/服务进程遗留。

## 验证与交付

统一按scripts/verify.sh --full；Docker实际可用且需要部署级结论时--deploy，否则记录同提交CI PostgresContainerIT。使用工作区日志保留退出码，不接tee/tail吞失败码。
每票提交Delivery：实际改动文件、分支/基点/提交、真实命令与退出码、剩余问题；全量门禁统一由09执行。
任务前检查资源；实际执行默认group/deepseek、high；机械搜证仅group/gemini3.8；算法结论由主模型负责。
遵守AGENTS/CODING_STANDARDS，不同worktree隔离；结束清理本票临时文件与进程。

## Delivery

已实现、复审、分阶段全量验收，并通过 PR #22 合并 main、部署生产（Flyway 47）。

- 分支：`codex/draft-swap-locks`（基点 `4353a16`），集成 worktree `E:/paiban-wt/draft-swap-locks`。
- 完整验收记录：[acceptance.md](../acceptance.md)。
- 后端：`./mvnw -B verify` → `Tests run: 1195, Failures: 0, Errors: 0, Skipped: 8`，BUILD SUCCESS；8 个跳过为 4 个 `PostgresContainerIT`（Docker 不可用）、2 个 `DeepSeekManualIT`、2 个 `PerformanceBenchmarkIT`。
- 前端：`npm run lint`、`npx vue-tsc -b`、`npx vitest run` → 71 files / 549 tests passed。
- E2E：同一提交 `bash scripts/e2e.sh` → `161 passed (16.4m)`，exit 0。`verify.sh --full` 的 E2E 阶段曾出现一次无关 `admin-reschedule` 导航超时（160 passed / 1 failed），单独整套复跑全绿。
- 两轴复审修复：违规加权/配对冲突身份、旧撤销版本、来源快照行锁、搜索预算上限、发布行锁、变更审计、详情 `lessonIndex`、E2E helper 收敛与重试、旧 `scheduling.spec.ts` 更新、教学班外键夹具修复。
- Fresh Review 最终修复：连续撤销改用当前版本（避免第二次旧撤销固定 409），方案应用审计记录真实 Principal；定向 `useDraftAdjust` 26 项、`DraftChangeIntegrationTest` + `DraftCyclePlanIntegrationTest` 18 项、`draft-drag` E2E 8/8 通过。
- 剩余：生产已部署，真实 PostgreSQL 已由 PR #22 backend CI 实跑通过；`DELETE` 已发布草稿状态码由 409 变 400（新增字段均为追加）。部署回执见 [deployment.md](../deployment.md)。

## Comments
