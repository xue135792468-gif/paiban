package com.paiban.scheduling.constraint;

/**
 * A rule hit. {@code code} is the {@code ConstraintType} name for preset rules so
 * the API can expose a stable identifier; the message is what teachers read.
 *
 * <p>{@code key} is the stable identity used to compare a "before" and an "after"
 * judgement. It must not contain names, counts or other wording that changes between
 * two runs of the same fact: lesson-scoped rules use {@code code|L|lessonId}, aggregate
 * rules use an explicit scope such as {@code SAME_COURSE_PER_DAY|class-course-day}.
 * The same key appearing more often after a change means the fact got worse.
 */
public record ConstraintViolation(String code, String message, Long lessonId, boolean hard, String key) {

    public ConstraintViolation(String code, String message, Long lessonId, boolean hard) {
        this(code, message, lessonId, hard, defaultKey(code, lessonId));
    }

    /** The stable identity of a lesson-scoped fact; aggregates must pass their own key. */
    static String defaultKey(String code, Long lessonId) {
        return lessonId == null ? code + "|G" : code + "|L|" + lessonId;
    }
}
