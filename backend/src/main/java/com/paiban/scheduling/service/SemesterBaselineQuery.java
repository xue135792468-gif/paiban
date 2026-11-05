package com.paiban.scheduling.service;

import java.util.List;

/**
 * Read-only cross-module facts the draft check needs: whether the semester already has a
 * published baseline, and which lessons that baseline contains. Implemented where
 * publishing lives (the timetable module), so this module never depends on it.
 */
public interface SemesterBaselineQuery {

    /**
     * One published lesson, just enough for the scheduling module to seed a warm start.
     * This is the scheduling module's only port for reading the published baseline; it
     * exists so this module never depends on the timetable repositories directly.
     */
    record PublishedLesson(
            Long adminClassId, Long courseId, Long teacherId, Long periodId) {}

    boolean hasPublishedBaseline(Long semesterId);

    /** Every published lesson of this semester; empty when there is no baseline. */
    List<PublishedLesson> publishedLessons(Long semesterId);
}
