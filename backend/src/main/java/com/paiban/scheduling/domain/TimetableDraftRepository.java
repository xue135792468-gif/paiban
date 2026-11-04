package com.paiban.scheduling.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimetableDraftRepository extends JpaRepository<TimetableDraft, Long> {

    /**
     * Live drafts only: a draft in the 7-day recycle bin must disappear from every
     * list, warm-start seed, room-fill plan and publish side effect (ticket 04).
     */
    @Query("select d from TimetableDraft d where d.semesterId = :semesterId "
            + "and d.deletedAt is null order by d.createdAt desc")
    List<TimetableDraft> findBySemesterIdOrderByCreatedAtDesc(@Param("semesterId") Long semesterId);

    @Query("select d from TimetableDraft d where d.semesterId = :semesterId "
            + "and d.deletedAt is null and d.status = :status")
    Optional<TimetableDraft> findBySemesterIdAndStatus(
            @Param("semesterId") Long semesterId, @Param("status") String status);

    /**
     * Serialises every write to one draft: the row lock is held until the surrounding
     * transaction commits, so two edits cannot both read the same version and write.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from TimetableDraft d where d.id = :id")
    Optional<TimetableDraft> findWithLockById(@Param("id") Long id);

    void deleteBySemesterId(Long semesterId);
}
