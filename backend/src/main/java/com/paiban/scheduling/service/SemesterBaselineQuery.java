package com.paiban.scheduling.service;

/**
 * Read-only cross-module fact the draft check needs: whether the semester already has a
 * published baseline. Implemented where publishing lives (the timetable module), so this
 * module never depends on it.
 */
public interface SemesterBaselineQuery {

    boolean hasPublishedBaseline(Long semesterId);
}
