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
 * The unfinished work of one account in one semester (ticket 14/11): the original text,
 * the run, manual completions, selections and the unsaved table changes. It is never
 * the effective configuration and it is deleted by a successful save.
 */
@Entity
@Table(name = "constraint_workspace")
@Getter
@Setter
@NoArgsConstructor
public class ConstraintWorkspace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(nullable = false)
    private Long revision;

    /** The effective-configuration version the draft is based on. */
    @Column(name = "base_version", nullable = false)
    private Long baseVersion;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
