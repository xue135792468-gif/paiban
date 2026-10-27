package com.paiban.scheduling.domain;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConstraintConfigStateRepository extends JpaRepository<ConstraintConfigState, Long> {

    /** The committed version, read with a scalar query so it bypasses the entity cache. */
    @Query("select s.version from ConstraintConfigState s where s.semesterId = :semesterId")
    Long findVersionById(@Param("semesterId") Long semesterId);

    /**
     * Advances the version only if it is still {@code base}. Zero rows means another
     * writer moved it first (or the row does not exist yet).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ConstraintConfigState s set s.version = s.version + 1, s.updatedAt = :now "
            + "where s.semesterId = :semesterId and s.version = :base")
    int bump(@Param("semesterId") Long semesterId, @Param("base") long base,
             @Param("now") Instant now);
}
