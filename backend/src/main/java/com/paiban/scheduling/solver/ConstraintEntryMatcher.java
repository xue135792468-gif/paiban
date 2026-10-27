package com.paiban.scheduling.solver;

import java.util.Map;
import java.util.Objects;
import org.springframework.util.StringUtils;

/**
 * The one place that decides whether a configured constraint entry matches a lesson,
 * teacher, course, day or period. The checker, the solver provider and the substitution
 * planner all read the same entry maps; keeping the predicates here stops the three
 * copies from drifting apart as the catalogue grows.
 */
public final class ConstraintEntryMatcher {

    private ConstraintEntryMatcher() {}

    /** The entry names exactly this course (entries that require a target course). */
    public static boolean course(Map<String, Object> entry, Long courseId) {
        return Objects.equals(ConstraintParams.courseId(entry), courseId);
    }

    /** A blank course means every course; otherwise it must be this one. */
    public static boolean courseOrAll(Map<String, Object> entry, Long courseId) {
        Long configured = ConstraintParams.courseId(entry);
        return configured == null || configured.equals(courseId);
    }

    /** A blank course means “sport classes”; otherwise the configured course. */
    public static boolean courseOrSport(
            Map<String, Object> entry, Long courseId, boolean lessonIsSport) {
        Long configured = ConstraintParams.courseId(entry);
        return configured != null ? configured.equals(courseId) : lessonIsSport;
    }

    public static boolean teacher(Map<String, Object> entry, Long teacherId) {
        return Objects.equals(ConstraintParams.teacherId(entry), teacherId);
    }

    public static boolean day(Map<String, Object> entry, int dayOfWeek) {
        return Objects.equals(ConstraintParams.dayOfWeek(entry), dayOfWeek);
    }

    public static boolean period(Map<String, Object> entry, int periodNo) {
        return Objects.equals(ConstraintParams.periodNo(entry), periodNo);
    }

    public static boolean slot(Map<String, Object> entry, int dayOfWeek, int periodNo) {
        return day(entry, dayOfWeek) && period(entry, periodNo);
    }

    public static boolean teacherSlot(
            Map<String, Object> entry, Long teacherId, int dayOfWeek, int periodNo) {
        return teacher(entry, teacherId) && slot(entry, dayOfWeek, periodNo);
    }

    public static boolean courseSlot(
            Map<String, Object> entry, Long courseId, int dayOfWeek, int periodNo) {
        return course(entry, courseId) && slot(entry, dayOfWeek, periodNo);
    }

    public static boolean dayOff(Map<String, Object> entry, Long teacherId, int dayOfWeek) {
        return teacher(entry, teacherId) && day(entry, dayOfWeek);
    }

    /** The entry also carries the room type its course must use. */
    public static boolean courseWithRoomType(Map<String, Object> entry, Long courseId) {
        return course(entry, courseId) && ConstraintParams.roomType(entry) != null;
    }

    /** A blank subject means every teacher; otherwise the teacher's subject must match. */
    public static boolean meetingSubject(Map<String, Object> entry, String teacherSubject) {
        String subject = ConstraintParams.subject(entry);
        if (!StringUtils.hasText(subject)) {
            return true;
        }
        return teacherSubject != null && subject.equalsIgnoreCase(teacherSubject);
    }
}
