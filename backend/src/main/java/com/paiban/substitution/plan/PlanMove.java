package com.paiban.substitution.plan;

import java.time.LocalDate;

/**
 * One lesson moved by a swap cycle. The lesson (course, teacher, room) follows the
 * move; for a swap the original and substitute teacher are always the same person.
 */
public record PlanMove(long entryId,
                       long classId,
                       long courseId,
                       long teacherId,
                       Long roomId,
                       long fromPeriodId,
                       int fromDayOfWeek,
                       int fromPeriodNo,
                       LocalDate fromDate,
                       long toPeriodId,
                       int toDayOfWeek,
                       int toPeriodNo,
                       LocalDate toDate,
                       int order) {}
