package com.paiban.foundation.domain;

/**
 * The identity of one timetable slot: a {@code (dayOfWeek, periodNo)} pair, keyed the same
 * way everywhere. Saving the period table, importing lessons and comparing a workbook's
 * slots against the semester all have to agree on what "the same slot" means, so the
 * concatenation lives here and nowhere else.
 */
public final class PeriodSlotKey {

    private PeriodSlotKey() {}

    /** {@code "3-2"} for Wednesday's second period; usable as a {@link java.util.Map} key. */
    public static String of(int dayOfWeek, int periodNo) {
        return dayOfWeek + "-" + periodNo;
    }
}
