package com.paiban.scheduling.service;

import com.paiban.common.web.ApiException;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import org.springframework.stereotype.Component;

/**
 * The one gate every draft write goes through. It takes the draft row lock, refuses
 * non-editable drafts and stale versions, and bumps the shared optimistic version
 * once a write really happened. Locking and the version live here so no entry point
 * can "check first and write later" without protection.
 */
@Component
public class DraftWriteGuard {

    private final TimetableDraftRepository drafts;

    public DraftWriteGuard(TimetableDraftRepository drafts) {
        this.drafts = drafts;
    }

    /**
     * Locks the draft row for the rest of the transaction and returns the managed entity.
     * {@code expectedVersion} is optional: an old client that never sent one keeps working,
     * while a client that did gets a 409 instead of silently overwriting someone else.
     */
    public TimetableDraft lockEditable(Long draftId, Long expectedVersion) {
        TimetableDraft draft = drafts.findWithLockById(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        String status = draft.getStatus();
        if (!"DRAFT".equals(status)) {
            if ("PUBLISHED".equals(status)) {
                throw ApiException.badRequest("课表已发布，发布后请走调代流程");
            }
            if ("ARCHIVED".equals(status)) {
                throw ApiException.badRequest("课表已归档，不能再换课");
            }
            throw ApiException.badRequest("当前课表状态不允许换课");
        }
        if (expectedVersion != null && expectedVersion != draft.getVersion()) {
            throw ApiException.conflict("课表已被其他人修改，请刷新后重试");
        }
        return draft;
    }

    /** A locked lesson fixes its period and teacher, whatever the action is called. */
    public void requireUnlocked(DraftLesson lesson, String action) {
        if (lesson.isLocked()) {
            throw ApiException.conflict("该课节已锁定，请先解锁后再" + action);
        }
    }

    public void bump(TimetableDraft draft) {
        draft.setVersion(draft.getVersion() + 1);
    }
}
