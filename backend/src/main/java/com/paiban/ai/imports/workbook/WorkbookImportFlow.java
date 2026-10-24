package com.paiban.ai.imports.workbook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.imports.AiImportLog;
import com.paiban.ai.imports.AiImportLogRepository;
import com.paiban.ai.imports.CsvTableReader;
import com.paiban.ai.imports.ImportDtos.CategoryResultDto;
import com.paiban.ai.imports.ImportDtos.ColumnDto;
import com.paiban.ai.imports.ImportDtos.CommitResultDto;
import com.paiban.ai.imports.ImportDtos.IssueDto;
import com.paiban.ai.imports.ImportDtos.MappingDto;
import com.paiban.ai.imports.ImportDtos.PreviewDto;
import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.ImportDtos.RowErrorDto;
import com.paiban.ai.imports.ImportDtos.SheetSummaryDto;
import com.paiban.ai.imports.ImportDtos.TimetableSummaryDto;
import com.paiban.ai.imports.ImportDtos.ToolTraceDto;
import com.paiban.ai.imports.ImportDtos.WorkbookSummaryDto;
import com.paiban.ai.imports.ImportMappingService;
import com.paiban.ai.imports.ImportPermissions;
import com.paiban.ai.imports.ImportMappingService.CourseDraft;
import com.paiban.ai.imports.ImportMappingService.EntityOutcome;
import com.paiban.ai.imports.ImportMappingService.TeacherDraft;
import com.paiban.ai.imports.ImportTarget;
import com.paiban.ai.imports.SlotText;
import com.paiban.ai.imports.TimetableImportService;
import com.paiban.ai.imports.UploadStorage;
import com.paiban.ai.imports.workbook.WorkbookPayload.CategorySummary;
import com.paiban.ai.imports.workbook.WorkbookPayload.SheetSummary;
import com.paiban.ai.imports.workbook.WorkbookPlan.SheetCategory;
import com.paiban.ai.imports.workbook.WorkbookPlan.SheetPlan;
import com.paiban.common.excel.ExcelTableReader;
import com.paiban.common.excel.ExcelTableReader.Grid;
import com.paiban.common.settings.SettingsService;
import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.AppUser;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.foundation.web.SemesterDtos.SemesterDto;
import com.paiban.identity.service.UserService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * The phase-11 workbook import: one upload, a model that browses the file with read-only
 * tools, a deterministic merge by timetable slot, and one confirmed commit that writes
 * teachers, courses, classes and a DRAFT timetable in dependency order.
 *
 * <p>What stays deterministic: skipping instruction sheets, finding headers, splitting
 * packed cells, merging slots and every database write. What the model decides: which sheet
 * is which kind of data, and which column feeds which field.
 */
@Component
public class WorkbookImportFlow {

    static final String TARGET = ImportTarget.WORKBOOK.code();
    private static final String TEACHER = "teacher";
    private static final String COURSE = "course";
    private static final String CLASS = "class";
    private static final String TIMETABLE = "timetable";
    private static final String SEMESTER_MISSING = "请先设置当前学期，再确认入库";
    private static final String PERIODS_MISSING =
            "当前学期还没有课节，请先生成默认作息（上午 4 + 下午 4，周一至周五）";
    private static final String UNCONFIRMED = "结构未确认，请人工核对";

    private final ImportPermissions permissions;
    private final UploadStorage storage;
    private final AiImportLogRepository logs;
    private final ObjectMapper objectMapper;
    private final WorkbookPlanner planner;
    private final WorkbookExtractor extractor;
    private final ImportMappingService mappings;
    private final TimetableImportService timetableImports;
    private final SemesterService semesters;
    private final PeriodRepository periods;
    private final UserService users;
    private final SettingsService settings;

    public WorkbookImportFlow(
            ImportPermissions permissions,
            UploadStorage storage,
            AiImportLogRepository logs,
            ObjectMapper objectMapper,
            WorkbookPlanner planner,
            WorkbookExtractor extractor,
            ImportMappingService mappings,
            TimetableImportService timetableImports,
            SemesterService semesters,
            PeriodRepository periods,
            UserService users,
            SettingsService settings) {
        this.permissions = permissions;
        this.storage = storage;
        this.logs = logs;
        this.objectMapper = objectMapper;
        this.planner = planner;
        this.extractor = extractor;
        this.mappings = mappings;
        this.timetableImports = timetableImports;
        this.semesters = semesters;
        this.periods = periods;
        this.users = users;
        this.settings = settings;
    }

