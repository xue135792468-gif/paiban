package com.paiban.imports.manual;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.ImportDtos.RowErrorDto;
import com.paiban.ai.imports.ImportDtos.TeacherWarningDto;
import com.paiban.ai.imports.SlotText;
import com.paiban.ai.imports.SuspiciousTeacherNames;
import com.paiban.ai.imports.TimetableImportService;
import com.paiban.ai.imports.workbook.HeaderDetection;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.imports.ImportDtos.CommitOptions;
import com.paiban.imports.ImportDtos.CommitResultDto;
import com.paiban.imports.ImportDtos.InspectDto;
import com.paiban.imports.ImportDtos.ManualMapping;
import com.paiban.imports.ImportDtos.ManualPreviewDto;
import com.paiban.imports.ImportDtos.PreviewRowDto;
import com.paiban.imports.ImportDtos.RecipeMatchDto;
import com.paiban.imports.ImportDtos.SheetInfoDto;
import com.paiban.imports.ImportDtos.SuggestedRuleDto;
import com.paiban.imports.recipe.ImportFingerprint;
import com.paiban.imports.recipe.ImportRecipeService;
import com.paiban.imports.manual.ManualSheetParser.NormalizedRow;
import com.paiban.imports.manual.ManualSheetParser.Parsed;
import com.paiban.imports.manual.SourceSheets.Sheet;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.service.ConstraintConfigService;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigRequest;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintEntryDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintEntryRequest;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintStateDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The manual import path: read a spreadsheet, apply the user's column mapping, show
 * what would be written, and commit it as a draft. No AI is involved anywhere: the
 * only decisions are the mapping and the optional weekly no-class rule, both of which
 * the user confirmed in the preview.
 */
@Service
public class ManualImportService {

