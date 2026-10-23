# 04: 映射步骤按推断年级批量创建

**What to build:** 映射步骤把班级问题按 `inferredGrade` 分组，显示「新建/已有」；批量创建按组发送 `gradeName`/`gradeId` 和 `studentCount`，去掉全局单一 `batchGradeId`。推断失败的班才要求手动选年级。班级名称保留文件原值。

**Blocked by:** 01, 02

**Status:** ready-for-agent

- [ ] 班级问题按 `inferredGrade` 分组展示（新建/已有）
- [ ] 批量创建按组提交 `gradeName`/`gradeId`
- [ ] 班级人数输入，默认 45
- [ ] 推断失败时要求手动选年级
- [ ] 去掉全局单一 `batchGradeId`
- [ ] 单测：分组、跨年级批量、人数、缺年级阻止
