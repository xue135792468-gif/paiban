package com.paiban.scheduling.constraint;

/**
 * A rule hit. {@code code} is the {@code ConstraintType} name for preset rules so
 * the API can expose a stable identifier; the message is what teachers read.
 *
 * <p>{@code key} is the stable identity used to compare a "before" and an "after"
 * judgement. It must not contain names or other wording that changes between two runs
 * of the same fact: lesson-scoped rules use {@code code|L|lessonId}, pair conflicts add
 * the other lesson's id, and aggregate rules use an explicit scope such as
 * {@code SAME_COURSE_PER_DAY|class-course-day}. {@code weight} is the severity of that
 * fact: one pair conflict is 1, while an aggregate records how many lessons or gaps
 * produce it (three lessons on one day weigh 3). A change may keep a key but must not
 * raise its weight.
 */
public record ConstraintViolation(String code, String message, Long lessonId, boolean hard, String key,
                                  int weight) {

    public ConstraintViolation(String code, String message, Long lessonId, boolean hard, String key) {
        this(code, message, lessonId, hard, key, 1);
    }

    public ConstraintViolation(String code, String message, Long lessonId, boolean hard) {
        this(code, message, lessonId, hard, defaultKey(code, lessonId), 1);
    }

    /** The stable identity of a lesson-scoped fact; pair/aggregate rules pass their own key. */
    static String defaultKey(String code, Long lessonId) {
        return lessonId == null ? code + "|G" : code + "|L|" + lessonId;
    }

    /** A pair fact whose identity includes the other lesson, in a stable order. */
    static String pairKey(String code, Long first, Long second) {
        long low = Math.min(first, second);
        long high = Math.max(first, second);
        return code + "|L|" + low + "|O|" + high;
    }
}
