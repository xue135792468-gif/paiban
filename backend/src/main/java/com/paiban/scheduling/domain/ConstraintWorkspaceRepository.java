package com.paiban.scheduling.domain;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConstraintWorkspaceRepository extends JpaRepository<ConstraintWorkspace, Long> {

    Optional<ConstraintWorkspace> findByUserIdAndSemesterId(Long userId, Long semesterId);

    /**
     * Advances the workspace only when the device still holds the revision it started
     * from. Zero rows means another device autosaved first.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ConstraintWorkspace w set w.revision = w.revision + 1, "
            + "w.payloadJson = :payload, w.baseVersion = :baseVersion, w.updatedAt = :now "
            + "where w.userId = :userId and w.semesterId = :semesterId and w.revision = :base")
    int updateIfRevision(
            @Param("userId") Long userId,
            @Param("semesterId") Long semesterId,
            @Param("base") long baseRevision,
            @Param("payload") String payload,
            @Param("baseVersion") long baseVersion,
            @Param("now") Instant now);

    void deleteByUserIdAndSemesterId(Long userId, Long semesterId);
}
