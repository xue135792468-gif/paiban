package com.paiban.common.event;

/**
 * Published after a semester's period table was created, replaced or appended to.
 * Listeners keep derived data (the prebuilt 「上午」/「下午」 time groups) in sync with
 * the slots that actually exist. The listener runs inside the saving transaction, so a
 * failure rolls the timetable save back instead of leaving the groups stale.
 */
public record PeriodsChangedEvent(Long semesterId) {}
