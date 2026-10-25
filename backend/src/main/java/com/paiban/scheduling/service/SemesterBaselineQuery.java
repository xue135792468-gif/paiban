package com.paiban.scheduling.service;

import java.util.Map;
import java.util.Set;

/**
 * Read-only cross-module facts the draft check and the room-fill action need: whether the
 * semester already has a published baseline, and which admin classes that baseline puts in
 * each room. Implemented where publishing lives (the timetable module), so this module
 * never depends on it.
 */
public interface SemesterBaselineQuery {

    boolean hasPublishedBaseline(Long semesterId);

    /**
     * {@code roomId → admin class ids} as published for this semester; empty when there is
     * no baseline. Used to make sure a same-named room is never handed to a second class.
     */
    Map<Long, Set<Long>> publishedRoomOccupants(Long semesterId);
}
