# 六期实现：AI 导入区代码笔记

供实现子代理快速定位，不替代读代码。所有路径相对仓库根。

## 构建与验证

```bash
source /e/排课系统/.tools/env.sh          # worktree 内没有 .tools，必须用主仓库的
cd backend && ./mvnw -Dtest=ClassName test
scripts/verify.sh                          # 本地闸门
```

- worktree 的 `frontend/node_modules` 是指向主仓库的 junction，不要 `npm install`。
- Playwright 在 worktree 里需要 Windows 路径的 `MAVEN_ARGS`：
  `-s E:/排课系统/.tools/maven-settings.xml -Dmaven.repo.local=E:/排课系统/.tools/m2repo`。

## 后端地图

| 关注点 | 位置 |
|---|---|
| 导入入口 | `ai/imports/AiImportController.java`（`/api/ai/import`，`foundation:manage`） |
| 导入服务 | `ai/imports/AiImportService.java`（`startExcel/startText/startImage`、`runPreview`、`callAi`、`commit`） |
| 目标定义 | `ai/imports/ImportTarget.java`（目前只有 `TEACHER`；注释说明加目标=加枚举+入库钩子） |
| DTO | `ai/imports/ImportDtos.java`（`RowDto.values` 是 `Map<String,String>`，天然通用） |
| AI 输出解析 | `ai/imports/AiImportParser.java`（按 `target.fieldNames()` 取字段；空行丢弃；最多 500 行） |
| 提示词 | `ai/imports/AiImportPrompts.java`（`system`、`tablePrompt`、`textPrompt`、`imagePrompt`） |
| Excel 读取 | `common/excel/ExcelTableReader.java`（`read` 按第一行非空行当表头；`mergedValues` 展开合并单元格） |
| CSV 读取 | `ai/imports/CsvTableReader.java` |
| 上传存储 | `ai/imports/UploadStorage.java` |
| AI 网关 | `ai/service/AiGatewayImpl.java`（`MAX_ATTEMPTS=3`，1 秒间隔；超时由策略层控制） |
| 超时配置 | 策略构造器 `@Value("${paiban.ai.timeout-seconds:30}")`（`OpenAiCompatibleStrategy` 等） |
| 审计 | `ai/imports/AiImportLog.java`（`ai_raw_response`、`confirmed_json`、`result_json`） |

## 课表落库相关

- `DraftLesson.assignmentId` **NOT NULL**（`scheduling/domain/DraftLesson.java`）。
- `Assignment` 唯一键 `(semester_id, teaching_class_id, course_id)`；仓库有
  `findBySemesterIdAndTeachingClassIdAndCourseId`。
- `TeachingClass` 与 `AdminClass` 一对一；`TeachingClassRepository.findByAdminClassId`。
- `AdminClassRepository.findBySemesterIdAndName`；`TeacherRepository`/`CourseRepository`/`RoomRepository` 可按名称查（实现时确认方法名）。
- `TimetableDraft` 状态 `DRAFT/PUBLISHED/ARCHIVED`；发布走 `timetable/service/TimetablePublishService`，它只读 `draft_lesson` 的 class/course/teacher/period/room。
- ADR-0008：发布后基线只读；ADR-0022：草稿教师以 `draft_lesson.teacher_id` 为准。

## 前端地图

| 关注点 | 位置 |
|---|---|
| AI 导入页 | `frontend/src/views/AiDataImportView.vue`（Excel/文本/图片三个 tab） |
| 错误提示 | 同文件 `messageOf`；`frontend/src/api/http.ts` 全局 `timeout: 30000` |
| 草稿网格 | `frontend/src/components/draft/DraftGrid.vue`（只读预览可复用） |
| 排课页 | `frontend/src/views/SchedulingView.vue` |
| 单测 | 与源码同目录 `*.spec.ts`；E2E 在 `frontend/e2e/` |

## 当前已知问题（六期要修）

1. `ExcelTableReader.findHeaderRow` 把标题行当表头，整表被裁成一列。
2. AI 超时 30 秒 × 3 次 ≈ 92 秒；前端 30 秒先断开，只显示「操作失败」。
3. `ImportTarget` 只有教师；课表无法作为导入目标。
4. 图片入口在模型 `visionCapable=false` 时仍可点。
