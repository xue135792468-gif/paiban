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

    public record PreviewDto(
            Long sessionId,
            String targetType,
            List<ColumnDto> columns,
            List<RowDto> rows,
            List<MappingDto> mappings,
            String explanation,
            double threshold,
            String status,
            List<IssueDto> issues) {}

    public record TextImportRequest(@NotBlank(message = "请粘贴需要解析的文本") @Size(max = 200000) String text) {}

    public record RowsUpdateRequest(@NotNull(message = "rows 不能为空") List<RowDto> rows) {}

    /** Optional commit options; the timetable target defaults to the current semester. */
    public record CommitRequest(Long semesterId) {}

    /** A row that could not be imported, with the system values the user may pick instead. */
    public record RowErrorDto(int row, String field, String message, List<String> candidates) {}

    /**
     * One unmatched value in a preview. {@code required} is true when the target field
     * itself is required, i.e. the value cannot simply be cleared to move on.
     */
    public record IssueDto(int row, String field, String value, List<String> candidates, boolean required) {}

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
                             Long gradeId) {}

    public record MappingRequest(List<MappingItem> mappings, List<CreateItem> creates) {}

    public record CommitResultDto(int imported, List<RowErrorDto> errors, Long draftId, List<String> warnings) {}
}