    private static final String[] DAY_LABELS =
            {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final int PREVIEW_ROWS = 50;
    private static final Pattern NUMERIC = Pattern.compile("\\d+");
    private static final String CLASS_UNAVAILABLE = "CLASS_UNAVAILABLE";

    private final SemesterService semesterService;
    private final AdminClassRepository adminClasses;
    private final TeacherRepository teachers;
    private final TimetableDraftRepository drafts;
    private final TimetableImportService timetableImports;
    private final ConstraintConfigService constraintConfigs;
    private final ImportRecipeService recipes;
    private final ManualSheetParser parser;
    private final ObjectMapper objectMapper;

    public ManualImportService(
            SemesterService semesterService,
            AdminClassRepository adminClasses,
            TeacherRepository teachers,
            TimetableDraftRepository drafts,
            TimetableImportService timetableImports,
            ConstraintConfigService constraintConfigs,
            ImportRecipeService recipes,
            ManualSheetParser parser,
            ObjectMapper objectMapper) {
        this.semesterService = semesterService;
        this.adminClasses = adminClasses;
        this.teachers = teachers;
        this.drafts = drafts;
        this.timetableImports = timetableImports;
        this.constraintConfigs = constraintConfigs;
        this.recipes = recipes;
        this.parser = parser;
        this.objectMapper = objectMapper;
    }

    /** Sheet list + header candidates + a suggested mapping, so the page can offer selects. */
    public InspectDto inspect(org.springframework.web.multipart.MultipartFile file) {
        List<Sheet> sheets = SourceSheets.read(file);
        List<SheetInfoDto> infos = new ArrayList<>();
        for (Sheet sheet : sheets) {
            if (!sheet.empty()) {
                infos.add(infoOf(sheet));
            }
        }
        if (infos.isEmpty()) {
            throw ApiException.badRequest("文件里没有可读取的数据");
        }
        return new InspectDto(List.copyOf(infos));
    }

    /** Parses with the chosen mapping (or the matched recipe) and reports the totals. */
    @Transactional
    public ManualPreviewDto preview(
            org.springframework.web.multipart.MultipartFile file, String mappingJson, Long semesterId) {
        List<Sheet> sheets = SourceSheets.read(file);
        ManualMapping requested = readMapping(mappingJson);
        Sheet sheet = SourceSheets.select(sheets, requested.sheetName());
        RecipeMatchDto match = recipes.match(sheet, requested);
        ManualMapping applied = appliedMapping(requested, match, sheet);
        return previewWithMapping(sheet, applied, match, semesterId);
    }

    /**
     * Runs one already-resolved mapping through the same parse/report pipeline as a
     * manual preview. Ticket 07's AI suggestion calls it to prove a proposal really
     * produces rows before offering it; it never consults or marks recipes, so a
     * suggestion cannot silently become a saved recipe.
     */
    @Transactional(readOnly = true)
    public ManualPreviewDto previewWithMapping(
            Sheet sheet, ManualMapping applied, RecipeMatchDto match, Long semesterId) {
        Parsed parsed = parser.parse(sheet, applied, knownClassNames(resolveSemester(semesterId)));
        List<PreviewRowDto> rows = previewRows(parsed.validRows());
        return new ManualPreviewDto(
                parsed.slotCount(),
                parsed.lessonCount(),
                parsed.emptySlotCount(),
                parsed.teacherNames().size(),
                parsed.courseNames().size(),
                rows,
                parsed.validRows().size() > PREVIEW_ROWS,
                teacherWarnings(parsed),
                suggestedRule(parsed.emptyRows()),
                match == null ? RecipeMatchDto.none() : match,
                List.copyOf(parsed.warnings()),
                ImportFingerprint.of(sheet, applied).toMap(),
                applied.keyColumn() != null
                        ? ImportFingerprint.LAYOUT_LONG : ImportFingerprint.LAYOUT_GRID);
    }

    /**
     * Writes the mapped rows as a draft. Empty slots are "no lesson", not an error; a
     * placeholder teacher is created only after the user had the chance to map it, and
     * the warning says so. The optional weekly rule is written in the same transaction.
     */
    @Transactional
    public CommitResultDto commit(
            org.springframework.web.multipart.MultipartFile file,
            String mappingJson,
            String optionsJson,
            String username) {
        CommitOptions options = readOptions(optionsJson);
        Long semesterId = resolveSemester(options.semesterId());
        List<Sheet> sheets = SourceSheets.read(file);
        ManualMapping requested = readMapping(mappingJson);
        Sheet sheet = SourceSheets.select(sheets, requested.sheetName());
        RecipeMatchDto match = recipes.match(sheet, requested);
        ManualMapping applied = appliedMapping(requested, match, sheet);
        Parsed parsed = parser.parse(sheet, applied, knownClassNames(semesterId));

        List<String> warnings = new ArrayList<>(parsed.warnings());
        Map<String, String> teacherMappings = options.teacherNameMappings() == null
                ? Map.of() : options.teacherNameMappings();
        List<RowDto> rows = importRows(parsed.validRows(), teacherMappings, warnings);
        TimetableImportService.Result result =
                timetableImports.importRows(rows, semesterId, username);
        Long appliedRecipeId = "NONE".equals(match.kind()) ? null : match.recipeId();
        SuggestedRuleDto rule = suggestedRule(parsed.emptyRows());

        if (!result.errors().isEmpty()) {
            return new CommitResultDto(
                    null, 0, parsed.emptySlotCount(), result.errors(),
                    List.copyOf(warnings), appliedRecipeId, null);
        }
        if (StringUtils.hasText(options.draftName())) {
            TimetableDraft draft = drafts.findById(result.draftId())
                    .orElseThrow(() -> new IllegalStateException("导入草稿不见了"));
            draft.setName(options.draftName().trim());
            drafts.save(draft);
        }
        if (options.appliesSuggestedRule() && rule != null) {
            applyWeeklyRule(semesterId, rule);
        }
        warnings.addAll(result.warnings());
        return new CommitResultDto(
                result.draftId(), result.imported(), parsed.emptySlotCount(),
                List.of(), List.copyOf(warnings), appliedRecipeId,
                options.appliesSuggestedRule() ? rule : null);
    }

    // ---------------------------------------------------------------- parsing

    private Set<String> knownClassNames(Long semesterId) {
        if (semesterId == null) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (AdminClass adminClass : adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId)) {
            names.add(SlotText.normalizeName(adminClass.getName()));
        }
        return names;
    }

    private List<PreviewRowDto> previewRows(List<NormalizedRow> rows) {
        List<PreviewRowDto> preview = new ArrayList<>();
        for (NormalizedRow row : rows.subList(0, Math.min(PREVIEW_ROWS, rows.size()))) {
            preview.add(new PreviewRowDto(
                    row.rowNumber(), row.className(), row.dayOfWeek(), row.periodNo(),
                    row.courseName(), row.teacherName(), row.empty()));
        }
        return List.copyOf(preview);
    }

