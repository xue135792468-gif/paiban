package com.paiban.common.deletion;

import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Writes one 7-day recycle-bin row plus the matching delete audit for entity
 * families that live outside {@code LifecycleService}'s switch (for example
 * {@code IMPORT_RECIPE}). Restore is handled by a {@link DeletionRestoreHandler}.
 */
@Component
public class RecentDeletionWriter {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final RecentDeletionRepository recentDeletions;
    private final AuditLogRepository auditLogs;
    private final Clock clock;

    public RecentDeletionWriter(
            RecentDeletionRepository recentDeletions,
            AuditLogRepository auditLogs,
            Clock clock) {
        this.recentDeletions = recentDeletions;
        this.auditLogs = auditLogs;
        this.clock = clock;
    }

    /**
     * Records one deleted entity. Returns the batch id; the caller is responsible for
     * stamping the business row's soft-delete column inside the same transaction.
     */
    public String record(String entityType, Long entityId, String label, String kindLabel) {
        Instant now = Instant.now(clock);
        String batchId = UUID.randomUUID().toString();
        RecentDeletion deletion = new RecentDeletion();
        deletion.setBatchId(batchId);
        deletion.setEntityType(entityType);
        deletion.setEntityId(entityId);
        deletion.setEntityLabel(label);
        deletion.setPrimaryFlag(true);
        deletion.setDeletedBy(actor());
        deletion.setDeletedAt(now);
        deletion.setExpiresAt(now.plus(RETENTION));
        recentDeletions.save(deletion);
        auditLogs.save(new AuditLog(
                actor(), "DELETE_" + entityType, entityType,
                "删除" + kindLabel + "「" + label + "」（批次 " + batchId + "）", 1, 0));
        return batchId;
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "system";
        }
        String name = authentication.getName();
        return StringUtils.hasText(name) ? name : "system";
    }
}
