package com.paiban.ai.imports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class ImportDtos {

    private ImportDtos() {}

    public record ColumnDto(String field, String label, boolean required) {}

    public record RowDto(
            Map<String, String> values,
            Map<String, Double> confidence,
            List<String> lowConfidence) {}

    public record MappingDto(String source, String target, String note) {}

    /**
     * How far a background parse has got. {@code failed} counts batches that errored,
     * {@code currentBatch} is the class being parsed right now (null when idle).
     */
    public record ImportProgressDto(int total, int completed, int failed, String currentBatch) {}

    /** A batch the AI could not parse, kept so the page can name the class to retry. */
    public record FailedBatchDto(int index, String label, String reason) {}

    /**
     * One sheet of a workbook import: what the model judged it to be, which columns feed
     * which system fields, how many rows it contributed and how many survived de-duplication.
     * {@code skippedReason} is set for sheets a conservative rule skipped.
     */
    public record SheetSummaryDto(
            String sheet,
            String category,
            Map<String, String> columns,
            int recognizedRows,
            int newRows,
            String skippedReason,
            String note) {}

    /** The workbook behind a preview: whether the model confirmed it, and each sheet. */
    public record WorkbookSummaryDto(
            boolean structureConfirmed,
            List<SheetSummaryDto> sheets,
            /** Empty until a commit ran; then one entry per category with what it wrote. */
            List<CategoryResultDto> committed) {}

    /**
     * One round of the tool loop: what the model asked for, its own reply, and what the
     * tool returned. Persisted so a wrong structural judgement can be replayed.
     */
    public record ToolTraceDto(int round, String tool, String args, String reply, String result) {}

    /** What writing one category did; a failure here does not undo the other categories. */
    public record CategoryResultDto(String category, int created, int updated, int skipped, List<String> errors) {}

    public record PreviewDto(
            Long sessionId,
            String targetType,
            /** What the AI thinks the data looks like, when it differs from the request. */
            String suggestedTarget,
            List<ColumnDto> columns,
            List<RowDto> rows,
            List<MappingDto> mappings,
            String explanation,
            double threshold,
            String status,
            List<IssueDto> issues,
            TimetableSummaryDto summary,
            /** Null unless the session was (or is being) parsed in batches. */
            ImportProgressDto progress,
            /** Batches that failed; empty unless the parse is partial (or was stopped). */
            List<FailedBatchDto> failedBatches,
            /** Null for every target except {@link ImportTarget#WORKBOOK}. */
            WorkbookSummaryDto workbook,
            /** Non-blocking notes: free periods skipped, duplicate rows dropped, structure unconfirmed. */
            List<String> warnings,
            /** The tool rounds the model ran while browsing the workbook. */
            List<ToolTraceDto> toolTrace,
            /**
             * Teacher values that look like a course name or a placeholder. A separate,
             * non-blocking channel: a warning never becomes an issue and never stops the
             * commit or the draft.
             */
            List<TeacherWarningDto> teacherWarnings) {}

    public record TextImportRequest(@NotBlank(message = "请粘贴需要解析的文本") @Size(max = 200000) String text) {}

    public record RowsUpdateRequest(@NotNull(message = "rows 不能为空") List<RowDto> rows) {}

    /**
     * What a timetable import would write: every preview row is a lesson (matched or
     * not), {@code unresolved} counts the rows that could not be matched, and the
     * (class, course) groups are counted only over the matched rows.
     */
    public record TimetableSummaryDto(
            int lessons, int unresolved, int groups, int newAssignments, int updatedAssignments) {}

    /** A row that could not be imported, with the system values the user may pick instead. */
    public record RowErrorDto(int row, String field, String message, List<String> candidates) {}

    /**
     * One unmatched value in a preview. {@code required} is true when the target field
     * itself is required, i.e. the value cannot simply be cleared to move on. A
     * {@code row} of 0 is a page-level blocker (nothing to map) and carries its reason
     * in {@code message}. {@code inferredGrade} is null unless this is a class issue
     * whose name carries a grade; {@code gradeCandidates} lists the current semester's
     * grades (empty for non-class issues).
     */
    public record IssueDto(
            int row, String field, String value, List<String> candidates, boolean required, String message,
            String inferredGrade, List<String> gradeCandidates) {}

    /** One system entity offered as a mapping target. {@code extra} is display context. */
    public record LookupDto(Long id, String name, String extra) {}

    /** Resolve one source value to an existing entity. */
    public record MappingItem(@NotBlank(message = "字段不能为空") String field,
                              @NotBlank(message = "原值不能为空") String source,
                              @NotNull(message = "目标 ID 不能为空") Long targetId) {}

    /**
     * Create one missing entity, then map the source value to its name. A class's
     * {@code studentCount} is optional (the entity default applies) but, when given, it
     * must be a positive integer — the same rule the manual class form enforces.
     */
    public record CreateItem(@NotBlank(message = "字段不能为空") String field,
                             @NotBlank(message = "原值不能为空") String source,
                             String name,
                             Long gradeId,
                             String gradeName,
                             @Positive Integer studentCount) {}

    public record MappingRequest(List<MappingItem> mappings, List<@Valid CreateItem> creates) {}

    public record CommitResultDto(
            int imported,
            List<RowErrorDto> errors,
            Long draftId,
            List<String> warnings,
            /** Per-category write results; empty for single-target imports. */
            List<CategoryResultDto> categories,
            /** The same aggregated suspicious teacher values the preview showed. */
            List<TeacherWarningDto> teacherWarnings) {}

    /** Why one teacher value was flagged; {@code code} is stable, {@code message} is for people. */
    public record TeacherWarningReasonDto(String code, String message) {}

    /**
     * One suspicious teacher value, de-duplicated on its normalized form. {@code rowNumbers}
     * are 1-based preview rows (the grid's lesson ids are their negatives); {@code lessonCount}
     * is how many lessons carry it, 0 when it came from a name sheet only.
     */
    public record TeacherWarningDto(
            String value, List<TeacherWarningReasonDto> reasons, int lessonCount, List<Integer> rowNumbers) {}
}
