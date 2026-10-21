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
            String status) {}

    public record TextImportRequest(@NotBlank(message = "请粘贴需要解析的文本") @Size(max = 200000) String text) {}

    public record RowsUpdateRequest(@NotNull(message = "rows 不能为空") List<RowDto> rows) {}

    /** Optional commit options; the timetable target defaults to the current semester. */
    public record CommitRequest(Long semesterId) {}

    /** A row that could not be imported, with the system values the user may pick instead. */
    public record RowErrorDto(int row, String field, String message, List<String> candidates) {}

    public record CommitResultDto(int imported, List<RowErrorDto> errors, Long draftId, List<String> warnings) {}
}
