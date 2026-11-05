package com.paiban.imports.recipe;

import com.paiban.ai.imports.workbook.HeaderDetection;
import com.paiban.imports.ImportDtos.ManualMapping;
import com.paiban.imports.manual.SourceSheets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * The stable identity of a spreadsheet layout: sheet name, header row, column count,
 * the header texts in column order, the composite-key delimiter and the layout family.
 * 06's management page and 07's AI-authored recipes both compare against this same shape.
 *
 * <p>The header list keeps the sheet's own column order: the mapping addresses columns by
 * index, so "same texts, different order" is a different layout, and comparing two
 * fingerprints position by position is what lets the matcher say “第 3 列由「教师」改成
 * 「任课教师」” instead of inventing a rename for every column after an insertion.
 */
public record ImportFingerprint(
        String sheetName,
        int headerRow,
        int columnCount,
        List<String> headers,
        String delimiter,
        String layout) {

    public static final String LAYOUT_LONG = "LONG";
    public static final String LAYOUT_GRID = "GRID";

    public ImportFingerprint {
        headers = headers == null ? List.of() : List.copyOf(headers);
        delimiter = delimiter == null ? "" : delimiter;
        layout = StringUtils.hasText(layout) ? layout : LAYOUT_LONG;
    }

    public static ImportFingerprint of(SourceSheets.Sheet sheet, ManualMapping mapping) {
        int columnCount = sheet.columnCount();
        int headerRow = mapping == null ? -1 : mapping.resolvedHeaderRow();
        List<String> headers = new ArrayList<>();
        if (headerRow >= 0 && headerRow < sheet.rows().size()) {
            List<String> row = sheet.row(headerRow);
            for (int column = 0; column < columnCount; column++) {
                String value = column < row.size() && row.get(column) != null ? row.get(column).trim() : "";
                headers.add(value.isEmpty() ? "列" + (column + 1) : value);
            }
        } else {
            headers.addAll(HeaderDetection.positionalLabels(columnCount));
        }
        String layout = mapping != null && mapping.keyColumn() != null ? LAYOUT_LONG : LAYOUT_GRID;
        return new ImportFingerprint(
                sheet.name(), headerRow, columnCount, headers,
                mapping == null ? "" : mapping.resolvedDelimiter(), layout);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sheetName", sheetName);
        map.put("headerRow", headerRow);
        map.put("columnCount", columnCount);
        map.put("headers", headers);
        map.put("delimiter", delimiter);
        map.put("layout", layout);
        return map;
    }

    public static ImportFingerprint fromMap(Map<String, Object> map) {
        if (map == null) {
            return new ImportFingerprint("", -1, 0, List.of(), "", LAYOUT_LONG);
        }
        List<String> headers = new ArrayList<>();
        Object rawHeaders = map.get("headers");
        if (rawHeaders instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    headers.add(item.toString());
                }
            }
        }
        return new ImportFingerprint(
                text(map.get("sheetName")),
                number(map.get("headerRow"), -1),
                number(map.get("columnCount"), 0),
                headers,
                text(map.get("delimiter")),
                text(map.get("layout")));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
