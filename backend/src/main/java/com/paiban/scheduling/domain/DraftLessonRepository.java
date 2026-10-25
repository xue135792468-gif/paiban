package com.paiban.scheduling.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftLessonRepository extends JpaRepository<DraftLesson, Long> {

    List<DraftLesson> findByDraftIdOrderByIdAsc(Long draftId);

    long countByDraftId(Long draftId);

    long countByAdminClassId(Long adminClassId);

    /** Number of draft lessons still pointing at any of these period rows. */
    long countByPeriodIdIn(List<Long> periodIds);

    void deleteByDraftId(Long draftId);
}
