package com.paiban.timetable.service;

import com.paiban.scheduling.service.SemesterBaselineQuery;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import com.paiban.timetable.domain.TimetablePublishRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Answers the scheduling module's baseline question from the publish records it owns. */
@Component
public class TimetableSemesterBaselineQuery implements SemesterBaselineQuery {

    private final TimetablePublishRepository publishes;
    private final TimetableEntryRepository entries;

    public TimetableSemesterBaselineQuery(
            TimetablePublishRepository publishes, TimetableEntryRepository entries) {
        this.publishes = publishes;
        this.entries = entries;
    }

    @Override
    public boolean hasPublishedBaseline(Long semesterId) {
        return publishes.findFirstBySemesterIdOrderByPublishedAtDesc(semesterId).isPresent();
    }

    @Override
    public List<PublishedLesson> publishedLessons(Long semesterId) {
        return List.copyOf(entries.findBySemesterId(semesterId).stream()
                .filter(entry -> entry.getAdminClassId() != null
                        && entry.getCourseId() != null
                        && entry.getTeacherId() != null
                        && entry.getPeriodId() != null)
                .map(entry -> new PublishedLesson(
                        entry.getAdminClassId(),
                        entry.getCourseId(),
                        entry.getTeacherId(),
                        entry.getPeriodId()))
                .toList());
    }

    @Override
    public Map<Long, Set<Long>> publishedRoomOccupants(Long semesterId) {
        Map<Long, Set<Long>> occupants = new HashMap<>();
        for (TimetableEntry entry : entries.findBySemesterId(semesterId)) {
            if (entry.getRoomId() != null && entry.getAdminClassId() != null) {
                occupants.computeIfAbsent(entry.getRoomId(), key -> new HashSet<>())
                        .add(entry.getAdminClassId());
            }
        }
        return occupants;
    }
}
