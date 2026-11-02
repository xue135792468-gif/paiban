package com.paiban.scheduling.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.web.DraftLockDtos.LockResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch lock/unlock of concrete draft lessons. Everything is validated before the
 * first row is touched: one bad id refuses the whole batch instead of locking half of it.
 */
@Service
public class DraftLockService {

    public static final String LOCK = "DRAFT_LESSON_LOCK";
    public static final String UNLOCK = "DRAFT_LESSON_UNLOCK";
    private static final String ENTITY_TYPE = "DRAFT_LESSON";

    private final DraftWriteGuard guard;
    private final DraftLessonRepository draftLessons;
    private final AuditLogRepository auditLogs;

    public DraftLockService(
            DraftWriteGuard guard,
            DraftLessonRepository draftLessons,
            AuditLogRepository auditLogs) {
        this.guard = guard;
        this.draftLessons = draftLessons;
        this.auditLogs = auditLogs;
    }

    @Transactional
    public LockResult setLocked(
            Long draftId, List<Long> lessonIds, boolean locked, Long expectedVersion, String actor) {
        TimetableDraft draft = guard.lockEditable(draftId, expectedVersion);
        if (lessonIds == null || lessonIds.isEmpty()) {
            throw ApiException.badRequest("请选择要锁定的课节");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : lessonIds) {
            if (id == null) {
                throw ApiException.badRequest("课节 id 不能为空");
            }
            ids.add(id);
        }

        Map<Long, DraftLesson> byId = new LinkedHashMap<>();
        draftLessons.findAllById(ids).forEach(lesson -> byId.put(lesson.getId(), lesson));
        List<Long> invalid = new ArrayList<>();
        for (Long id : ids) {
            DraftLesson lesson = byId.get(id);
            if (lesson == null || !draftId.equals(lesson.getDraftId())) {
                invalid.add(id);
            }
        }
        if (!invalid.isEmpty()) {
            throw ApiException.badRequest("非法课节 id: " + invalid);
        }

        List<DraftLesson> lessons = new ArrayList<>(byId.values());
        if (locked) {
            for (DraftLesson lesson : lessons) {
                if (lesson.getPeriodId() == null) {
                    throw ApiException.badRequest("未排课的课节不能锁定: " + lesson.getId());
                }
            }
        }

        List<DraftLesson> changed = new ArrayList<>();
        for (DraftLesson lesson : lessons) {
            if (lesson.isLocked() != locked) {
                lesson.setLocked(locked);
                changed.add(lesson);
            }
        }
        int updated = changed.size();
        if (updated > 0) {
            draftLessons.saveAll(changed);
            guard.bump(draft);
            auditLogs.save(new AuditLog(
                    actor == null ? "system" : actor,
                    locked ? LOCK : UNLOCK,
                    ENTITY_TYPE,
                    "draftId=" + draftId + ", lessonIds=" + ids,
                    updated,
                    0));
        }
        return new LockResult(ids.size(), updated, locked, draft.getVersion());
    }
}
