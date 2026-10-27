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
 * One successful effective-configuration save (ticket 14/10): the version it produced,
 * the user's decisions about the proposals and the final configuration exactly as it
 * was written. The generation run keeps the raw model answer; this row keeps what the
 * person confirmed.
 */
@Entity
@Table(name = "constraint_save_audit")
@Getter
@Setter
@NoArgsConstructor
public class ConstraintSaveAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(nullable = false)
    private Long version;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 100)
    private String actor;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "decisions_json", columnDefinition = "TEXT")
    private String decisionsJson;

    @Column(name = "final_config_json", nullable = false, columnDefinition = "TEXT")
    private String finalConfigJson;

    @Column(name = "previous_config_json", columnDefinition = "TEXT")
    private String previousConfigJson;

    /** The structural effective change, computed server-side from before/after. */
    @Column(name = "diff_json", columnDefinition = "TEXT")
    private String diffJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
