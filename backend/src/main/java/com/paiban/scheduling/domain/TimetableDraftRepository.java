package com.paiban.scheduling.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimetableDraftRepository extends JpaRepository<TimetableDraft, Long> {

    List<TimetableDraft> findBySemesterIdOrderByCreatedAtDesc(Long semesterId);

    Optional<TimetableDraft> findBySemesterIdAndStatus(Long semesterId, String status);

    void deleteBySemesterId(Long semesterId);
}
