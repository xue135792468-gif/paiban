package com.paiban.scheduling.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchedulingJobRepository extends JpaRepository<SchedulingJob, Long> {

    List<SchedulingJob> findBySemesterIdOrderByCreatedAtDesc(Long semesterId);

    Optional<SchedulingJob> findFirstBySemesterIdAndStatusInOrderByCreatedAtAsc(
            Long semesterId, List<String> statuses);

    boolean existsBySemesterIdAndStatusIn(Long semesterId, List<String> statuses);
}
