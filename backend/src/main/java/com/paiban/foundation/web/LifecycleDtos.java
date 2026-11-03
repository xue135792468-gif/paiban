package com.paiban.foundation.web;

import java.time.Instant;

public final class LifecycleDtos {

    private LifecycleDtos() {}

    /**
     * What a delete would touch. {@code publishedRefs}/{@code substitutionRefs} are
     * hard blockers (disable only); the remaining counts are the “一起删 / 改为停用”
     * choice. Cascade counts say how many dependants a CASCADE delete would take.
     */
    public record DeleteImpactDto(
            String entityType,
            Long entityId,
            String label,
            long publishedRefs,
            long substitutionRefs,
            long draftRefs,
            long assignmentRefs,
            long ruleRefs,
            boolean canDelete,
            boolean canDisable,
            long cascadeAssignments,
            long cascadeClasses) {}

    public record DeleteResultDto(String batchId, int deletedCount) {}

    public record RestoreResultDto(String batchId, int restoredCount) {}

    public record RecentDeletionDto(
            Long id,
            String batchId,
            String entityType,
            Long entityId,
            String entityLabel,
            boolean primary,
            String deletedBy,
            Instant deletedAt,
            Instant expiresAt,
            Instant restoredAt,
            String restoredBy,
            String status,
            long daysLeft) {}
}
