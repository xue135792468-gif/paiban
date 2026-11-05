package com.paiban.scheduling.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.web.DraftChangeDtos.ChangeRequest;
import com.paiban.scheduling.web.DraftChangeDtos.ChangeResult;
import com.paiban.scheduling.web.DraftChangeDtos.LessonChangeItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * One atomic editor action: apply an entire list of lesson moves together, or write
 * nothing at all. Every check (draft state, version, rules fingerprint, source values,
 * locks, references) runs before the first row changes, and the before/after rule
 * comparison looks at the whole draft after every change has been applied. A change
 * that repairs an old conflict is allowed while other old conflicts remain; a change
 * that adds a new one or makes an existing one worse is refused.
 */
@Service
public class DraftChangeService {

    private final DraftWriteGuard guard;
    private final DraftValidator validator;
    private final ConstraintService constraints;
    private final DraftLessonRepository draftLessons;
    private final PeriodRepository periods;
    private final TeacherRepository teachers;
    private final AuditLogRepository auditLogs;

    public DraftChangeService(
            DraftWriteGuard guard,
            DraftValidator validator,
            ConstraintService constraints,
            DraftLessonRepository draftLessons,
            PeriodRepository periods,
            TeacherRepository teachers,
            AuditLogRepository auditLogs) {
        this.guard = guard;
        this.validator = validator;
        this.constraints = constraints;
        this.draftLessons = draftLessons;
        this.periods = periods;
        this.teachers = teachers;
        this.auditLogs = auditLogs;
    }

    /** Plan application and internal callers have no request principal; keep them working. */
    @Transactional
    public ChangeResult apply(Long draftId, ChangeRequest request) {
        return apply(draftId, request, "system");
    }

    @Transactional
    public ChangeResult apply(Long draftId, ChangeRequest request, String actor) {
        TimetableDraft draft = guard.lockEditable(draftId, request.expectedVersion());
        List<LessonChangeItem> items = request.changes();
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("请至少提交一节课的变更");
        }
        if (StringUtils.hasText(request.rulesFingerprint())) {
            String current = constraints.settings(draft.getSemesterId()).fingerprint();
            if (!current.equals(request.rulesFingerprint())) {
                throw ApiException.conflict("课表规则已变化，请刷新后重试");
            }
        }

        Map<Long, DraftLesson> byId = resolveLessons(draftId, items);
        validateTargets(draft, items);
        if (Boolean.TRUE.equals(request.verifySource())) {
            verifySource(byId, items);
        }

        List<DraftLesson> changed = new ArrayList<>();
        List<DraftValidator.LessonChange> changes = new ArrayList<>();
        for (LessonChangeItem item : items) {
            DraftLesson lesson = byId.get(item.lessonId());
            Long periodId = item.toPeriodId();
            Long teacherId = item.toTeacherId() == null ? lesson.getTeacherId() : item.toTeacherId();
            boolean movesPeriodOrTeacher = !Objects.equals(periodId, lesson.getPeriodId())
                    || !Objects.equals(teacherId, lesson.getTeacherId());
            if (lesson.isLocked() && movesPeriodOrTeacher) {
                guard.requireUnlocked(lesson, "调整");
            }
            changes.add(new DraftValidator.LessonChange(lesson.getId(), periodId, teacherId));
            changed.add(lesson);
        }

        DraftValidator.ChangeAssessment assessment = validator.assessChanges(draftId, changes);
        int hardConflicts = (int) assessment.remaining().stream()
                .filter(violation -> violation.severity() >= 1)
                .count();
        int delta = assessment.softPenaltyAfter() - assessment.softPenaltyBefore();

        if (!assessment.added().isEmpty()) {
            return new ChangeResult(
                    false, false, draft.getVersion(), hardConflicts,
                    assessment.softPenaltyBefore(), assessment.softPenaltyAfter(), delta,
                    assessment.added(), assessment.remaining(), assessment.remaining());
        }

        for (DraftValidator.LessonChange change : changes) {
            DraftLesson lesson = byId.get(change.lessonId());
            lesson.setPeriodId(change.periodId());
            lesson.setTeacherId(change.teacherId());
        }
        draftLessons.saveAll(changed);
        guard.bump(draft);
        auditLogs.save(new AuditLog(
                actor == null ? "system" : actor,
                "DRAFT_CHANGE",
                "DRAFT_LESSON",
                "draftId=" + draftId + ", lessons=" + changed.size() + ", version=" + draft.getVersion(),
                changed.size(),
                0));
        return new ChangeResult(
                true, true, draft.getVersion(), hardConflicts,
                assessment.softPenaltyBefore(), assessment.softPenaltyAfter(), delta,
                List.of(), assessment.remaining(), assessment.remaining());
    }

    /** Rejects empty, duplicate and foreign lesson ids before anything is evaluated. */
    private Map<Long, DraftLesson> resolveLessons(Long draftId, List<LessonChangeItem> items) {
        Set<Long> ids = new LinkedHashSet<>();
        for (LessonChangeItem item : items) {
            if (item == null || item.lessonId() == null) {
                throw ApiException.badRequest("课节 id 不能为空");
            }
            if (!ids.add(item.lessonId())) {
                throw ApiException.badRequest("重复的课节 id: " + item.lessonId());
            }
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
        return byId;
    }

    /** Periods must exist and belong to this draft's semester; teachers must exist. */
    private void validateTargets(TimetableDraft draft, List<LessonChangeItem> items) {
        for (LessonChangeItem item : items) {
            if (item.toPeriodId() != null) {
                Period period = periods.findById(item.toPeriodId())
                        .orElseThrow(() -> ApiException.badRequest(
                                "作息时段不存在: " + item.toPeriodId()));
                if (!draft.getSemesterId().equals(period.getSemesterId())) {
                    throw ApiException.badRequest("作息时段不属于该学期: " + item.toPeriodId());
                }
            }
            if (item.toTeacherId() != null && !teachers.existsById(item.toTeacherId())) {
                throw ApiException.badRequest("教师不存在: " + item.toTeacherId());
            }
        }
    }

    /** {@code verifySource} is an expiry check only; the server state still decides the write. */
    private void verifySource(Map<Long, DraftLesson> byId, List<LessonChangeItem> items) {
        for (LessonChangeItem item : items) {
            DraftLesson lesson = byId.get(item.lessonId());
            if (!Objects.equals(item.expectedPeriodId(), lesson.getPeriodId())
                    || !Objects.equals(item.expectedTeacherId(), lesson.getTeacherId())) {
                throw ApiException.conflict("课表已被其他人修改，请刷新后重试");
            }
        }
    }
}
