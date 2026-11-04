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
     * {@code expected} lets the preview flag counts that drifted too far from what the
     * recipe promised (ticket 06) without a second request.
     */
    public record RecipeMatchDto(
            String kind,
            Long recipeId,
            String recipeName,
            List<RecipeDifferenceDto> differences,
            Map<String, Object> expected,
            ManualMapping mapping) {

        public static RecipeMatchDto none() {
            return new RecipeMatchDto("NONE", null, null, List.of(), Map.of(), null);
        }
    }

    /**
     * The preview is the contract the page renders: totals, a window of rows, the
     * suspicious-teacher channel, the suggested weekly rule and the recipe verdict.
     * {@code fingerprint} is what "保存为配方" stores; the page never computes it.
     *
     * <p>{@code teacherWarnings} is informational (the value looks like a course name or
     * a placeholder); {@code pendingTeachers} is the actionable subset that does not
     * exist as a teacher yet, so importing would create it. The page must get an explicit
     * answer for exactly those before it may commit.
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
            List<String> pendingTeachers,
            SuggestedRuleDto suggestedRule,
            RecipeMatchDto recipeMatch,
            List<String> warnings,
            Map<String, Object> fingerprint,
            String layout) {}

    /**
     * Commit options: where the draft goes and how placeholder teachers are resolved.
     * A placeholder teacher is never created silently: the page must either map it
     * ({@code teacherNameMappings}, teacher id or another name) or say explicitly that
     * keeping it — and creating it — is what the user wants
     * ({@code keepPlaceholderTeachers}).
     */
    public record CommitOptions(
            Long semesterId,
            String draftName,
            Map<String, String> teacherNameMappings,
            Boolean applySuggestedRule,
            Boolean keepPlaceholderTeachers) {

        public boolean appliesSuggestedRule() {
            return Boolean.TRUE.equals(applySuggestedRule);
        }

        public boolean keepsPlaceholderTeachers() {
            return Boolean.TRUE.equals(keepPlaceholderTeachers);
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

    /**
     * Save/update the current mapping as a recipe; name is unique per installation.
     * The {@code ai*} fields are the ticket-07 provenance: they are written into the
     * version snapshot only, so the final user mapping and the raw AI proposal stay
     * distinguishable without a new audit table.
     */
    public record SaveRecipeRequest(
            String name,
            String layout,
            Map<String, Object> fingerprint,
            Map<String, Object> columns,
            Map<String, Object> options,
            Map<String, Object> expected,
            Object sample,
            Boolean aiSuggested,
            String aiRawResponse,
            Map<String, Object> aiSuggestedMapping) {}

    /**
     * The column mapping one model answer proposes. Column indexes are 0-based and
     * {@code null} means "no such column"; {@code compositeColumn} packs
     * 班级/星期/节次 into one cell, otherwise the three separate columns are used.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiRecipeSuggestion(
            String layout,
            String sheetName,
            Integer headerRow,
            String delimiter,
            Integer compositeColumn,
            Integer classColumn,
            Integer dayColumn,
            Integer periodColumn,
            Integer courseColumn,
            Integer teacherColumn,
            List<Integer> ignoreColumns,
            String reasoning) {}

    /**
     * Answer of {@code POST /api/imports/manual/ai-suggest}. {@code available=false}
     * is a normal 200 with no AI configured; a failed or unparseable call is
     * {@code available=true, success=false} plus {@code raw}, so the page can fall back
     * to manual mapping without an error dialog.
     */
    public record AiRecipeSuggestionDto(
            boolean available,
            boolean success,
            AiRecipeSuggestion suggestion,
            RecipeCounts previewSummary,
            String raw,
            String explanation,
            String modelName,
            long latencyMs,
            String error) {

        public static AiRecipeSuggestionDto unavailable(String message) {
            return new AiRecipeSuggestionDto(
                    false, false, null, null, null, message, null, 0L, null);
        }
    }

    /** The five numbers a recipe promises; the save/test comparison uses exactly these. */
    public record RecipeCounts(
            int slotCount,
            int lessonCount,
            int emptySlotCount,
            int teacherCount,
            int courseCount) {}

    /** One count that did not survive the sample re-run. */
    public record RecipeCountDifferenceDto(
            String field,
            String label,
            Integer expected,
            Integer actual) {}

    /**
     * Result of re-running a recipe's stored sample with its current mapping. Save
     * refuses with 409 and these differences when {@code pass} is false.
     */
    public record RecipeTestDto(
            boolean pass,
            RecipeCounts actual,
            Map<String, Integer> expected,
            List<RecipeCountDifferenceDto> differences) {}

    /** One historical snapshot as the version dialog lists it. */
    public record RecipeVersionDto(
            int version,
            java.time.Instant createdAt,
            String createdBy,
            String summary) {}

    /**
     * Edits a saved recipe. Any mapping/option/expected change is re-tested against the
     * stored sample (or the newly uploaded one) before it is written.
     */
    public record RecipePatchRequest(
            String name,
            Boolean enabled,
            String layout,
            Map<String, Object> columns,
            Map<String, Object> options,
            Map<String, Object> expected) {}
}
