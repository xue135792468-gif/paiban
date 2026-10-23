package com.paiban.ai.imports;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
            List<FailedBatchDto> failedBatches) {}

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

    /** Create one missing entity, then map the source value to its name. */
    public record CreateItem(@NotBlank(message = "字段不能为空") String field,
                             @NotBlank(message = "原值不能为空") String source,
                             String name,
                             Long gradeId,
                             String gradeName,
                             Integer studentCount) {}

    public record MappingRequest(List<MappingItem> mappings, List<CreateItem> creates) {}

    public record CommitResultDto(int imported, List<RowErrorDto> errors, Long draftId, List<String> warnings) {}
}
