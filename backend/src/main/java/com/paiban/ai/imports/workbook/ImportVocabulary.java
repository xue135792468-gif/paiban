package com.paiban.ai.imports.workbook;

import java.util.Locale;

/**
 * Header words a real school workbook uses for the fields this system writes. Kept in one
 * place so header detection, the deterministic fallback plan and the prompt all agree on
 * what "星期" means. A header that matches nothing is simply not a system column.
 */
public final class ImportVocabulary {

    /** One importable field plus the words schools write for it. */
    public enum Field {
        CLASS_NAME("className", "班级"),
        DAY("dayOfWeek", "星期"),
        PERIOD("periodNo", "节次"),
        COURSE("courseName", "课程"),
        TEACHER("teacherName", "教师"),
        ROOM("roomName", "教室"),
        NAME("name", "姓名"),
        EMPLOYEE_NO("employeeNo", "工号"),
        PHONE("phone", "电话"),
        EMAIL("email", "邮箱"),
        SUBJECT("subject", "学科"),
        STUDENT_COUNT("studentCount", "人数");

        private final String field;
        private final String label;

        Field(String field, String label) {
            this.field = field;
            this.label = label;
        }

        public String field() {
            return field;
        }

        public String label() {
            return label;
        }
    }

    private ImportVocabulary() {}

    /**
     * The field a column header names, or null when it names none. Order matters: a
     * "教师姓名" column is a teacher column, not a generic name column.
     */
    public static Field match(String header) {
        String value = normalize(header);
        if (value.isEmpty()) {
            return null;
        }
        if (contains(value, "班级", "行政班", "班别") || value.equals("班")) {
            return Field.CLASS_NAME;
        }
        if (contains(value, "星期", "礼拜", "周几") || value.equals("周")) {
            return Field.DAY;
        }
        if (contains(value, "节次", "课节") || value.equals("节")) {
            return Field.PERIOD;
        }
        if (contains(value, "课程", "科目")) {
            return Field.COURSE;
        }
        if (contains(value, "教师", "老师", "任课")) {
            return Field.TEACHER;
        }
        if (contains(value, "教室", "场地", "地点")) {
            return Field.ROOM;
        }
        if (contains(value, "姓名") || value.equals("名字")) {
            return Field.NAME;
        }
        if (contains(value, "工号", "编号")) {
            return Field.EMPLOYEE_NO;
        }
        if (contains(value, "电话", "手机")) {
            return Field.PHONE;
        }
        if (contains(value, "邮箱", "邮件")) {
            return Field.EMAIL;
        }
        if (value.equals("学科")) {
            return Field.SUBJECT;
        }
        if (contains(value, "人数", "学生数")) {
            return Field.STUDENT_COUNT;
        }
        return null;
    }

    private static boolean contains(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String header) {
        return header == null ? "" : header.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
