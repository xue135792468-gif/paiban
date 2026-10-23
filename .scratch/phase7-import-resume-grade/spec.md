# 排课系统七期规格：导入会话恢复与年级推断

Status: ready-for-agent

---

## Problem Statement

六期把 AI 课表导入跑通了，但真实验收暴露了三个体验缺口：

1. **切页/刷新丢结果**：AI 导入页没有 keep-alive，`useImportSession` 的预览只存在组件内；切到别的页面再回来，组件重新挂载，预览消失。后端其实已经保存了会话（`GET /api/ai/import/sessions/{id}`），但前端从未调用它恢复。
2. **年级没有适配**：课表里写「八（1）」，系统只按班级名称精确匹配；没有「八年级」时，映射步骤要求手动选/建年级，而且批量创建只有一个年级选择框，跨年级的班会建错。
3. **新建班级人数为 0**：映射创建班级时没有设置 `studentCount`，教室容量校验会偏松。

## Solution

1. **会话可恢复**：解析成功后把 `session` 写进 URL；刷新/切页回来按 URL 恢复；空页面提供「上次导入」入口（localStorage）。不引入 keep-alive（它救不了 F5）。
2. **年级推断**：从班级名称前缀推断年级（「八（1）」→ 八年级、「高一1班」→ 高一）；已有年级优先匹配，缺失时在确认后随班级一起创建。
3. **按年级批量创建**：批量创建按推断年级分组，每个班带自己的年级；班级人数默认 45，可改。

## User Stories

1. 我解析完切到别的页面再回来，预览还在。
2. 我按 F5 刷新，预览还在。
3. 我在空页面看到「上次导入」入口，点一下回到上次的预览。
4. 映射步骤显示「八（1）→ 八年级（新建）」。
5. 我全选后一次创建缺失的年级和班级，不需要先手动建年级。
6. 跨年级的班按年级分组创建，不会全建在一个年级下。
7. 新建班级的人数默认 45，我可以在确认时改。

## Implementation Decisions

### 前端

1. 解析成功后 `router.replace({ query: { ...route.query, session: String(sessionId), target: selectedTarget } })`；开始新上传/清除时移除 `session`。
2. 页面挂载时：若 `route.query.session` 存在，调 `GET /api/ai/import/sessions/{id}` 并 `setPreview`；加载失败显示可读错误，并允许重新上传。
3. `localStorage` 记录 `paiban.aiImport.lastSession`；无预览且存在上次会话时，显示「上次导入：文件名 · 时间 · 状态」+「继续」按钮。
4. `useImportSession` 增加 `restore(sessionId)`；恢复后按状态显示：`PREVIEW/EDITED` 正常编辑；`COMMITTED` 显示已入库；`FAILED` 显示失败原因；未知状态回退为新导入。
5. 映射步骤按 `inferredGrade` 分组：每组显示「新建/已有」；批量创建发送 `gradeName`/`gradeId` 和 `studentCount`；去掉全局单一 `batchGradeId`。
6. 班级名称默认保留文件原值（「八（1）」），不强行规范化；年级是归属信息。

### 后端

1. 新增 `GradeInference`（放在 `ai/imports/`）：从班级名称推断年级。
   - 规则：取名称前缀的年级 token；`八`/`7` 这类纯数字 → 加「年级」；`高一`/`初一` 这类已含学段 → 原样；`八年级` → 原样；无法识别 → 空。
   - 匹配：用现有 `normalizeName`（NFKC、去空白、小写）匹配当前学期年级；不做「初二 = 八年级」的别名映射，交给用户确认。
2. `IssueDto` 增加 `inferredGrade`（可空）和 `gradeCandidates`（当前学期已有年级名）。
3. `CreateItem` 增加 `gradeName`（可空）和 `studentCount`（可空）。
4. `ImportMappingService`：
   - 创建班级时接受 `gradeId` 或 `gradeName`；两者都空 → 400；都有 → 优先 `gradeId`。
   - `gradeName` 在当前学期不存在时创建年级（`sortOrder` = 当前最大值 + 1）。
   - 创建班级时设置 `studentCount`（缺省 0；前端默认 45）。
5. `TimetableImportService.inspect` 在班级问题上带出 `inferredGrade`/`gradeCandidates`。
6. `GET /api/ai/import/sessions/{id}` 契约保持不变，作为恢复入口；测试覆盖 `PREVIEW/EDITED/COMMITTED/FAILED`。

## Testing Decisions

1. `GradeInferenceTest`：`八（1）`、`八年级1班`、`高一1班`、`7年级(2)`、`初一3班`、无法识别。
2. `ImportMappingService`/`AiImportIntegrationTest`：`gradeName` 创建年级 + 班级；已有年级匹配；`studentCount` 写入；跨年级批量创建。
3. 前端 `useImportSession.spec.ts` / `AiDataImportView.spec.ts`：URL 恢复、localStorage 上次会话、状态映射、清除。
4. `TimetableMappingStep.spec.ts`：按年级分组、批量创建发送 gradeName/studentCount、缺年级阻止。
5. Playwright：解析 → 切页 → 回来 → 预览仍在；F5 → 仍在；空页面「上次导入」入口。
6. 帮助中心补充「切页/刷新恢复」和「年级推断」。

## Out of Scope

- 后台任务、部分结果、重试失败批次、放弃导入（八期）。
- 视觉/图片导入。
- 「初二 = 八年级」这类跨命名体系的自动别名映射（由用户在确认时选择）。
- 其他导入目标（行政班/课程/教室/授课任务）。

## Resolved Questions

1. 恢复载体：URL `session` 为主，localStorage 上次会话为辅，不用 keep-alive。
2. 年级推断：推断 + 确认创建，不静默写库；已有年级优先匹配。
3. 班级名称：保留文件原值，年级作为归属。
4. 批量创建：按推断年级分组，每班带自己的年级；人数默认 45。
5. 分期：七期做恢复 + 年级推断；八期做后台任务 + 部分结果/重试/放弃。
