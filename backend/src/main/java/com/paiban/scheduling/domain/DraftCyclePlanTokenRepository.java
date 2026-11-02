package com.paiban.scheduling.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DraftCyclePlanTokenRepository extends JpaRepository<DraftCyclePlanToken, String> {

    /** Serialises concurrent applies of the same token so a retry cannot swap twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DraftCyclePlanToken t where t.token = :token")
    Optional<DraftCyclePlanToken> findWithLockByToken(@Param("token") String token);

    long deleteByExpiresAtBefore(Instant cutoff);
}
