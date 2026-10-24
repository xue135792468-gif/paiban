# 03: 十期评审后续项

**What to build:** 把十期 code-review 里不属于合并阻塞项的改进集中处理：预演显示匹配依据、部门 id 贯通、限制批量合并、前端提示抽取、停用计数口径与 ADR 维护。

**Blocked by:** None

**Status:** ready-for-agent

- [ ] 预演为 MERGE 行显示匹配依据（钉钉 UserID / 工号 / 手机 / 姓名+部门）；`DingTalkTeacherMatcher` 返回命中的键，而不只是教师
- [ ] 部门 id 贯通到预演 DTO 与确认请求，避免同名部门被合并、多部门用户只归到第一个部门
- [ ] 「全部合并同名教师」只在疑似用户只有一个候选时可用；多候选必须逐条选择
- [ ] 抽取 `SettingsView` 里重复的 `success ? ElMessage.success : ElMessage.error` + 结果 alert 形态
- [ ] 统一 `deactivated` 口径（新建即停用 vs 由启用变停用），或拆成两个计数
- [ ] 评估 `POST /integrations/dingtalk/sync` 是否继续保留为绕过预演的入口