    // ------------------------------------------------------------------- entry

    public PreviewDto start(MultipartFile file, String username) {
        permissions.requireConfigured();
        UploadStorage.StoredFile stored = storage.storeSpreadsheet(file);
        AiImportLog log = newLog(stored, username);
        try {
            return run(log);
        } catch (RuntimeException ex) {
            markFailed(log, message(ex));
            throw ex;
        }
    }

    /** Re-reads the stored file with the same pipeline; the previous preview is replaced. */
    public PreviewDto reparse(AiImportLog log) {
        if (AiImportLog.COMMITTED.equals(log.getStatus())) {
            throw ApiException.conflict("已入库的导入不能再重新解析");
        }
        if (log.getFilePath() == null) {
            throw ApiException.badRequest("这次导入没有原始文件，无法重新解析");
        }
        try {
            return run(log);
        } catch (RuntimeException ex) {
            markFailed(log, message(ex));
            throw ex;
        }
    }

    private PreviewDto run(AiImportLog log) {
        List<Grid> grids = readWorkbook(storage.read(log.getFilePath()), log.getFilePath());
        List<ProfiledSheet> profiles = SheetProfiler.profile(grids);
        WorkbookPlanner.Outcome outcome = planner.plan(profiles);
        WorkbookExtractor.Extraction extraction = extractor.extract(profiles, outcome.plan());
        WorkbookPayload payload = payload(profiles, outcome.plan(), extraction);

        log.setPreviewJson(writeJson(payload));
        log.setConfirmedJson(null);
        log.setResultJson(null);
        log.setToolTraceJson(writeJson(trace(outcome.trace())));
        log.setAiRawResponse(writeJson(extraction.rawAnswers()));
        log.setAiExplanation(payload.explanation());
        log.setRowCount(payload.rows().size());
        log.setImportedCount(0);
        log.setStatus(AiImportLog.PREVIEW);
        log.setUpdatedAt(Instant.now());
        logs.save(log);
        return preview(log);
    }

    // ----------------------------------------------------------------- preview

    public PreviewDto preview(AiImportLog log) {
        WorkbookPayload payload = payload(log);
        List<IssueDto> issues = issues(payload);
        return new PreviewDto(
                log.getId(),
                TARGET,
                null,
                columns(),
                payload.rows(),
                mappings(payload),
                payload.explanation(),
                settings.aiImportConfidenceThreshold(),
                log.getStatus(),
                issues,
                summary(payload),
                null,
                List.of(),
                new WorkbookSummaryDto(
                        payload.structureConfirmed(),
                        payload.sheets().stream().map(this::sheetDto).toList(),
                        categoryDtos(payload.committed())),
                payload.warnings(),
                trace(log.getToolTraceJson()));
    }

    private List<ColumnDto> columns() {
        return ImportTarget.WORKBOOK.fields().stream()
                .map(field -> new ColumnDto(field.field(), field.label(), field.required()))
                .toList();
    }

    private SheetSummaryDto sheetDto(SheetSummary sheet) {
        return new SheetSummaryDto(
                sheet.sheet(), sheet.category(), sheet.columns(), sheet.recognizedRows(),
                sheet.newRows(), sheet.skippedReason(), sheet.note());
    }

    /** The column decisions in the same shape the single-target preview shows. */
    private List<MappingDto> mappings(WorkbookPayload payload) {
        List<MappingDto> mappings = new ArrayList<>();
        for (SheetSummary sheet : payload.sheets()) {
            for (Map.Entry<String, String> column : sheet.columns().entrySet()) {
                mappings.add(new MappingDto(
                        column.getValue(), column.getKey(), "工作表「" + sheet.sheet() + "」"));
            }
        }
        return List.copyOf(mappings);
    }

    private TimetableSummaryDto summary(WorkbookPayload payload) {
        Map<String, Boolean> groups = new LinkedHashMap<>();
        for (RowDto row : payload.rows()) {
            String className = value(row, "className");
            String courseName = value(row, "courseName");
            if (!className.isBlank() && !courseName.isBlank()) {
                groups.put(className + "\u0001" + courseName, Boolean.TRUE);
            }
        }
        return new TimetableSummaryDto(
                payload.rows().size(), 0, groups.size(), groups.size(), 0);
    }

