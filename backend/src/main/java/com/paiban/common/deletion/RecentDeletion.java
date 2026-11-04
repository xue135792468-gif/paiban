package com.paiban.common.deletion;

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
 * One row per deleted entity. A single “一起删” writes one row per entity sharing a
 * {@code batchId}; restoring the batch revives all of them. The row is kept for 7
 * days after deletion; a scheduled purge removes only this marker, never the
 * business row (physical cleanup is a later ticket).
 */
@Entity
@Table(name = "recent_deletion")
@Getter
@Setter
@NoArgsConstructor
public class RecentDeletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, length = 36)
    private String batchId;

    @Column(name = "entity_type", nullable = false, length = 32)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "entity_label", nullable = false, length = 255)
    private String entityLabel;

    @Column(name = "primary_flag", nullable = false)
    private boolean primaryFlag;

    @Column(name = "deleted_by", nullable = false, length = 64)
    private String deletedBy;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * The entity's {@code active} flag right before deletion, so restoring a disabled
     * object leaves it disabled. NULL for rows written before this column existed.
     */
    @Column(name = "was_active")
    private Boolean wasActive;

    /**
     * Minimal state a restore cannot recompute (rule-card entry ids, AI model ids,
     * the DingTalk key values). Recycle-bin data only: it may hold a secret and must
     * never be copied into an audit detail.
     */
    @Column(name = "snapshot_json", length = 65535)
    private String snapshotJson;

    @Column(name = "restored_at")
    private Instant restoredAt;

    @Column(name = "restored_by", length = 64)
    private String restoredBy;
}
