package com.paiban.scheduling.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConstraintSaveAuditRepository extends JpaRepository<ConstraintSaveAudit, Long> {

    List<ConstraintSaveAudit> findBySemesterIdOrderByCreatedAtDesc(
            Long semesterId, Pageable pageable);

    List<ConstraintSaveAudit> findBySemesterIdAndUserIdOrderByCreatedAtDesc(
            Long semesterId, Long userId, Pageable pageable);
}
