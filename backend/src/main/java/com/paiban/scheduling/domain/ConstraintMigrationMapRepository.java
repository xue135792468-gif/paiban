package com.paiban.scheduling.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConstraintMigrationMapRepository
        extends JpaRepository<ConstraintMigrationMap, Long> {

    List<ConstraintMigrationMap> findBySemesterIdOrderByOldTypeAscOldEntryIdAsc(Long semesterId);

    List<ConstraintMigrationMap> findByRunIdOrderByOldTypeAscOldEntryIdAsc(String runId);

    Optional<ConstraintMigrationMap> findBySemesterIdAndOldTypeAndOldEntryId(
            Long semesterId, String oldType, Long oldEntryId);
}
