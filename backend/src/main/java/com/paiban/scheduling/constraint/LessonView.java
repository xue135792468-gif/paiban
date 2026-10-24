package com.paiban.scheduling.constraint;

/**
 * One lesson as seen by the shared constraint engine. Deliberately free of JPA
 * and draft concepts so a published timetable snapshot can be validated with the
 * same rules as a draft.
 */
public record LessonView(long id,
                         Long classId,
                         Long courseId,
                         Long teacherId,
                         Long roomId,
                         Long periodId,
                         int studentCount) {}