    /**
     * Page-level blockers only: a missing semester, a semester without periods, and the row
     * errors the merge refused. Free periods and dropped duplicates are warnings, not blocks.
     * A committed session keeps whatever blocker metadata it had; nothing can be done then.
     */
    private List<IssueDto> issues(WorkbookPayload payload) {
        List<IssueDto> issues = new ArrayList<>();
        Long semesterId = currentSemesterId();
        // A teacher- or course-only workbook commits without a semester, so it is not blocked.
        boolean needsSemester = !payload.rows().isEmpty() || !payload.classes().isEmpty();
        if (needsSemester && semesterId == null) {
            issues.add(new IssueDto(0, "semester", "", List.of(), true, SEMESTER_MISSING, null, List.of()));
        } else if (needsSemester && periods.countBySemesterId(semesterId) == 0) {
            issues.add(new IssueDto(0, "periods", "", List.of(), true, PERIODS_MISSING, null, List.of()));
        }
        for (RowErrorDto error : payload.errors()) {
            issues.add(new IssueDto(
                    0, error.field(), "", error.candidates(), true, error.message(), null, List.of()));
        }
        return List.copyOf(issues);
    }

    // ------------------------------------------------------------------ commit

    /**
     * Writes the confirmed preview in dependency order: teachers, courses, classes, then the
     * timetable. Each category is its own transaction, so a failing timetable does not undo
     * the teachers; the session stays editable and a second click retries only what failed.
     */
    public CommitResultDto commit(AiImportLog log, String username) {
        if (AiImportLog.COMMITTED.equals(log.getStatus())) {
            throw ApiException.conflict("该导入已入库，不能再修改");
        }
        if (AiImportLog.PARSING.equals(log.getStatus()) || AiImportLog.PARTIAL.equals(log.getStatus())) {
            throw ApiException.conflict("这次导入还没有完整结果，请先处理失败批次");
        }
        WorkbookPayload payload = payload(log);
        if (payload.rows().isEmpty() && payload.teachers().isEmpty() && payload.courses().isEmpty()) {
            throw ApiException.badRequest("没有可入库的数据");
        }
        // A teacher- or course-only workbook needs neither a semester nor periods; only
        // rows/classes make the semester part of the write.
        Long semesterId = currentSemesterId();
        boolean needsSemester = !payload.rows().isEmpty() || !payload.classes().isEmpty();
        if (needsSemester && semesterId == null) {
            throw ApiException.badRequest(SEMESTER_MISSING);
        }
        if (!payload.rows().isEmpty() && periods.countBySemesterId(semesterId) == 0) {
            throw ApiException.badRequest(PERIODS_MISSING);
        }
        if (!payload.rows().isEmpty()) {
            requireTimetableEdit(username);
        }

        Map<String, CategorySummary> previous = payload.committed();
        Map<String, CategorySummary> committed = new LinkedHashMap<>();
        committed.put(TEACHER, write(() -> mappings.ensureTeachers(payload.teachers())));
        committed.put(COURSE, write(() -> mappings.ensureCourses(payload.courses())));
        if (!payload.classes().isEmpty()) {
            committed.put(CLASS, write(() -> mappings.ensureClasses(payload.classes())));
        }

        List<RowErrorDto> rowErrors = new ArrayList<>();
        Long draftId = null;
        int lessons = 0;
        if (!payload.rows().isEmpty()) {
            try {
                TimetableImportService.Result result =
                        timetableImports.importRows(payload.rows(), semesterId, username);
                if (result.errors().isEmpty()) {
                    draftId = result.draftId();
                    lessons = result.imported();
                    committed.put(TIMETABLE, new CategorySummary(lessons, 0, 0, List.of()));
                } else {
                    rowErrors.addAll(result.errors());
                    committed.put(TIMETABLE, new CategorySummary(
                            0, 0, rowsSkipped(result.errors()),
                            result.errors().stream().map(RowErrorDto::message).toList()));
                }
            } catch (RuntimeException ex) {
                committed.put(TIMETABLE, new CategorySummary(0, 0, 0, List.of(message(ex))));
            }
        }

        // A retry re-runs only what is missing, so its own counts are 0 for work already
        // done. The summary a user reads is the session's total, not the last attempt's.
        committed.replaceAll((category, current) ->
                mergeCounts(previous.get(category), current));
        WorkbookPayload saved = new WorkbookPayload(
                payload.rows(), payload.sheets(), payload.structureConfirmed(),
                payload.explanation(), payload.warnings(), payload.errors(),
                payload.teachers(), payload.courses(), payload.classes(), committed);
        log.setPreviewJson(writeJson(saved));
        log.setAiExplanation(payload.explanation());
        log.setRowCount(payload.rows().size());
        boolean allWritten = committed.values().stream().allMatch(category -> category.errors().isEmpty());
        // The headline number is what the user asked for: the lessons in the new draft.
        // A workbook that carries only people/courses reports the entity writes instead.
        int total = lessons > 0
                ? lessons
                : committed.values().stream()
                        .mapToInt(category -> category.created() + category.updated())
                        .sum();
        log.setImportedCount(total);
        log.setResultJson(writeJson(Map.of(
                "categories", committed,
                "draftId", draftId == null ? "" : draftId)));
        log.setStatus(allWritten ? AiImportLog.COMMITTED : AiImportLog.PREVIEW);
        log.setUpdatedAt(Instant.now());
        logs.save(log);
        return new CommitResultDto(
                total, List.copyOf(rowErrors), draftId, payload.warnings(), categoryDtos(committed));
    }

