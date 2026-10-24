package com.paiban.ai.imports.workbook;

import com.paiban.ai.imports.GradeInference;
import com.paiban.ai.imports.SlotText;
import com.paiban.ai.imports.workbook.ImportVocabulary.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a column that packs several fields into one cell, the way real exports do
 * ({@code 八（1）|星期一|1}). The rule is deterministic and conservative: the same separator
 * must appear in at least 80% of the column's non-empty values and at least one split part
 * must look like a weekday or a period, otherwise the column is left exactly as it is.
 * The model is told which derived columns already exist, so it never has to guess a split.
 */
public final class CompositeColumns {

    /** Separators that show up between packed timetable fields, most specific first. */
    private static final List<String> SEPARATORS = List.of("|", "｜", "·", "/", "—");

    private static final double RATIO = 0.8;
    private static final int MIN_VALUES = 3;

    /** One derived column: where it came from and which system field it carries. */
    public record Derived(String label, int sourceColumn, Field field) {}

    /**
     * {@code derived} are appended to the sheet's columns in order; {@code extraCells} is
     * aligned to {@code rows} and holds one value per derived column.
     */
    public record Split(List<Derived> derived, List<List<String>> extraCells) {

        public boolean empty() {
            return derived.isEmpty();
        }
    }

    private CompositeColumns() {}

    public static Split split(List<String> labels, List<List<String>> rows) {
        List<Derived> derived = new ArrayList<>();
        List<List<String>> extraCells = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            extraCells.add(new ArrayList<>());
        }
        for (int column = 0; column < labels.size(); column++) {
            SplitColumn split = splitColumn(labels.get(column), column, rows);
            if (split == null) {
                continue;
            }
            for (Derived item : split.derived()) {
                derived.add(item);
            }
            for (int row = 0; row < rows.size(); row++) {
                extraCells.get(row).addAll(split.cells().get(row));
            }
        }
        return new Split(List.copyOf(derived), immutable(extraCells));
    }

    private record SplitColumn(List<Derived> derived, List<List<String>> cells) {}

    private static SplitColumn splitColumn(String label, int column, List<List<String>> rows) {
        List<String> values = new ArrayList<>();
        for (List<String> row : rows) {
            String value = column < row.size() && row.get(column) != null ? row.get(column).trim() : "";
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        if (values.size() < MIN_VALUES) {
            return null;
        }
        String separator = dominantSeparator(values);
        if (separator == null) {
            return null;
        }
        List<List<String>> parts = new ArrayList<>();
        int maxParts = 0;
        for (List<String> row : rows) {
            String value = column < row.size() && row.get(column) != null ? row.get(column).trim() : "";
            List<String> cells = value.isEmpty() || !value.contains(separator)
                    ? (value.isEmpty() ? List.of() : List.of(value))
                    : split(value, separator);
            parts.add(cells);
            maxParts = Math.max(maxParts, cells.size());
        }
        if (maxParts < 2) {
            return null;
        }
        List<Field> kinds = classify(parts, maxParts);
        if (!kinds.contains(Field.DAY) && !kinds.contains(Field.PERIOD)) {
            return null;
        }
        String base = label == null || label.isBlank() ? "列" + (column + 1) : label;
        List<Derived> derived = new ArrayList<>();
        for (int part = 0; part < maxParts; part++) {
            Field kind = kinds.get(part);
            String suffix = kind == null ? "拆分" + (part + 1) : kind.label();
            derived.add(new Derived(base + "·" + suffix, column, kind));
        }
        List<List<String>> cells = new ArrayList<>();
        for (List<String> rowParts : parts) {
            List<String> padded = new ArrayList<>();
            for (int part = 0; part < maxParts; part++) {
                padded.add(part < rowParts.size() ? rowParts.get(part) : "");
            }
            cells.add(List.copyOf(padded));
        }
        return new SplitColumn(List.copyOf(derived), List.copyOf(cells));
    }

    /** The separator present in at least 80% of the non-empty values, or null. */
    private static String dominantSeparator(List<String> values) {
        String best = null;
        int bestCount = 0;
        for (String separator : SEPARATORS) {
            int count = 0;
            for (String value : values) {
                if (value.contains(separator)) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = separator;
                bestCount = count;
            }
        }
        if (best == null || bestCount < Math.ceil(values.size() * RATIO)) {
            return null;
        }
        return best;
    }

    private static List<String> split(String value, String separator) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split(java.util.regex.Pattern.quote(separator), -1)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return List.copyOf(parts);
    }

    /**
     * Two passes: a part that explicitly says 星期/节 is that field; a bare number is the
     * period when another part already names the day, and the day otherwise; what is left
     * that looks like a class name is the class.
     */
    private static List<Field> classify(List<List<String>> parts, int maxParts) {
        List<Field> kinds = new ArrayList<>();
        for (int part = 0; part < maxParts; part++) {
            kinds.add(strongKind(parts, part));
        }
        boolean hasStrongDay = kinds.contains(Field.DAY);
        for (int part = 0; part < maxParts; part++) {
            if (kinds.get(part) != null) {
                continue;
            }
            if (ratio(parts, part, value -> SlotText.periodNo(value).isPresent()
                    && value.matches("第?[0-9一二三四五六七八九十]+节?")) >= RATIO) {
                kinds.set(part, hasStrongDay ? Field.PERIOD : Field.DAY);
            }
        }
        for (int part = 0; part < maxParts; part++) {
            if (kinds.get(part) == null && ratio(parts, part, CompositeColumns::looksLikeClass) >= RATIO) {
                kinds.set(part, Field.CLASS_NAME);
            }
        }
        return kinds;
    }

    private static Field strongKind(List<List<String>> parts, int part) {
        if (ratio(parts, part, value -> value.contains("星期") || value.contains("礼拜")
                || value.contains("周")) >= RATIO) {
            return Field.DAY;
        }
        if (ratio(parts, part, value -> value.contains("节")) >= RATIO) {
            return Field.PERIOD;
        }
        return null;
    }

    private static double ratio(
            List<List<String>> parts, int part, java.util.function.Predicate<String> matches) {
        int seen = 0;
        int hit = 0;
        for (List<String> row : parts) {
            if (part >= row.size()) {
                continue;
            }
            String value = row.get(part);
            if (value.isBlank()) {
                continue;
            }
            seen++;
            if (matches.test(value)) {
                hit++;
            }
        }
        return seen == 0 ? 0 : (double) hit / seen;
    }

    private static boolean looksLikeClass(String value) {
        return GradeInference.infer(value) != null
                || value.endsWith("班")
                || value.matches(".*[（(][0-9]+[）)]$");
    }

    private static List<List<String>> immutable(List<List<String>> cells) {
        List<List<String>> copy = new ArrayList<>();
        for (List<String> row : cells) {
            copy.add(List.copyOf(row));
        }
        return List.copyOf(copy);
    }
}
