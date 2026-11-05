package com.paiban.timetable.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimetablePublishRepository extends JpaRepository<TimetablePublish, Long> {

    Optional<TimetablePublish> findFirstBySemesterIdOrderByPublishedAtDesc(Long semesterId);

    Optional<TimetablePublish> findFirstBySemesterIdAndStatusOrderByPublishedAtDesc(
            Long semesterId, String status);

    Optional<TimetablePublish> findFirstBySemesterIdAndDraftIdAndStatusOrderByPublishedAtDesc(
            Long semesterId, Long draftId, String status);

    /** Every row currently claiming to be the semester's baseline (should be at most one). */
    List<TimetablePublish> findBySemesterIdAndStatus(Long semesterId, String status);

    List<TimetablePublish> findBySemesterIdOrderByPublishedAtDesc(Long semesterId);
}
