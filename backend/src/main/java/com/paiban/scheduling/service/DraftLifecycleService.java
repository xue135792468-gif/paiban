package com.paiban.scheduling.service;

import com.paiban.common.deletion.DeletionRestoreHandler;
import com.paiban.common.deletion.RecentDeletion;
import com.paiban.common.web.ApiException;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import org.springframework.stereotype.Service;

/**
 * Restore half of ticket 04's draft deletion. The draft row and every lesson are kept
 * while deleted, so reviving means clearing {@code deleted_at}; no lesson is rebuilt.
 */
@Service
public class DraftLifecycleService implements DeletionRestoreHandler {

    static final String ENTITY_TYPE = "TIMETABLE_DRAFT";

    private final TimetableDraftRepository drafts;

    public DraftLifecycleService(TimetableDraftRepository drafts) {
        this.drafts = drafts;
    }

    @Override
    public boolean supports(String entityType) {
        return ENTITY_TYPE.equalsIgnoreCase(entityType);
    }

    @Override
    public void restore(Long entityId) {
        TimetableDraft draft = drafts.findById(entityId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + entityId));
        draft.setDeletedAt(null);
        drafts.save(draft);
    }

    @Override
    public void restore(RecentDeletion deletion) {
        restore(deletion.getEntityId());
    }
}
