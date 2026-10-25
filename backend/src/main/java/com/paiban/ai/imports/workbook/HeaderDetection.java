package com.paiban.ai.imports.workbook;

import com.paiban.ai.imports.workbook.ImportVocabulary.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the header row of a sheet from the words schools actually use. A sheet whose first
 * rows are data has no header: the old "the first two rows are headers" assumption silently
 * ate those rows, so a missing header now means zero header rows instead.
 */
public final class HeaderDetection {

    private static final int MAX_SCAN_ROWS = 10;
    /** A header row names at least this many distinct fields. */
    private static final int MIN_FIELDS = 2;
    private static final int MAX_TITLE_ROWS = 3;

    /**
     * {@code rowIndex} is -1 for a headerless sheet; {@code labels} is aligned to the sheet's
     * columns (blank headers become {@code 列N}); {@code dataStart} is the first data row.
     */
    public record Header(
            int rowIndex, List<String> labels, Set<Field> fields, int columnCount, int dataStart) {

        public boolean headerless() {
            return rowIndex < 0;
        }
    }

    private HeaderDetection() {}

    public static Header detect(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return new Header(-1, List.of(), Set.of(), 0, 0);
        }
        int columnCount = widest(rows);
        int scan = Math.min(rows.size(), MAX_SCAN_ROWS);
        int bestRow = -1;
        Set<Field> bestFields = Set.of();
        for (int r = 0; r < scan; r++) {
            Set<Field> fields = EnumSet.noneOf(Field.class);
            for (String cell : rows.get(r)) {
                Field field = ImportVocabulary.match(cell);
                if (field != null) {
                    fields.add(field);
                }
            }
            if (fields.size() > bestFields.size()) {
                bestRow = r;
                bestFields = fields;
            }
        }
        if (bestRow < 0 || bestFields.size() < MIN_FIELDS) {
            int dataStart = skipTitleRows(rows, columnCount);
            return new Header(-1, positionalLabels(columnCount), Set.of(), columnCount, dataStart);
        }
        return new Header(
                bestRow, labels(rows.get(bestRow), columnCount), Set.copyOf(bestFields),
                columnCount, bestRow + 1);
    }

    /** {@code 列1..列N}: the names a headerless sheet's columns get in prompts and plans. */
    public static List<String> positionalLabels(int columnCount) {
        List<String> labels = new ArrayList<>();
        for (int c = 1; c <= columnCount; c++) {
            labels.add("列" + c);
        }
        return List.copyOf(labels);
    }

    private static List<String> labels(List<String> row, int columnCount) {
        List<String> labels = new ArrayList<>();
        for (int c = 0; c < columnCount; c++) {
            String value = c < row.size() && row.get(c) != null ? row.get(c).trim() : "";
            labels.add(value.isEmpty() ? "列" + (c + 1) : value);
        }
        return List.copyOf(labels);
    }

    /**
     * A headerless sheet often still starts with a title line ("班级课表") and a date line.
     * Only a row that is sparse <em>and</em> much sparser than the rows below it is dropped,
     * so a genuine two-column teacher list keeps its first record.
     */
    private static int skipTitleRows(List<List<String>> rows, int columnCount) {
        int start = 0;
        int limit = Math.min(rows.size(), MAX_TITLE_ROWS);
        while (start < limit) {
            int filled = nonEmpty(rows.get(start));
            if (filled > 2 || columnCount <= 2) {
                break;
            }
            int below = 0;
            for (int r = start + 1; r < Math.min(rows.size(), start + 5); r++) {
                below = Math.max(below, nonEmpty(rows.get(r)));
            }
            if (below < filled + 2) {
                break;
            }
            start++;
        }
        return start;
    }

    static int nonEmpty(List<String> row) {
        if (row == null) {
            return 0;
        }
        int count = 0;
        for (String cell : row) {
            if (cell != null && !cell.isBlank()) {
                count++;
            }
        }
        return count;
    }

    static int widest(List<List<String>> rows) {
        int widest = 0;
        for (List<String> row : rows) {
            widest = Math.max(widest, row == null ? 0 : row.size());
        }
        return widest;
    }
}
