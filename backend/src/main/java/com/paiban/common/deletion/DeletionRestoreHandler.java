package com.paiban.common.deletion;

/**
 * Restore seam for entity families outside {@link EntityType}. Ticket 03's
 * {@code LifecycleService} owns the 7-day recycle bin; a module that adds a new
 * soft-deletable entity implements this interface so {@code restore(batchId)}
 * can revive it without teaching the lifecycle switch about every table.
 *
 * <p>{@code entityType} is the stored {@code recent_deletion.entity_type} value.
 */
public interface DeletionRestoreHandler {

    boolean supports(String entityType);

    void restore(Long entityId);

    /**
     * Restore with the recycle row, so a handler can read the snapshot it wrote at
     * deletion time (rule-card entry ids, DingTalk key values). Handlers that only
     * need the id keep implementing {@link #restore(Long)}.
     */
    default void restore(RecentDeletion deletion) {
        restore(deletion.getEntityId());
    }
}
