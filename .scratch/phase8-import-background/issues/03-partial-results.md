# 03: 部分结果（PARTIAL）

**What to build:** 某一批解析失败不再让整个导入失败：已成功批次保留，会话状态为 `PARTIAL`，预览同时展示已解析的班与失败班列表（含失败原因）。部分结果下不提供确认入库，避免缺班的草稿被误发布，用户必须先重试或放弃。

**Blocked by:** 01

**Status:** done

- [x] 单批失败时会话进入 `PARTIAL`，成功批次的行仍可预览
- [x] 会话响应暴露失败批次、失败班与失败原因
- [x] `PARTIAL` 下确认入库保持禁用，并提示先重试或放弃
- [x] 前端把成功行与失败班分组展示，失败原因可读
- [x] 测试覆盖「单批失败」「多批失败」与「成功批次不丢」

**实现说明：** `PreviewDto` 增加 `progress`（total/completed/failed/currentBatch）与 `failedBatches`（index/label/reason）。`PARTIAL` 下 `commit`、`rows`、`mapping`、`continue`、`reparse` 一律 409，文案指向「先重试失败批次或放弃导入」。
