package com.paiban.ai.imports.workbook;

import java.util.List;

/**
 * What a workbook session remembers between planning and extraction: the confirmed plan,
 * its version, and one line per sheet (including the ones the profiler skipped). Persisting
 * this means a retry never asks the model to re-read the structure; a re-plan increments
 * the version, so all old batch identities stop matching.
 */
public record WorkbookRunPlan(
        int version,
        WorkbookPlan plan,
        List<SheetMeta> sheets,
        List<String> warnings) {

    /** A sheet line as the preview lists it, independent of the uploaded file still existing. */
    public record SheetMeta(String name, String skippedReason, int dataRows) {}

    public WorkbookRunPlan {
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
