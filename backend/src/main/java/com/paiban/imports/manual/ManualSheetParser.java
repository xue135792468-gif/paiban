package com.paiban.imports.manual;

import com.paiban.ai.imports.SlotText;
import com.paiban.imports.ImportDtos.ManualMapping;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Turns one mapped sheet into normalized slot rows and the five counts the preview
 * and recipe tests agree on. Extracted from the 05 manual path so a recipe can be
 * re-tested against its stored sample without going through an HTTP upload.
 *
 * <p>The counts are the contract: {@code slotCount}, {@code lessonCount},
 * {@code emptySlotCount}, {@code teacherCount} and {@code courseCount}.
 */
@Component
public class ManualSheetParser {

    private static final int MAX_WARNINGS = 100;

    /** One data row after column mapping; {@code empty} means "no lesson in this slot". */
    public record NormalizedRow(
            int rowNumber,
            String className,
            String dayText,
            String periodText,
            Integer dayOfWeek,
            Integer periodNo,
            String courseName,
            String teacherName,
            boolean empty) {}

    public record Parsed(
            List<NormalizedRow> validRows,
            int slotCount,
            int lessonCount,
            int emptySlotCount,
            List<NormalizedRow> emptyRows,
            Set<String> teacherNames,
            Set<String> courseNames,
            List<String> warnings) {}

    /**
     * Parses every data row. {@code knownClasses} only drives warnings; an empty set
     * (recipe test) skips the "class not found" check because no semester is involved.
     */
    public Parsed parse(SourceSheets.Sheet sheet, ManualMapping mapping, Set<String> knownClasses) {
        List<NormalizedRow> valid = new ArrayList<>();
        List<NormalizedRow> emptyRows = new ArrayList<>();
        Set<String> teacherNames = new LinkedHashSet<>();
        Set<String> courseNames = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        int slotCount = 0;
        int lessonCount = 0;

        Set<String> classes = knownClasses == null ? Set.of() : knownClasses;
        List<String> keyParts = mapping.resolvedKeyParts();
        int dataStart = Math.max(mapping.resolvedHeaderRow() + 1, 0);
        for (int index = dataStart; index < sheet.rows().size(); index++) {
            List<String> row = sheet.row(index);
            int rowNumber = index + 1;
            String keyRaw = cell(row, mapping.keyColumn());
            String className = "";
            String dayText = "";
            String periodText = "";
            if (mapping.keyColumn() != null) {
                String[] parts = keyRaw.isEmpty()
                        ? new String[0]
                        : keyRaw.split(Pattern.quote(mapping.resolvedDelimiter()), -1);
                for (int part = 0; part < keyParts.size() && part < parts.length; part++) {
                    String value = parts[part] == null ? "" : parts[part].trim();
                    switch (keyParts.get(part)) {
                        case "className" -> className = value;
                        case "dayOfWeek" -> dayText = value;
                        case "periodNo" -> periodText = value;
                        default -> {
                            // Unknown key part: a forward-compatible recipe may add fields.
                        }
                    }
                }
            } else {
                className = cell(row, mapping.classNameColumn());
                dayText = cell(row, mapping.dayOfWeekColumn());
                periodText = cell(row, mapping.periodNoColumn());
            }
            String course = cell(row, mapping.courseColumn());
            String teacher = cell(row, mapping.teacherColumn());

            if (allBlank(row, mapping)) {
                continue;
            }
            if (!StringUtils.hasText(className)
                    && !StringUtils.hasText(dayText) && !StringUtils.hasText(periodText)) {
                warn(warnings, "第 " + rowNumber + " 行：班级/星期/节次为空，已跳过");
                continue;
            }
            Integer day = SlotText.day(dayText).orElse(null);
            Integer period = SlotText.periodNo(periodText).orElse(null);
            if (!StringUtils.hasText(className)) {
                warn(warnings, "第 " + rowNumber + " 行：缺少班级，已跳过");
                continue;
            }
            if (day == null) {
                warn(warnings, "第 " + rowNumber + " 行：无法识别星期「" + dayText + "」，已跳过");
                continue;
            }
            if (period == null) {
                warn(warnings, "第 " + rowNumber + " 行：无法识别节次「" + periodText + "」，已跳过");
                continue;
            }
            if (!classes.isEmpty() && !classes.contains(SlotText.normalizeName(className))) {
                warn(warnings, "第 " + rowNumber + " 行：系统中找不到班级「" + className + "」");
            }
            slotCount++;
            boolean empty = !StringUtils.hasText(course);
            NormalizedRow normalized = new NormalizedRow(
                    rowNumber, className, dayText, periodText, day, period, course, teacher, empty);
            valid.add(normalized);
            if (empty) {
                emptyRows.add(normalized);
            } else {
                lessonCount++;
                courseNames.add(course);
                if (StringUtils.hasText(teacher)) {
                    teacherNames.add(teacher);
                }
            }
        }
        return new Parsed(
                List.copyOf(valid), slotCount, lessonCount, emptyRows.size(),
                List.copyOf(emptyRows), teacherNames, courseNames, List.copyOf(warnings));
    }

    private boolean allBlank(List<String> row, ManualMapping mapping) {
        List<Integer> columns = new ArrayList<>();
        columns.add(mapping.keyColumn());
        columns.add(mapping.classNameColumn());
        columns.add(mapping.dayOfWeekColumn());
        columns.add(mapping.periodNoColumn());
        columns.add(mapping.courseColumn());
        columns.add(mapping.teacherColumn());
        for (Integer column : columns) {
            if (column != null && StringUtils.hasText(cell(row, column))) {
                return false;
            }
        }
        return true;
    }

    private static String cell(List<String> row, Integer column) {
        if (column == null || column < 0 || column >= row.size() || row.get(column) == null) {
            return "";
        }
        return row.get(column).trim();
    }

    private static void warn(Set<String> warnings, String message) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(message);
        }
    }
}
