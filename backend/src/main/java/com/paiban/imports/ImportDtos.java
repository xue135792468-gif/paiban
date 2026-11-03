package com.paiban.imports;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.util.StringUtils;
import java.util.List;
import java.util.Map;

/** Contract of the manual (non-AI) import path and the import recipes it reuses. */
public final class ImportDtos {

    private ImportDtos() {}

    /**
     * How one sheet's columns feed the timetable shape. A composite key column
     * ({@code 班级|星期|节次}) is named by {@code keyColumn} + {@code keyParts};
     * separate columns use the three explicit indexes instead. Indexes are 0-based
     * and {@code headerRow} is -1 for a headerless sheet.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ManualMapping(
            String sheetName,
            Integer headerRow,
            String delimiter,
            Integer keyColumn,
            List<String> keyParts,
            Integer classNameColumn,
            Integer dayOfWeekColumn,
            Integer periodNoColumn,
            Integer courseColumn,
            Integer teacherColumn,
            Boolean ignoreBlankRows) {

        public int resolvedHeaderRow() {
            return headerRow == null ? -1 : headerRow;
        }

        public String resolvedDelimiter() {
            return StringUtils.hasText(delimiter) ? delimiter : "|";
        }

        public List<String> resolvedKeyParts() {
            return keyParts == null || keyParts.isEmpty()
                    ? List.of("className", "dayOfWeek", "periodNo")
                    : keyParts;
        }

        public boolean ignoresBlankRows() {
            return ignoreBlankRows == null || ignoreBlankRows;
        }

        /** True when the mapping names at least one column role; an empty map is "未选择". */
        public boolean hasLayout() {
            return keyColumn != null || classNameColumn != null
                    || courseColumn != null || teacherColumn != null;
        }
    }

    /** One sheet offered by inspect: shape, header candidates and a suggested mapping. */
    public record SheetInfoDto(
            String name,
            int rowCount,
            int columnCount,
            int headerRow,
            boolean headerless,
            List<String> headers,
            List<List<String>> sampleRows,
            ManualMapping suggestedMapping) {}

    public record InspectDto(List<SheetInfoDto> sheets) {}

    /** One preview row; the grid only shows a window, the counts cover the whole file. */
    public record PreviewRowDto(
            int row,
            String className,
            Integer dayOfWeek,
            Integer periodNo,
            String courseName,
            String teacherName,
            boolean empty) {}

    /** The rule a whole-school empty slot justifies, null when the empties are scattered. */
    public record SuggestedRuleDto(String type, Integer dayOfWeek, Integer periodNo, String text) {}

    /** How a recipe differs from the uploaded file; column is a label or a shape name. */
    public record RecipeDifferenceDto(String kind, String column, String detail) {}

    /**
     * EXACT re-applies silently, SIMILAR re-applies with the differences listed, NONE
     * asks the user to map columns again. {@code mapping} is the recipe's own mapping,
     * so the page can prefill the editor and preview without another round trip.
     */
    public record RecipeMatchDto(
            String kind,
            Long recipeId,
            String recipeName,
            List<RecipeDifferenceDto> differences,
            ManualMapping mapping) {

        public static RecipeMatchDto none() {
            return new RecipeMatchDto("NONE", null, null, List.of(), null);
        }
    }

    /**
     * The preview is the contract the page renders: totals, a window of rows, the
     * suspicious-teacher channel, the suggested weekly rule and the recipe verdict.
     * {@code fingerprint} is what "保存为配方" stores; the page never computes it.
     */
    public record ManualPreviewDto(
            int slotCount,
            int lessonCount,
            int emptySlotCount,
            int teacherCount,
            int courseCount,
            List<PreviewRowDto> rows,
            boolean truncated,
            List<com.paiban.ai.imports.ImportDtos.TeacherWarningDto> teacherWarnings,
            SuggestedRuleDto suggestedRule,
            RecipeMatchDto recipeMatch,
            List<String> warnings,
            Map<String, Object> fingerprint,
            String layout) {}

    /** Commit options: where the draft goes and how placeholder teachers are resolved. */
    public record CommitOptions(
            Long semesterId,
            String draftName,
            Map<String, String> teacherNameMappings,
            Boolean applySuggestedRule) {

        public boolean appliesSuggestedRule() {
            return Boolean.TRUE.equals(applySuggestedRule);
        }
    }

    public record CommitResultDto(
            Long draftId,
            int imported,
            int skippedEmpty,
            List<com.paiban.ai.imports.ImportDtos.RowErrorDto> errors,
            List<String> warnings,
            Long appliedRecipeId,
            SuggestedRuleDto appliedRule) {}

    /** A saved recipe, as list/detail surfaces read it (06 reuses this shape). */
    public record RecipeDto(
            Long id,
            String name,
            String layout,
            Map<String, Object> fingerprint,
            Map<String, Object> columns,
            Map<String, Object> options,
            Map<String, Object> expected,
            Object sample,
            int version,
            boolean enabled,
            String createdBy,
            java.time.Instant createdAt,
            java.time.Instant lastUsedAt) {}

    /** Save/update the current mapping as a recipe; name is unique per installation. */
    public record SaveRecipeRequest(
            String name,
            String layout,
            Map<String, Object> fingerprint,
            Map<String, Object> columns,
            Map<String, Object> options,
            Map<String, Object> expected,
            Object sample) {}
}