    private List<TeacherWarningDto> teacherWarnings(Parsed parsed) {
        List<SuspiciousTeacherNames.Occurrence> occurrences = new ArrayList<>();
        for (NormalizedRow row : parsed.validRows()) {
            if (StringUtils.hasText(row.teacherName())) {
                occurrences.add(new SuspiciousTeacherNames.Occurrence(
                        row.teacherName(), row.rowNumber()));
            }
        }
        return SuspiciousTeacherNames.inspect(occurrences, parsed.courseNames());
    }

    /** All empty slots on one weekday+period justify one whole-school weekly no-class rule. */
    private static SuggestedRuleDto suggestedRule(List<NormalizedRow> emptyRows) {
        if (emptyRows.isEmpty()) {
            return null;
        }
        Integer day = emptyRows.get(0).dayOfWeek();
        Integer period = emptyRows.get(0).periodNo();
        for (NormalizedRow row : emptyRows) {
            if (!Objects.equals(day, row.dayOfWeek()) || !Objects.equals(period, row.periodNo())) {
                return null;
            }
        }
        String dayLabel = day >= 1 && day <= 7 ? DAY_LABELS[day - 1] : ("星期" + day);
        return new SuggestedRuleDto(
                CLASS_UNAVAILABLE, day, period,
                "全校" + dayLabel + "第 " + period + " 节不上课");
    }

    // ---------------------------------------------------------------- commit helpers

