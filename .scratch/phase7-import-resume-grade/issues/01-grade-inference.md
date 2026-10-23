# 01: 年级推断与问题列表

**What to build:** 新增 `GradeInference`，从班级名称前缀推断年级；`TimetableImportService.inspect` 在班级未匹配问题上带出 `inferredGrade` 与 `gradeCandidates`（当前学期已有年级名）。完成后，映射步骤能显示「八（1）→ 八年级（新建）」或「→ 八年级（已有）」。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `GradeInference`：前缀 token、纯数字补「年级」、初/高原样、无法识别为空
- [ ] 用 `normalizeName` 匹配当前学期已有年级
- [ ] `IssueDto` 增加 `inferredGrade`、`gradeCandidates`
- [ ] `TimetableImportService.inspect` 填充两个字段
- [ ] `GradeInferenceTest`：八（1）、八年级1班、高一1班、7年级(2)、初一3班、无法识别
- [ ] 集成测试：预览的班级 issue 带出推断年级与候选
