# 09: 导入时提示"这看起来不是人名"的教师值

**What to build:** 真实工作簿里 14 节「信息科技」的教师列写的是「信息技术」——原系统拿岗位名当了占位符，导入后教师列表里多出一个叫「信息技术」的"老师"。教师是自动创建的主数据，这种值会一直留在库里，并且出现在排课候选里。导入预览应当把可疑值标出来，让人在写库前就能改。

**Blocked by:** None（2026-10-25 用户导入真实文件时发现）

**Status:** done

- [x] 预览/工作表摘要里标出可疑教师值：与某门课程同名、与课程名互为子串（如「信息技术」vs「信息科技」是近似，不强制）、或命中岗位词（老师/教师/待定/暂无/机房/自习）
- [x] 提示不阻断入库，只标黄并在结果页列出清单
- [x] 用户可以在预览里改名（或入库后到教师页改名，课节按 id 引用不会断）——采用原票允许的入库后改名，预览与结果页都给了入口
- [x] 单元测试：课程/教师同名的合成数据触发提示；正常姓名不触发

**实现说明：**

规则位置：`backend/src/main/java/com/paiban/ai/imports/SuspiciousTeacherNames.java`，纯函数（无 IO、无模型调用），`inspect(occurrences, courseNames)` 返回聚合后的清单。三条确定性规则，命中任一即提示：

1. `sameAsCourse`：规范化后与某门课程名称完全相同；
2. `containsCourseName`：规范化后两者互为子串，且较短一方长度 ≥ 2（单字重名不提示）；
3. `placeholder`：命中固定词表（包含即命中，取第一个命中的词）。

固定词表：`老师`、`教师`、`待定`、`暂无`、`机房`、`自习`、`信息技术`、`未定`。问题样例“信息技术”是显式词项，不是靠“信息技术/信息科技”子串命中（两者并非子串关系，测试专门断言该值只由词表命中）。不引入编辑距离、相似度或 AI 判姓名。

规范化定义（`SuspiciousTeacherNames.normalize`）：NFKC（全角→半角）→ 去掉括注（`（）[]【】〔〕〖〗` 及其中的内容，`张三（代课）`→`张三`）→ 去掉空白（含全角空格）、ASCII 标点与常见中文标点（，。、；：？！·—… 引号括号等）→ 转小写。比较值用规范化结果，展示值保留首次出现的原文（仅 `strip()` 两端空白）。

比较用的课程名来自本工作簿自身：课表行的 `courseName` 列 + 课程清单工作表的课程名，不额外查库，因此同一份文件的可预览结果稳定、可复现。教师值来源同样包含课表行的 `teacherName` 与教师名单工作表的姓名（后者无课节，`lessonCount=0`、`rowNumbers=[]`）。

响应字段（独立非阻断通道，不进入 `issues`/`blockers`，不复用 `row=0` 语义）：

```
teacherWarnings: [
  { value, reasons: [{ code, message }], lessonCount, rowNumbers }
]
```

- `GET/POST/PUT /api/ai/import/sessions/{id}`（`PreviewDto`）与 `POST .../commit`（`CommitResultDto`）返回同一份聚合结果；它持久化在 `ai_import_log.preview_json`（`WorkbookPayload.teacherWarnings`），所以刷新、恢复会话、入库后的结果页读到的是同一份数据。
- 去重口径：按规范化值合并，一个值只出一条；`lessonCount` 为该值出现的课节数，`rowNumbers` 为 1 基预览行号（网格课节 id 即其负数），升序且去重；多个命中原因合并在同一条的 `reasons` 里。
- 前端计数同源：`AiDataImportView` 只算一次 `teacherWarnings`（`commitResult ?? preview`），同一数组传给预览网格、工作簿摘要与结果页，页面上的“疑似教师值 N 个”就是该数组长度，前端不再重算。

呈现：

- 预览网格：可疑值所在课节加黄框（沿用既有 `DraftGrid` 的 highlight/conflict 样式体系，新色取 Element Plus warning 色），图例补一行“黄框：教师值疑似课程名或占位词”，网格上方列出可疑教师值。
- 工作簿摘要/结果页：`SuspiciousTeacherList` 组件列出去重清单，每条显示教师值、课节数（教师名单值显示“教师名单”）、命中原因（`reasons[].message` 拼接）。
- 改名入口：清单下方“到教师页改名”跳到 `/admin/teachers`。课节按教师 id 引用，改名不断链（集成测试覆盖：改名后草稿仍校验通过，课节显示新姓名）。

测试：

- `SuspiciousTeacherNamesTest`（10 条）：同名、含课程名、课程名包含教师值、单字重叠不命中、7 个固定词逐个命中、正常姓名（张伟/李娜/欧阳明）不命中、一词多因、同值去重与计数、全角空格与括注归一。
- `WorkbookImportIntegrationTest#aPlaceholderTeacherValueIsWarnedButStillImportsAndSurvivesARename`：合成工作簿（信息科技 / 信息技术，另有张伟、李娜）→ 预览 200 且 `teacherWarnings` 只有一条、`issues` 为空 → 入库 200、3 节课成草稿 → 教师页改名 200 → 改名不产生结构性阻断（整草稿检查仅剩导入本就不填的 `MISSING_ROOMS`）→ 补教室后整草稿检查 `valid=true`，课节显示新姓名（票 13 语义：整草稿检查已包含缺教室）。
- 前端：`timetablePreview.spec.ts`（行号→课节 id）、`TimetablePreviewGrid.spec.ts`（标黄与提示）、`WorkbookSummaryPanel.spec.ts` / `CommitResultSummary.spec.ts`（清单、计数、原因、改名入口）。

**背景：** 2026-10-25 真实文件：560 行中 14 行教师=「信息技术」、课程=「信息科技」，48 个教师名里混入这一个占位值。
