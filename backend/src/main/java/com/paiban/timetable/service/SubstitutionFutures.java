package com.paiban.timetable.service;

import com.paiban.substitution.domain.Substitution;
import java.time.LocalDate;

/**
 * The one answer to "would this overlay row still apply after today?".
 *
 * <p>Withdrawing the baseline deletes the {@code timetable_entry} every substitution
 * points at, so a row that still drives a future lesson loses its anchor. Publishing
 * afterwards counts the rows the withdrawal cancelled, so both sides must judge
 * "future" the same way — hence one shared predicate.
 *
 * <p>New-format rows carry the instance dates they cover. Legacy rows have a NULL
 * {@code lesson_date} and mean "from {@code effective_date} onward": the start date
 * is their only date, and a row that starts today or later is still ahead of us.
 */
final class SubstitutionFutures {

    private SubstitutionFutures() {}

    static boolean affectsFuture(Substitution row, LocalDate today) {
        if (after(row.getEffectiveDate(), today)
                || after(row.getLessonDate(), today)
                || after(row.getTargetLessonDate(), today)) {
            return true;
        }
        return row.getLessonDate() == null
                && (row.getEffectiveDate() == null || !row.getEffectiveDate().isBefore(today));
    }

    private static boolean after(LocalDate date, LocalDate today) {
        return date != null && date.isAfter(today);
    }
}