    private List<RowDto> importRows(
            List<NormalizedRow> validRows, Map<String, String> teacherMappings,
            List<String> warnings) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : teacherMappings.entrySet()) {
            String source = entry.getKey();
            String target = entry.getValue();
            if (!StringUtils.hasText(source) || !StringUtils.hasText(target)) {
                continue;
            }
            resolved.put(source, resolveTeacherName(target.trim()));
        }
        Set<String> existing = new LinkedHashSet<>();
        for (Teacher teacher : teachers.findAllByDeletedAtIsNullOrderByNameAsc()) {
            existing.add(SlotText.normalizeName(teacher.getName()));
        }
        Set<String> created = new LinkedHashSet<>();
        List<RowDto> rows = new ArrayList<>();
        for (NormalizedRow row : validRows) {
            if (row.empty()) {
                continue;
            }
            String teacherName = resolved.getOrDefault(row.teacherName(), row.teacherName());
            if (!StringUtils.hasText(teacherName)) {
                teacherName = "";
            }
            if (StringUtils.hasText(teacherName)
                    && !existing.contains(SlotText.normalizeName(teacherName))) {
                Teacher teacher = new Teacher();
                teacher.setName(teacherName);
                teachers.save(teacher);
                existing.add(SlotText.normalizeName(teacherName));
                created.add(teacherName);
            }
            Map<String, String> values = new LinkedHashMap<>();
            values.put("className", row.className());
            values.put("dayOfWeek", row.dayText());
            values.put("periodNo", row.periodText());
            values.put("courseName", row.courseName());
            values.put("teacherName", teacherName);
            rows.add(new RowDto(values, Map.of(), List.of()));
        }
        for (String name : created) {
            warnings.add("已按占位名创建教师：" + name);
        }
        return rows;
    }

    private String resolveTeacherName(String target) {
        if (NUMERIC.matcher(target).matches()) {
            long id = Long.parseLong(target);
            return teachers.findById(id)
                    .orElseThrow(() -> ApiException.badRequest("映射到的教师不存在：#" + target))
                    .getName();
        }
        return target;
    }

    /**
     * Adds one whole-school {@code CLASS_UNAVAILABLE} entry for the suggested slot.
     * Re-committing the same file must not duplicate it, so an existing whole-school
     * entry for that weekday+period is treated as already applied.
     */
    private void applyWeeklyRule(Long semesterId, SuggestedRuleDto rule) {
        ConstraintStateDto state = constraintConfigs.state(semesterId);
        List<ConstraintConfigRequest> requests = new ArrayList<>();
        for (ConstraintConfigDto config : state.configs()) {
            requests.add(toRequest(config));
            if (CLASS_UNAVAILABLE.equals(config.type()) && containsSlot(config, rule)) {
                return;
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dayOfWeek", rule.dayOfWeek());
        params.put("periodNo", rule.periodNo());
        ConstraintEntryRequest entry =
                new ConstraintEntryRequest(null, params, true, null, null, null);
        int index = indexOfType(requests, CLASS_UNAVAILABLE);
        if (index < 0) {
            requests.add(new ConstraintConfigRequest(
                    CLASS_UNAVAILABLE, null, null, true, true, List.of(entry)));
        } else {
            ConstraintConfigRequest existing = requests.get(index);
            List<ConstraintEntryRequest> entries = new ArrayList<>(
                    existing.entries() == null ? List.of() : existing.entries());
            entries.add(entry);
            requests.set(index, new ConstraintConfigRequest(
                    CLASS_UNAVAILABLE, null, existing.weight(), existing.hard(),
                    existing.enabled(), entries));
        }
        constraintConfigs.save(semesterId, requests, state.version());
    }

    private boolean containsSlot(ConstraintConfigDto config, SuggestedRuleDto rule) {
        for (ConstraintEntryDto entry : config.entries()) {
            Map<String, Object> params = entry.params();
            if (params.get("classId") == null
                    && Objects.equals(intValue(params.get("dayOfWeek")), rule.dayOfWeek())
                    && Objects.equals(intValue(params.get("periodNo")), rule.periodNo())) {
                return true;
            }
        }
        return false;
    }

    private ConstraintConfigRequest toRequest(ConstraintConfigDto config) {
        boolean entryBacked = isEntryBacked(config.type());
        List<ConstraintEntryRequest> entries = entryBacked || !config.entries().isEmpty()
                ? config.entries().stream()
                        .map(entry -> new ConstraintEntryRequest(
                                entry.id(), entry.params(), entry.enabled(),
                                entry.hard(), entry.weight(), entry.groupKey()))
                        .toList()
                : null;
        return new ConstraintConfigRequest(
                config.type(), entries == null ? config.params() : null,
                config.weight(), config.hard(), config.enabled(), entries);
    }

    private boolean isEntryBacked(String type) {
        try {
            return com.paiban.scheduling.domain.ConstraintType.valueOf(type).isEntryBacked();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private int indexOfType(List<ConstraintConfigRequest> requests, String type) {
        for (int index = 0; index < requests.size(); index++) {
            if (type.equals(requests.get(index).type())) {
                return index;
            }
        }
        return -1;
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------- json helpers

    private ManualMapping appliedMapping(
            ManualMapping requested, RecipeMatchDto match, Sheet sheet) {
        if (requested.hasLayout()) {
            return requested;
        }
        if (match.mapping() != null) {
            return match.mapping();
        }
        return ManualSuggestion.suggest(sheet);
    }

    private ManualMapping readMapping(String json) {
        if (!StringUtils.hasText(json)) {
            return new ManualMapping(
                    null, null, null, null, null, null, null, null, null, null, true);
        }
        try {
            return objectMapper.readValue(json, ManualMapping.class);
        } catch (Exception ex) {
            throw ApiException.badRequest("列映射格式不正确：" + ex.getMessage());
        }
    }

    private CommitOptions readOptions(String json) {
        if (!StringUtils.hasText(json)) {
            return new CommitOptions(null, null, Map.of(), false);
        }
        try {
            return objectMapper.readValue(json, CommitOptions.class);
        } catch (Exception ex) {
            throw ApiException.badRequest("入库选项格式不正确：" + ex.getMessage());
        }
    }

    private Long resolveSemester(Long semesterId) {
        if (semesterId != null) {
            return semesterService.require(semesterId).getId();
        }
        return semesterService.current()
                .map(dto -> dto.id())
                .orElse(null);
    }

    private SheetInfoDto infoOf(Sheet sheet) {
        HeaderDetection.Header header = HeaderDetection.detect(sheet.rows());
        int start = Math.min(header.dataStart(), sheet.rows().size());
        List<List<String>> sample = new ArrayList<>();
        for (int index = start; index < Math.min(start + 5, sheet.rows().size()); index++) {
            sample.add(sheet.row(index));
        }
        return new SheetInfoDto(
                sheet.name(),
                sheet.rows().size(),
                sheet.columnCount(),
                header.rowIndex(),
                header.headerless(),
                header.labels(),
                List.copyOf(sample),
                ManualSuggestion.suggest(sheet));
    }
}
