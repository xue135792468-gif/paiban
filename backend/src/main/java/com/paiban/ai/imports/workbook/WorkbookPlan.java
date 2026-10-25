package com.paiban.ai.imports.workbook;

import com.paiban.ai.imports.workbook.ImportVocabulary.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the model concluded about the workbook after browsing it, in the shape the rest of
 * the import consumes: one category and one column mapping per sheet. {@code confirmed} is
 * false when the tool loop ran out of rounds, so the preview can say so instead of throwing
 * the whole upload away.
 */
public record WorkbookPlan(List<SheetPlan> sheets, boolean confirmed, String explanation) {

    public enum SheetCategory {
        /** One row is one lesson: class + day + period (+ course, teacher, room). */
        TIMETABLE,
        /** One row is one teacher. */
        TEACHER,
        /** One row is one course. */
        COURSE,
        /** Instructions, a title page, notes: read and skipped. */
        EXPLANATION,
        /** Not recognised; kept in the sheet list with its reason. */
        UNKNOWN;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** A category name the model may write; anything else is {@link #UNKNOWN}. */
        public static SheetCategory from(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNKNOWN;
            }
            String value = raw.trim().toUpperCase(Locale.ROOT);
            for (SheetCategory category : values()) {
                if (category.name().equals(value)) {
                    return category;
                }
            }
            return UNKNOWN;
        }
    }

    /**
     * One sheet's judgement. {@code columns} maps a system field to the sheet's column label;
     * {@code confirmed} is true when this plan came from a {@code finish} call rather than
     * from the deterministic fallback.
     */
    public record SheetPlan(
            String sheet,
            SheetCategory category,
            Map<String, String> columns,
            String note,
            boolean confirmed) {

        public String column(String field) {
            return columns.getOrDefault(field, "");
        }
    }

    public WorkbookPlan {
        sheets = List.copyOf(sheets);
    }

    /** Every sheet that contributes rows to the preview (explanations do not). */
    public List<SheetPlan> dataSheets() {
        return sheets.stream()
                .filter(plan -> plan.category() != SheetCategory.EXPLANATION
                        && plan.category() != SheetCategory.UNKNOWN)
                .toList();
    }

    /**
     * The plan the import uses when the model never declared the structure: header words and
     * sheet names decide, so a six-round timeout still yields a usable, clearly-marked
     * preview instead of an empty one.
     */
    public static WorkbookPlan fallback(List<ProfiledSheet> profiles, String note) {
        List<SheetPlan> sheets = new ArrayList<>();
        for (ProfiledSheet sheet : profiles) {
            if (sheet.skipped()) {
                continue;
            }
            SheetCategory category = guess(sheet);
            sheets.add(new SheetPlan(
                    sheet.name(), category, columns(sheet, category), "", false));
        }
        return new WorkbookPlan(sheets, false, note);
    }

    public static SheetCategory guess(ProfiledSheet sheet) {
        String name = sheet.name() == null ? "" : sheet.name();
        if (contains(name, "说明", "使用", "帮助", "目录", "填写")) {
            return SheetCategory.EXPLANATION;
        }
        int className = sheet.columnOf(Field.CLASS_NAME);
        int day = sheet.columnOf(Field.DAY);
        int period = sheet.columnOf(Field.PERIOD);
        int course = sheet.columnOf(Field.COURSE);
        int teacher = sheet.columnOf(Field.TEACHER);
        int person = sheet.columnOf(Field.NAME);
        if (className > 0 && day > 0 && period > 0) {
            return SheetCategory.TIMETABLE;
        }
        if ((person > 0 || teacher > 0)
                && (sheet.columnOf(Field.EMPLOYEE_NO) > 0
                        || sheet.columnOf(Field.PHONE) > 0
                        || sheet.columnOf(Field.EMAIL) > 0
                        || sheet.columnOf(Field.SUBJECT) > 0)
                && day == 0) {
            return SheetCategory.TEACHER;
        }
        if (course > 0 && day == 0) {
            return SheetCategory.COURSE;
        }
        if (contains(name, "课表", "排课", "考勤", "数据源", "班级")) {
            return SheetCategory.TIMETABLE;
        }
        if (contains(name, "教师", "老师", "名单")) {
            return SheetCategory.TEACHER;
        }
        if (contains(name, "课程", "科目")) {
            return SheetCategory.COURSE;
        }
        return SheetCategory.UNKNOWN;
    }

    /** Header-driven mapping for one category; only columns the sheet actually has. */
    public static Map<String, String> columns(ProfiledSheet sheet, SheetCategory category) {
        List<Field> fields = switch (category) {
            case TIMETABLE -> List.of(
                    Field.CLASS_NAME, Field.DAY, Field.PERIOD,
                    Field.COURSE, Field.TEACHER, Field.ROOM);
            case TEACHER -> List.of(
                    Field.NAME, Field.TEACHER, Field.EMPLOYEE_NO, Field.PHONE,
                    Field.EMAIL, Field.SUBJECT);
            case COURSE -> List.of(Field.COURSE, Field.SUBJECT);
            default -> List.of();
        };
        Map<String, String> columns = new LinkedHashMap<>();
        for (Field field : fields) {
            String target = switch (category) {
                case TEACHER -> field == Field.TEACHER ? "name" : field.field();
                case COURSE -> field == Field.COURSE ? "name" : field.field();
                default -> field.field();
            };
            if (columns.containsKey(target)) {
                continue;
            }
            int column = sheet.columnOf(field);
            if (column > 0) {
                columns.put(target, sheet.labels().get(column - 1));
            }
        }
        if (sheet.headerRowIndex() < 0) {
            positional(columns, sheet, category);
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    /**
     * A headerless sheet has no words to map, so after the packed column is split the rest
     * is taken in file order: course, teacher, room (timetable); name, subject (course);
     * name (teacher). This is only the fallback plan - a confirmed plan names its columns.
     */
    private static void positional(
            Map<String, String> columns, ProfiledSheet sheet, SheetCategory category) {
        // Room stays empty: real exports have no reliable room column, and taking the next
        // free column would try to match a course/teacher string as a room name.
        List<String> remaining = switch (category) {
            case TIMETABLE -> List.of("courseName", "teacherName");
            case TEACHER -> List.of("name");
            case COURSE -> List.of("name", "subject");
            default -> List.of();
        };
        int originalColumns = sheet.labels().size() - sheet.derived().size();
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (CompositeColumns.Derived item : sheet.derived()) {
            used.add(item.sourceColumn());
        }
        List<Integer> free = new ArrayList<>();
        for (int column = 0; column < originalColumns; column++) {
            if (!used.contains(column)) {
                free.add(column);
            }
        }
        int next = 0;
        for (String field : remaining) {
            if (columns.containsKey(field) || next >= free.size()) {
                continue;
            }
            columns.put(field, sheet.labels().get(free.get(next++)));
        }
    }

    private static boolean contains(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
