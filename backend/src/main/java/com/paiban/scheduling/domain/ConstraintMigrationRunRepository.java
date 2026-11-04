package com.paiban.scheduling.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConstraintMigrationRunRepository
        extends JpaRepository<ConstraintMigrationRun, Long> {

    List<ConstraintMigrationRun> findBySemesterIdOrderByIdDesc(Long semesterId);

    Optional<ConstraintMigrationRun> findFirstBySemesterIdOrderByIdDesc(Long semesterId);
}
