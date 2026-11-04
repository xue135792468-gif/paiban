package com.paiban.timetable.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.timetable.domain.TimetableEntryRepository;
import com.paiban.timetable.domain.TimetablePublish;
import com.paiban.timetable.domain.TimetablePublishRepository;
import com.paiban.timetable.web.TimetableDtos.WithdrawPreviewDto;
import com.paiban.timetable.web.TimetableDtos.WithdrawResultDto;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdrawing takes the published baseline back to an editable draft.
 *
 * <p>Only the baseline is withdrawn: {@code timetable_entry} rows are deleted, the
 * publish row turns WITHDRAWN, and the draft returns to DRAFT. Substitution rows are
 * never deleted; the foreign keys use ON DELETE SET NULL so every row survives with
 * its whole history. Future EFFECTIVE substitutions are cancelled because their
 * timetable instance no longer exists; past rows keep their status as history.
 */
@Service
public class TimetableWithdrawService {

    private final TimetableDraftRepository drafts;
    private final TimetableEntryRepository entries;
    private final TimetablePublishRepository publishes;
    private final SubstitutionRepository substitutions;
    private final AuditLogRepository auditLogs;

    public TimetableWithdrawService(
            TimetableDraftRepository drafts,
            TimetableEntryRepository entries,
            TimetablePublishRepository publishes,
            SubstitutionRepository substitutions,
            AuditLogRepository auditLogs) {
        this.drafts = drafts;
        this.entries = entries;
        this.publishes = publishes;
        this.substitutions = substitutions;
        this.auditLogs = auditLogs;
    }

    @Transactional(readOnly = true)
    public WithdrawPreviewDto preview(Long semesterId) {
        long total = substitutions.countBySemesterId(semesterId);
        return publishes.findFirstBySemesterIdAndStatusOrderByPublishedAtDesc(
                        semesterId, TimetablePublish.ACTIVE)
                .map(publish -> new WithdrawPreviewDto(
                        true,
                        publish.getDraftId(),
                        publish.getId(),
                        futureSubstitutions(semesterId, LocalDate.now()).size(),
                        total))
                .orElseGet(() -> new WithdrawPreviewDto(false, null, null, 0, total));
    }

    @Transactional
    public WithdrawResultDto withdraw(Long draftId, String actor) {
        // Same row lock publishing takes: a concurrent publish/withdraw cannot interleave
        // between the status check and the entry delete.
        TimetableDraft draft = drafts.findWithLockById(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        if (!"PUBLISHED".equals(draft.getStatus())) {
            throw ApiException.conflict("只有已发布的课表可以撤回为草稿");
        }
        TimetablePublish publish = publishes
                .findFirstBySemesterIdAndDraftIdAndStatusOrderByPublishedAtDesc(
                        draft.getSemesterId(), draftId, TimetablePublish.ACTIVE)
                .orElseThrow(() -> ApiException.conflict("该课表没有可撤回的发布记录，请刷新后重试"));

        List<Substitution> future = futureSubstitutions(draft.getSemesterId(), LocalDate.now());
        future.forEach(item -> item.setStatus(Substitution.CANCELLED));
        substitutions.saveAll(future);
        substitutions.flush();

        int removedEntries = (int) entries.countBySemesterId(draft.getSemesterId());
        // Deleting the baseline is safe: substitution/request entry_id now becomes NULL
        // instead of cascading the row away.
        entries.deleteBySemesterId(draft.getSemesterId());
        entries.flush();

        Instant now = Instant.now();
        publish.setStatus(TimetablePublish.WITHDRAWN);
        publish.setWithdrawnAt(now);
        publish.setWithdrawnBy(actor);
        publishes.save(publish);

        draft.setStatus("DRAFT");
        drafts.save(draft);

        auditLogs.save(new AuditLog(
                actor == null ? "system" : actor,
                "WITHDRAW_TIMETABLE",
                "TIMETABLE",
                "draftId=" + draftId + ", publishId=" + publish.getId()
                        + ", futureSubstitutions=" + future.size()
                        + ", entries=" + removedEntries,
                future.size(),
                0));

        return new WithdrawResultDto(
                draftId, publish.getId(), now, future.size(), removedEntries);
    }

    /**
     * A row affects a future date when any of its instance dates is after today.
     * Rows with a NULL lesson_date keep the legacy "from effective_date onward"
     * meaning, so effective_date is their only date.
     */
    private List<Substitution> futureSubstitutions(Long semesterId, LocalDate today) {
        return substitutions.findBySemesterIdAndStatus(semesterId, Substitution.EFFECTIVE).stream()
                .filter(item -> after(item.getEffectiveDate(), today)
                        || after(item.getLessonDate(), today)
                        || after(item.getTargetLessonDate(), today))
                .toList();
    }

    private static boolean after(LocalDate date, LocalDate today) {
        return date != null && date.isAfter(today);
    }
}
