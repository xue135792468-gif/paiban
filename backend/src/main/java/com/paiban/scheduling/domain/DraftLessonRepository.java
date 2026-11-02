package com.paiban.scheduling.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftLessonRepository extends JpaRepository<DraftLesson, Long> {

    List<DraftLesson> findByDraftIdOrderByIdAsc(Long draftId);

    /** Every lesson of the given drafts in one query, ordered by draft then row id. */
    List<DraftLesson> findByDraftIdInOrderByDraftIdAscIdAsc(Collection<Long> draftIds);

    /** Every lesson of the given drafts, for semester-wide questions (e.g. room use). */
    List<DraftLesson> findByDraftIdIn(List<Long> draftIds);

    long countByDraftId(Long draftId);

    /** Number of locked lessons in one draft, used before deleting the draft. */
    long countByDraftIdAndLockedTrue(Long draftId);

    long countByAdminClassId(Long adminClassId);

    /** Number of draft lessons still pointing at any of these period rows. */
    long countByPeriodIdIn(List<Long> periodIds);

    void deleteByDraftId(Long draftId);
}
