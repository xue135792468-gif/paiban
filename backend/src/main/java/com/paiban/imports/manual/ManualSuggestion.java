package com.paiban.imports.manual;

import com.paiban.ai.imports.workbook.CompositeColumns;
import com.paiban.ai.imports.workbook.HeaderDetection;
import com.paiban.ai.imports.workbook.ImportVocabulary;
import com.paiban.ai.imports.workbook.ImportVocabulary.Field;
import com.paiban.imports.ImportDtos.ManualMapping;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A first guess at the column mapping, so the page can prefill the selects instead of
 * asking the user to map everything from zero. The guess is deterministic and explains
 * itself: header words for the individual columns, {@link CompositeColumns} for a packed
 * {@code 班级|星期|节次} column. The user can always override every field.
 */
public final class ManualSuggestion {

    /** Separators a composite key may use; the same list CompositeColumns knows plus CSV-ish ones. */
    private static final List<String> SEPARATORS =
            List.of("|", "｜", "·", "/", "—", ",", "，", "-", "_");

    private ManualSuggestion() {}

    public static ManualMapping suggest(SourceSheets.Sheet sheet) {
        HeaderDetection.Header header = HeaderDetection.detect(sheet.rows());
        int headerRow = header.rowIndex();
        List<String> labels = header.labels();
        List<List<String>> data = sheet.rows().subList(
                Math.min(header.dataStart(), sheet.rows().size()), sheet.rows().size());

        Integer keyColumn = null;
        String delimiter = null;
        List<String> keyParts = null;

        Integer labelKey = compositeLabelColumn(labels);
        if (labelKey != null) {
            keyColumn = labelKey;
            delimiter = separatorOf(labels.get(labelKey), data, labelKey);
            keyParts = keyPartsOf(labels.get(labelKey));
        } else {
            CompositeColumns.Split split = CompositeColumns.split(labels, data);
            Map<Integer, List<Field>> byColumn = new LinkedHashMap<>();
            for (CompositeColumns.Derived derived : split.derived()) {
                byColumn.computeIfAbsent(derived.sourceColumn(), key -> new ArrayList<>())
                        .add(derived.field());
            }
            for (Map.Entry<Integer, List<Field>> entry : byColumn.entrySet()) {
                if (entry.getValue().containsAll(
                        List.of(Field.CLASS_NAME, Field.DAY, Field.PERIOD))) {
                    keyColumn = entry.getKey();
                    delimiter = separatorOf(null, data, entry.getKey());
                    keyParts = new ArrayList<>();
                    for (Field field : entry.getValue()) {
                        keyParts.add(field.field());
                    }
                    break;
                }
            }
        }

        Integer classNameColumn = null;
        Integer dayColumn = null;
        Integer periodColumn = null;
        Integer courseColumn = null;
        Integer teacherColumn = null;
        for (int column = 0; column < labels.size(); column++) {
            if (keyColumn != null && column == keyColumn) {
                continue;
            }
            Field field = ImportVocabulary.match(labels.get(column));
            if (field == null) {
                continue;
            }
            switch (field) {
                case CLASS_NAME -> classNameColumn = classNameColumn == null ? column : classNameColumn;
                case DAY -> dayColumn = dayColumn == null ? column : dayColumn;
                case PERIOD -> periodColumn = periodColumn == null ? column : periodColumn;
                case COURSE -> courseColumn = courseColumn == null ? column : courseColumn;
                case TEACHER -> teacherColumn = teacherColumn == null ? column : teacherColumn;
                default -> {
                    // A name/employee column belongs to an entity import, not a timetable.
                }
            }
        }

        return new ManualMapping(
                sheet.name(), headerRow, delimiter, keyColumn,
                keyParts, classNameColumn, dayColumn, periodColumn, courseColumn, teacherColumn, true);
    }

    /** The first header that names at least two of 班级/星期/节次: a packed key column. */
    private static Integer compositeLabelColumn(List<String> labels) {
        for (int column = 0; column < labels.size(); column++) {
            String label = normalize(labels.get(column));
            int hits = 0;
            if (label.contains("班级") || label.contains("行政班")) {
                hits++;
            }
            if (label.contains("星期") || label.contains("周")) {
                hits++;
            }
            if (label.contains("节次") || label.contains("节")) {
                hits++;
            }
            if (hits >= 2) {
                return column;
            }
        }
        return null;
    }

    private static List<String> keyPartsOf(String label) {
        String normalized = normalize(label);
        record Token(String field, int index) {}
        List<Token> tokens = new ArrayList<>();
        tokens.add(new Token("className", indexOrMax(normalized, "班级", "行政班")));
        tokens.add(new Token("dayOfWeek", indexOrMax(normalized, "星期", "周")));
        tokens.add(new Token("periodNo", indexOrMax(normalized, "节次", "节")));
        tokens.sort((left, right) -> Integer.compare(left.index(), right.index()));
        return tokens.stream().map(Token::field).toList();
    }

    private static int indexOrMax(String label, String... needles) {
        int found = Integer.MAX_VALUE;
        for (String needle : needles) {
            int index = label.indexOf(needle);
            if (index >= 0) {
                found = Math.min(found, index);
            }
        }
        return found;
    }

    private static String separatorOf(String label, List<List<String>> data, int column) {
        if (label != null) {
            for (String candidate : SEPARATORS) {
                if (label.contains(candidate)) {
                    return candidate;
                }
            }
        }
        String best = null;
        int bestHits = 0;
        for (String candidate : SEPARATORS) {
            int hits = 0;
            for (List<String> row : data) {
                String value = column < row.size() && row.get(column) != null ? row.get(column) : "";
                if (value.contains(candidate)) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                best = candidate;
                bestHits = hits;
            }
        }
        return best == null ? "|" : best;
    }

    private static String normalize(String label) {
        return label == null ? "" : label.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
