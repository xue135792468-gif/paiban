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
 * One old configuration row's migration decision (ticket 11). A config-level decision
 * stores {@code oldEntryId = 0}; an entry-level decision stores the entry's id. The
 * unique key {@code (semesterId, oldType, oldEntryId)} makes a second run update the
 * same row instead of appending a duplicate.
 */
@Entity
@Table(name = "constraint_migration_map")
@Getter
@Setter
@NoArgsConstructor
public class ConstraintMigrationMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(name = "old_type", nullable = false, length = 64)
    private String oldType;

    @Column(name = "old_entry_id", nullable = false)
    private Long oldEntryId = 0L;

    /** The type that replaces the old one; null when the old type is disabled. */
    @Column(name = "new_type", length = 64)
    private String newType;

    /** RETAIN / CONVERT / DISABLE / REVIEW. */
    @Column(nullable = false, length = 16)
    private String action;

    @Column(name = "group_key", length = 64)
    private String groupKey;

    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    @Column(length = 500)
    private String note;

    @Column(name = "requires_review", nullable = false)
    private boolean requiresReview;

    @Column(name = "migrated_at", nullable = false)
    private Instant migratedAt = Instant.now();
}