    private List<CategoryResultDto> categoryDtos(Map<String, CategorySummary> committed) {
        return committed.entrySet().stream()
                .map(entry -> new CategoryResultDto(
                        entry.getKey(), entry.getValue().created(), entry.getValue().updated(),
                        entry.getValue().skipped(), entry.getValue().errors()))
                .toList();
    }

    private int rowsSkipped(List<RowErrorDto> errors) {
        return (int) errors.stream().map(RowErrorDto::row).distinct().count();
    }

    private CategorySummary write(Supplier<List<EntityOutcome>> action) {
        try {
            List<EntityOutcome> outcomes = action.get();
            int created = (int) outcomes.stream().filter(EntityOutcome::created).count();
            int updated = (int) outcomes.stream().filter(EntityOutcome::updated).count();
            int skipped = (int) outcomes.stream().filter(outcome -> outcome.error() != null).count();
            List<String> errors = outcomes.stream()
                    .filter(outcome -> outcome.error() != null)
                    .map(outcome -> outcome.error())
                    .toList();
            return new CategorySummary(created, updated, skipped, errors);
        } catch (RuntimeException ex) {
            return new CategorySummary(0, 0, 0, List.of(message(ex)));
        }
    }

    private CategorySummary mergeCounts(CategorySummary previous, CategorySummary current) {
        if (previous == null) {
            return current;
        }
        return new CategorySummary(
                previous.created() + current.created(),
                previous.updated() + current.updated(),
                current.skipped(),
                current.errors());
    }

    private void requireTimetableEdit(String username) {
        permissions.requireTimetableEdit(username);
    }

    // ----------------------------------------------------------------- payload

