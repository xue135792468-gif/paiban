package com.paiban.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One execution of the 26-to-19 constraint migration (ticket 11). The run row keeps
 * the before/after draft-check snapshot so the migration has a comparison record even
 * after the old rows have been paused.
 */
@Entity
@Table(name = "constraint_migration_run")
@Getter
@Setter
@NoArgsConstructor
public class ConstraintMigrationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, unique = true, length = 36)
    private String runId;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    /** RUNNING while the migration writes; DONE once the report is complete. */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "run_by", nullable = false, length = 64)
    private String runBy;

    /** Draft-check comparison before the migration: counts, fingerprint, violations, penalty. */
    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    /** Same comparison after the migration. */
    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    /** Retained / converted / disabled / review counters. */
    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;
}
