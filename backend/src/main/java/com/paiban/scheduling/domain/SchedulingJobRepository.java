package com.paiban.scheduling.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SchedulingJobRepository extends JpaRepository<SchedulingJob, Long> {

    List<SchedulingJob> findBySemesterIdOrderByCreatedAtDesc(Long semesterId);

    Optional<SchedulingJob> findFirstBySemesterIdAndStatusInOrderByCreatedAtAsc(
            Long semesterId, List<String> statuses);

    boolean existsBySemesterIdAndStatusIn(Long semesterId, List<String> statuses);

    /**
     * Claims a queued job for the worker. Returns 0 when the job was cancelled
     * between being queued and being picked up, so a cancel never loses the race
     * against the worker's own status write.
     */
    @Modifying
    @Transactional
    @Query("update SchedulingJob j set j.status = 'RUNNING', j.startedAt = :startedAt "
            + "where j.id = :id and j.status = 'PENDING'")
    int markRunning(@Param("id") Long id, @Param("startedAt") Instant startedAt);

    /**
     * Writes only the warm-start source. The worker re-reads the job after
     * markRunning, but saving that whole entity could race with a concurrent
     * cancel and overwrite CANCELLED back to RUNNING, so this stays a targeted
     * update instead of an entity save.
     */
    @Modifying
    @Transactional
    @Query("update SchedulingJob j set j.warmStartSource = :source where j.id = :id")
    int updateWarmStartSource(@Param("id") Long id, @Param("source") String source);
}
