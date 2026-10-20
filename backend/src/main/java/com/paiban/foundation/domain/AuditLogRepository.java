package com.paiban.foundation.domain;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    java.util.List<AuditLog> findTop100ByOrderByCreatedAtDesc();
}
