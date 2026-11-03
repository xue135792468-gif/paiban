package com.paiban.common.deletion;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecentDeletionRepository extends JpaRepository<RecentDeletion, Long> {

    List<RecentDeletion> findByBatchIdOrderByIdAsc(String batchId);

    List<RecentDeletion> findByRestoredAtIsNullAndExpiresAtAfterOrderByDeletedAtDesc(Instant now);

    List<RecentDeletion> findAllByOrderByDeletedAtDesc();

    long deleteByRestoredAtIsNullAndExpiresAtBefore(Instant now);
}