    private WorkbookPayload payload(
            List<ProfiledSheet> profiles,
            WorkbookPlan plan,
            WorkbookExtractor.Extraction extraction) {
        List<SlotMerger.Kept> keptInput = new ArrayList<>();
        Map<String, Integer> sheetRows = new LinkedHashMap<>();
        Map<String, List<String>> sheetTeachers = new LinkedHashMap<>();
        Map<String, List<String>> sheetCourses = new LinkedHashMap<>();
        List<TeacherDraft> teachers = new ArrayList<>();
        List<CourseDraft> courses = new ArrayList<>();
        List<String> sheetWarnings = new ArrayList<>(extraction.warnings());
        for (WorkbookExtractor.SheetExtraction sheet : extraction.sheets()) {
            sheetRows.put(sheet.sheet(), sheet.rows().size());
            for (int i = 0; i < sheet.rows().size(); i++) {
                RowDto row = sheet.rows().get(i);
                if (sheet.category() == SheetCategory.TIMETABLE) {
                    keptInput.add(new SlotMerger.Kept(sheet.sheet(), i + 1, row));
                } else if (sheet.category() == SheetCategory.TEACHER) {
                    String name = value(row, "name");
                    teachers.add(new TeacherDraft(
                            name, value(row, "employeeNo"), value(row, "phone"),
                            value(row, "email"), value(row, "subject")));
                    if (!name.isBlank()) {
                        sheetTeachers.computeIfAbsent(sheet.sheet(), key -> new ArrayList<>()).add(name);
                    }
                } else if (sheet.category() == SheetCategory.COURSE) {
                    String name = value(row, "courseName").isEmpty()
                            ? value(row, "name")
                            : value(row, "courseName");
                    courses.add(new CourseDraft(name, value(row, "subject")));
                    if (!name.isBlank()) {
                        sheetCourses.computeIfAbsent(sheet.sheet(), key -> new ArrayList<>()).add(name);
                    }
                }
            }
        }
        SlotMerger.Merge merge = SlotMerger.merge(keptInput);
        List<RowDto> rows = merge.kept().stream().map(SlotMerger.Kept::row).toList();

        // Teachers and courses also arrive through the timetable rows; the named sheets
        // supply the extra fields, the timetable only the names.
        Map<String, String> teacherNames = new LinkedHashMap<>();
        Map<String, String> courseNames = new LinkedHashMap<>();
        Map<String, String> classNames = new LinkedHashMap<>();
        for (RowDto row : rows) {
            String teacher = value(row, "teacherName");
            if (!teacher.isBlank()) {
                teacherNames.putIfAbsent(SlotText.normalizeName(teacher), teacher);
            }
            String course = value(row, "courseName");
            if (!course.isBlank()) {
                courseNames.putIfAbsent(SlotText.normalizeName(course), course);
            }
            String className = value(row, "className");
            if (!className.isBlank()) {
                classNames.putIfAbsent(SlotText.normalizeName(className), className);
            }
        }
        for (String name : teacherNames.values()) {
            teachers.add(new TeacherDraft(name, null, null, null, null));
        }
        for (String name : courseNames.values()) {
            courses.add(new CourseDraft(name, null));
        }

        List<String> warnings = new ArrayList<>(sheetWarnings);
        if (!plan.confirmed()) {
            warnings.add(UNCONFIRMED);
        }
        if (merge.freeSlots() > 0) {
            warnings.add("空课位 " + merge.freeSlots() + " 个（没有课程与教师），已跳过");
        }
        if (merge.droppedIncomplete() > 0) {
            warnings.add("丢弃 " + merge.droppedIncomplete() + " 行（信息不全）");
        }
        if (merge.duplicates() > 0) {
            warnings.add("合并 " + merge.duplicates() + " 行（重复槽位）");
        }
        List<SheetSummary> sheets = sheets(profiles, plan, merge, sheetRows, sheetTeachers, sheetCourses);
        String explanation = (plan.explanation() == null || plan.explanation().isBlank()
                ? "已读取工作簿"
                : plan.explanation())
                + "（" + sheets.stream().filter(sheet -> sheet.skippedReason() == null).count()
                + " 张有数据的工作表，" + rows.size() + " 节课）";
        return new WorkbookPayload(
                rows, sheets, plan.confirmed(), explanation, warnings, merge.errors(),
                teachers, courses, List.copyOf(classNames.values()), Map.of());
    }

    /** One line per sheet, in the file's own order; skipped sheets keep their reason. */
    private List<SheetSummary> sheets(
            List<ProfiledSheet> profiles,
            WorkbookPlan plan,
            SlotMerger.Merge merge,
            Map<String, Integer> extractedRows,
            Map<String, List<String>> sheetTeachers,
            Map<String, List<String>> sheetCourses) {
        Map<String, SheetPlan> planned = new LinkedHashMap<>();
        for (SheetPlan sheetPlan : plan.sheets()) {
            planned.put(sheetPlan.sheet(), sheetPlan);
        }
        List<SheetSummary> sheets = new ArrayList<>();
        for (ProfiledSheet profile : profiles) {
            if (profile.skipped()) {
                sheets.add(new SheetSummary(
                        profile.name(), SheetCategory.EXPLANATION.code(), Map.of(),
                        0, 0, profile.skippedReason(), ""));
                continue;
            }
            SheetPlan sheetPlan = planned.get(profile.name());
            SheetCategory category = sheetPlan == null ? SheetCategory.UNKNOWN : sheetPlan.category();
            Map<String, String> columns = sheetPlan == null ? Map.of() : sheetPlan.columns();
            String note = sheetPlan == null ? "" : sheetPlan.note();
            int recognized = extractedRows.getOrDefault(profile.name(), 0);
            int fresh;
            String skipReason = null;
            if (category == SheetCategory.TIMETABLE) {
                SlotMerger.SheetStats stats = merge.perSheet().get(profile.name());
                recognized = stats == null ? 0 : stats.recognized();
                fresh = stats == null ? 0 : stats.kept();
                skipReason = timetableSkipReason(stats);
            } else if (category == SheetCategory.TEACHER) {
                fresh = missingNames(sheetTeachers.getOrDefault(profile.name(), List.of()), "teacher");
            } else if (category == SheetCategory.COURSE) {
                fresh = missingNames(sheetCourses.getOrDefault(profile.name(), List.of()), "course");
            } else {
                recognized = profile.dataRows().size();
                fresh = 0;
                skipReason = category == SheetCategory.EXPLANATION
                        ? "判断为说明类，已跳过"
                        : "未识别的表，已跳过";
            }
            sheets.add(new SheetSummary(
                    profile.name(), category.code(), columns, recognized, fresh, skipReason, note));
        }
        return List.copyOf(sheets);
    }

