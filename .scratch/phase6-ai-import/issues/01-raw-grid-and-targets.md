# 01: 原始网格读取与导入目标框架

**What to build:** 给 `ExcelTableReader` 增加 `readGrid`：把工作表按原始网格读出（使用中的单元格、合并单元格展开、公式求值），返回工作表名与二维字符串数组；自动识别路径不再按「第一行是表头」裁剪。给 `ImportTarget` 增加 `TIMETABLE`（班级/星期/节次/课程/教师/教室）和 `kind`（ENTITY/GRID）。新增 `AiImportPrompts.gridPrompt`，把原始网格 + 目标字段交给 AI；`textPrompt`/`imagePrompt` 去掉教师硬编码。AI 超时默认改为 120 秒，超时类失败最多重试 2 次。完成后，AI 能看到完整表格，教师导入行为不变，课表目标可以被识别和预览。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] `ExcelTableReader.readGrid`（合并单元格、公式、多工作表、空表）
- [x] 网格上限：超过 500 行或 60 列返回可读错误，提示拆分
- [x] `ImportTarget.TIMETABLE` + `kind`（ENTITY/GRID）
- [x] `AiImportPrompts.gridPrompt` + 目标字段注入；text/image 提示词去教师硬编码
- [x] `AiImportService.startExcel` 自动识别路径改用 `readGrid`
- [x] `paiban.ai.timeout-seconds` 默认 120；超时类失败最多 2 次
- [x] `ExcelTableReaderTest`、`AiImportPromptsTest`
- [x] 教师导入回归测试
