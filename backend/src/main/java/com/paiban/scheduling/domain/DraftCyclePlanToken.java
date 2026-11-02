package com.paiban.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A one-time preview credential for a cycle plan. The server writes the moves and the
 * fingerprint it was generated under; applying the token re-checks both, so a preview
 * can never be replayed after the draft, rules, settings, locks or assignments changed.
 * {@code usedAt} makes the token single-use: a network retry cannot swap twice.
 */
@Entity
@Table(name = "draft_cycle_plan_token")
@Getter
@Setter
@NoArgsConstructor
public class DraftCyclePlanToken {

    @Id
    @Column(name = "token", nullable = false, length = 64)
    private String token;

    @Column(name = "draft_id", nullable = false)
    private Long draftId;

    @Column(name = "plan_json", nullable = false, length = 4000)
    private String planJson;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_version")
    private Long usedVersion;
}
