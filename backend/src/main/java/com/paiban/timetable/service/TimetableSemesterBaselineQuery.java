package com.paiban.timetable.service;

import com.paiban.scheduling.service.SemesterBaselineQuery;
import com.paiban.timetable.domain.TimetableEntry;
import com.paiban.timetable.domain.TimetableEntryRepository;
import com.paiban.timetable.domain.TimetablePublish;
import com.paiban.timetable.domain.TimetablePublishRepository;
import java.util.List;
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
        // A withdrawn publish row is history, not a live baseline.
        return publishes.findFirstBySemesterIdAndStatusOrderByPublishedAtDesc(
                semesterId, TimetablePublish.ACTIVE).isPresent();
    }

    @Override
    public List<PublishedLesson> publishedLessons(Long semesterId) {
        if (!hasPublishedBaseline(semesterId)) {
            return List.of();
        }
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
}