    private String timetableSkipReason(SlotMerger.SheetStats stats) {
        if (stats == null) {
            return null;
        }
        List<String> notes = new ArrayList<>();
        if (stats.freeSlots() > 0) {
            notes.add("跳过空课位 " + stats.freeSlots() + " 个");
        }
        if (stats.droppedIncomplete() > 0) {
            notes.add("丢弃信息不全 " + stats.droppedIncomplete() + " 行");
        }
        if (stats.duplicates() > 0) {
            notes.add("合并重复 " + stats.duplicates() + " 行");
        }
        return notes.isEmpty() ? null : String.join("；", notes);
    }

    /** How many of these names the system does not have yet (the sheet's "new" count). */
    private int missingNames(List<String> names, String type) {
        try {
            return mappings.lookupNames(type, names.stream().filter(Objects::nonNull).toList())
                    .missing().size();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- plumbing

    private List<Grid> readWorkbook(byte[] bytes, String path) {
        String name = path == null ? "" : path.toLowerCase();
        try {
            if (name.endsWith(".csv")) {
                return List.of(new Grid(fileName(path), CsvTableReader.readGrid(bytes)));
            }
            return ExcelTableReader.readAllGrids(new ByteArrayInputStream(bytes));
        } catch (Exception ex) {
            throw ApiException.badRequest("无法读取表格文件: " + ex.getMessage());
        }
    }

    private AiImportLog newLog(UploadStorage.StoredFile stored, String username) {
        AiImportLog log = new AiImportLog();
        log.setImportType("excel");
        log.setTargetType(TARGET);
        log.setUsername(username);
        AppUser user = users.getByUsername(username);
        log.setUserId(user == null ? null : user.getId());
        log.setFileName(stored.fileName());
        log.setFileSize(stored.size());
        log.setFilePath(stored.path());
        log.setStatus(AiImportLog.PREVIEW);
        log.setRowCount(0);
        log.setImportedCount(0);
        log.setCreatedAt(Instant.now());
        log.setUpdatedAt(Instant.now());
        return logs.save(log);
    }

    private WorkbookPayload payload(AiImportLog log) {
        String json = log.getPreviewJson();
        if (json == null || json.isBlank()) {
            return emptyPayload();
        }
        try {
            return objectMapper.readValue(json, WorkbookPayload.class);
        } catch (Exception ex) {
            return emptyPayload();
        }
    }

    private WorkbookPayload emptyPayload() {
        return new WorkbookPayload(
                List.of(), List.of(), false, "", List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of());
    }

    private List<ToolTraceDto> trace(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ToolTraceDto>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<ToolTraceDto> trace(List<WorkbookPlanner.Trace> rounds) {
        return rounds.stream()
                .map(round -> new ToolTraceDto(
                        round.round(), round.tool(), round.args(), round.reply(), round.result()))
                .toList();
    }

    private Long currentSemesterId() {
        return semesters.current().map(SemesterDto::id).orElse(null);
    }

    private void markFailed(AiImportLog log, String message) {
        AiImportLog current = logs.findById(log.getId()).orElse(null);
        if (current == null) {
            return;
        }
        current.setStatus(AiImportLog.FAILED);
        current.setAiExplanation(message);
        current.setUpdatedAt(Instant.now());
        logs.save(current);
    }

    private String message(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    private String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("保存导入数据失败", ex);
        }
    }

    private String value(RowDto row, String field) {
        if (row == null || row.values() == null) {
            return "";
        }
        String value = row.values().get(field);
        return value == null ? "" : value.trim();
    }
}
