package com.paiban.ai.imports.workbook;

import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.ImportDtos.RowErrorDto;
import com.paiban.ai.imports.ImportDtos.TeacherWarningDto;
import com.paiban.ai.imports.ImportMappingService.CourseDraft;
import com.paiban.ai.imports.ImportMappingService.TeacherDraft;
import java.util.List;
import java.util.Map;

/**
 * Everything a workbook session stores between the preview and the commit. The preview is
 * derived from it, the commit writes what it names, and the row errors it carries are the
 * ones that blocked the preview. Keeping it in the session's JSON columns means a confirmed
 * preview never needs the AI again.
 */
public record WorkbookPayload(
        List<RowDto> rows,
        List<SheetSummary> sheets,
        boolean structureConfirmed,
        String explanation,
        List<String> warnings,
        /** The aggregated suspicious teacher values; the preview and the result read this one list. */
        List<TeacherWarningDto> teacherWarnings,
        List<RowErrorDto> errors,
        List<TeacherDraft> teachers,
        List<CourseDraft> courses,
        List<String> classes,
        /** Per-category write results; empty until a commit ran. */
        Map<String, CategorySummary> committed) {

    /** One sheet's contribution, the line the preview lists for it. */
    public record SheetSummary(
            String sheet,
            String category,
            Map<String, String> columns,
            int recognizedRows,
            int newRows,
            String skippedReason,
            String note) {

        public SheetSummary {
            columns = columns == null ? Map.of() : Map.copyOf(columns);
        }
    }

    /** What writing one category did. {@code errors} are per-category, not fatal to the rest. */
    public record CategorySummary(int created, int updated, int skipped, List<String> errors) {}

    public WorkbookPayload {
        rows = rows == null ? List.of() : List.copyOf(rows);
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        teacherWarnings = teacherWarnings == null ? List.of() : List.copyOf(teacherWarnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
        teachers = teachers == null ? List.of() : List.copyOf(teachers);
        courses = courses == null ? List.of() : List.copyOf(courses);
        classes = classes == null ? List.of() : List.copyOf(classes);
        committed = committed == null ? Map.of() : Map.copyOf(committed);
    }
}
