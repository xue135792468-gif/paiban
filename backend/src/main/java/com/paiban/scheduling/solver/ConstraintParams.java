package com.paiban.scheduling.solver;

import java.util.Map;

/**
 * Typed accessors for the JSON keys stored in {@code constraint_entry.params_json}.
 * The keys are shared by the checker, the solver, the planner and the normalizer, so
 * they live here instead of being spelled out at each call site.
 */
public final class ConstraintParams {

    public static final String COURSE_ID = "courseId";
    public static final String TEACHER_ID = "teacherId";
    public static final String DAY_OF_WEEK = "dayOfWeek";
    public static final String PERIOD_NO = "periodNo";
    public static final String ROOM_TYPE = "roomType";
    public static final String SUBJECT = "subject";

    private ConstraintParams() {}

    public static Long courseId(Map<String, Object> entry) {
        return ConstraintSettings.longValue(entry.get(COURSE_ID));
    }

    public static Long teacherId(Map<String, Object> entry) {
        return ConstraintSettings.longValue(entry.get(TEACHER_ID));
    }

    public static Integer dayOfWeek(Map<String, Object> entry) {
        return ConstraintSettings.intValue(entry.get(DAY_OF_WEEK));
    }

    public static Integer periodNo(Map<String, Object> entry) {
        return ConstraintSettings.intValue(entry.get(PERIOD_NO));
    }

    public static String roomType(Map<String, Object> entry) {
        Object value = entry.get(ROOM_TYPE);
        return value == null ? null : value.toString();
    }

    public static String subject(Map<String, Object> entry) {
        Object value = entry.get(SUBJECT);
        return value == null ? null : value.toString();
    }
}
