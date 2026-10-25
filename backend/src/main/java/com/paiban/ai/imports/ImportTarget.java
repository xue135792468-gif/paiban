package com.paiban.ai.imports;

import java.util.List;

/**
 * Target shapes the import pipeline can write into. {@link Kind#ENTITY} targets are
 * flat lists of objects (teachers today); {@link Kind#GRID} targets are one row per
 * timetable slot. Adding a target means adding one enum value and its persistence
 * hook in {@link AiImportService}, leaving the AI prompt and preview format unchanged.
 * <p>{@link #WORKBOOK} is the phase-11 entry: the upload does not name a target, the
 * model decides which sheets are timetables, teacher lists or course lists, and one
 * confirmed commit writes every category.
 */
public enum ImportTarget {

    TEACHER("teacher", Kind.ENTITY, List.of(
            new Field("name", "姓名", true),
            new Field("employeeNo", "工号", false),
            new Field("phone", "电话", false),
            new Field("email", "邮箱", false),
            new Field("subject", "学科", false))),

    TIMETABLE("timetable", Kind.GRID, gridFields()),

    WORKBOOK("workbook", Kind.GRID, gridFields());

    public enum Kind {
        /** One row is one object; preview renders a table. */
        ENTITY,
        /** One row is one timetable slot; preview renders a grid. */
        GRID
    }

    public record Field(String field, String label, boolean required) {}

    private final String code;
    private final Kind kind;
    private final List<Field> fields;

    ImportTarget(String code, Kind kind, List<Field> fields) {
        this.code = code;
        this.kind = kind;
        this.fields = fields;
    }

    /** The timetable slot shape: a workbook preview and a timetable import share it. */
    private static List<Field> gridFields() {
        return List.of(
                new Field("className", "班级", true),
                new Field("dayOfWeek", "星期", true),
                new Field("periodNo", "节次", true),
                new Field("courseName", "课程", true),
                new Field("teacherName", "教师", true),
                new Field("roomName", "教室", false));
    }

    public String code() {
        return code;
    }

    public Kind kind() {
        return kind;
    }

    public List<Field> fields() {
        return fields;
    }

    public List<String> fieldNames() {
        return fields.stream().map(Field::field).toList();
    }

    /** Resolves the API's target code; blank means the teacher target. */
    public static ImportTarget fromCode(String code) {
        if (code == null || code.isBlank()) {
            return TEACHER;
        }
        String normalized = code.trim();
        for (ImportTarget target : values()) {
            if (target.code.equalsIgnoreCase(normalized)) {
                return target;
            }
        }
        throw new IllegalArgumentException("不支持的导入目标: " + code);
    }

    /**
     * The canonical code when {@code code} names a known target, otherwise an empty
     * string. Used for AI suggestions, where an unknown answer is not an error.
     */
    public static String codeOrEmpty(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String normalized = code.trim();
        for (ImportTarget target : values()) {
            if (target.code.equalsIgnoreCase(normalized)) {
                return target.code;
            }
        }
        return "";
    }

    /** The display label of a field, falling back to the field name itself. */
    public String labelOf(String field) {
        return fields.stream()
                .filter(candidate -> candidate.field().equals(field))
                .map(Field::label)
                .findFirst()
                .orElse(field);
    }
}
