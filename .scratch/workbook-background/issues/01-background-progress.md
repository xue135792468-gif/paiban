# 票据 01: 整本解析在后台运行并可恢复进度

**What to build:** 上传后即可获得会话，页面展示规划/取值阶段和逐批进度，刷新后仍能看到已完成结果。
**Blocked by:** None
**Status:** done
**Execution:** 已实现（2026-10-26，`feature/workbook-background-import`）
**Spec:** ../spec.md

- [x] 工具规划与取值在导入线程串行执行；规划阶段不显示伪造的批次数。
- [x] 稳定计划包含工作表、范围和结构版本；批次原文、结果和计数及时持久化。
- [x] 规划阶段失败且无确认计划时，页面提供显式重新规划入口；已有计划的恢复不自动重跑工具规划。
- [x] 单批失败后仍处理其他批次，结果可查看，失败/未完成范围存在时前后端均不可提交。
- [x] 刷新、重启、执行器拒绝、存储失败有可恢复且可观察的状态；解析不写业务主数据。
- [x] REST＋受控假网关观察中间进度，Playwright 覆盖刷新恢复；旧单目标流程保持兼容。
- [x] 先红后绿，独立评审与完整闸门；下一批启动前复核规格及当前代码。

**交付范围：** 一条完整上传→后台→轮询→预览路径，不按“只做数据库/只做前端”拆分。

**复核记录：** 批次启动复核通过（2026-10-26）：`importExecutor` 与会话状态/批次字段仍与草案一致；接口测试需显式带 `?target=`。

**实现说明：** `WorkbookImportFlow.start/reparse` 在 `importExecutor` 上跑规划与取值；上传同步读取并 profile 文件，坏文件仍同步 400。规划每完成一轮就追加到 `tool_trace_json`，规划失败或中途停止也留得下已跑过的工具轮次。新增 `ImportProgressDto.phase`（`PLANNING`/`EXTRACTING`）与 V30 的 `plan_json`/`plan_version`/`run_id`；`WorkbookBatchStore` 按「工作表 + 起始/结束数据行 + 计划版本」落每批源行、结果与每次原始回答。规划失败（无计划）为 `FAILED`，页面显示「重新规划」；单批失败继续其余批次并收尾 `PARTIAL`，提交前后端都拒绝。重启把 `PARSING` 收为 `PARTIAL`（有计划）或 `FAILED`（无计划）。测试：`WorkbookBackgroundImportTest`（10 例，含规划阶段无假总数、失败继续、停止/放弃/重试、停止后重试、无完成批次停止、权限、重启恢复、队列拒绝），`WorkbookImportIntegrationTest` 改为轮询结算；E2E 新增「整本工作簿在后台分批解析，刷新后仍能看到进度并走到预览」。
