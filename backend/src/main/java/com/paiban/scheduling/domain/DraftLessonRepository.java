package com.paiban.scheduling.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftLessonRepository extends JpaRepository<DraftLesson, Long> {

    List<DraftLesson> findByDraftIdOrderByIdAsc(Long draftId);

    long countByDraftId(Long draftId);

    void deleteByDraftId(Long draftId);
}
