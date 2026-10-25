package com.paiban.timetable.service;

import com.paiban.scheduling.service.SemesterBaselineQuery;
import com.paiban.timetable.domain.TimetablePublishRepository;
import org.springframework.stereotype.Component;

/** Answers the scheduling module's baseline question from the publish records it owns. */
@Component
public class TimetableSemesterBaselineQuery implements SemesterBaselineQuery {

    private final TimetablePublishRepository publishes;

    public TimetableSemesterBaselineQuery(TimetablePublishRepository publishes) {
        this.publishes = publishes;
    }

    @Override
    public boolean hasPublishedBaseline(Long semesterId) {
        return publishes.findFirstBySemesterIdOrderByPublishedAtDesc(semesterId).isPresent();
    }
}
