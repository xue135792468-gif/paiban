package com.paiban.foundation.web;

import java.util.List;
import java.util.Map;

public final class ImportDtos {

    private ImportDtos() {}

    public record ImportError(int row, String field, String message) {}

    public record ImportReport(String entity,
                               String displayName,
                               int totalRows,
                               int validRows,
                               int errorCount,
                               int importedRows,
                               List<ImportError> errors,
                               List<String> warnings,
                               List<Map<String, String>> rows) {}

    public record ImportSpecDto(String entity,
                                String displayName,
                                List<String> headers,
                                List<String> requiredHeaders,
                                String sampleFileName) {}
}
