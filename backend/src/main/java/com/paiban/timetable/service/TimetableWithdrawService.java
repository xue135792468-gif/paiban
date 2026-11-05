package com.paiban.timetable.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.substitution.domain.Substitution;
import com.paiban.substitution.domain.SubstitutionHistory;
import com.paiban.substitution.domain.SubstitutionHistoryRepository;
import com.paiban.substitution.domain.SubstitutionRepository;
import com.paiban.substitution.domain.SubstitutionRequest;
import com.paiban.substitution.domain.SubstitutionRequestRepository;
import com.paiban.timetable.domain.TimetableEntryRepository;
import com.paiban.timetable.domain.TimetablePublish;
import com.paiban.timetable.domain.TimetablePublishRepository;
import com.paiban.timetable.web.TimetableDtos.WithdrawPreviewDto;
import com.paiban.timetable.web.TimetableDtos.WithdrawResultDto;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdrawing takes the published baseline back to an editable draft.
 *
 * <p>Only the baseline is withdrawn: {@code timetable_entry} rows are deleted, the
 * publish row turns WITHDRAWN, and the draft returns to DRAFT. Substitution rows are
 * never deleted; the foreign keys use ON DELETE SET NULL so every row survives with
 * its whole history. Future overlay rows — and the requests behind them — are
 * cancelled because their timetable instance no longer exists; past rows keep their
 * status as history.
 */
@Service
public class TimetableWithdrawService {

    /** The comment every cancellations landed in {@code substitution_history} gets. */
    static final String WITHDRAW_COMMENT = "发布撤回：课表基线已撤回为草稿，需重新处理";

    private final TimetableDraftRepository drafts;
    private final TimetableEntryRepository entries;
    private final TimetablePublishRepository publishes;
    private final SubstitutionRepository substitutions;
    private final SubstitutionRequestRepository requests;
    private final SubstitutionHistoryRepository history;
    private final AuditLogRepository auditLogs;
    private final Clock clock;

    public TimetableWithdrawService(
            TimetableDraftRepository drafts,
            TimetableEntryRepository entries,
            TimetablePublishRepository publishes,
            SubstitutionRepository substitutions,
            SubstitutionRequestRepository requests,
            SubstitutionHistoryRepository history,
            AuditLogRepository auditLogs,
            Clock clock) {
        this.drafts = drafts;
        this.entries = entries;
        this.publishes = publishes;
        this.substitutions = substitutions;
        this.requests = requests;
        this.history = history;
        this.auditLogs = auditLogs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WithdrawPreviewDto preview(Long semesterId) {
        long total = substitutions.countBySemesterId(semesterId);
        LocalDate today = today();
        return publishes.findFirstBySemesterIdAndStatusOrderByPublishedAtDesc(
                        semesterId, TimetablePublish.ACTIVE)
                .map(publish -> new WithdrawPreviewDto(
                        true,
                        publish.getDraftId(),
                        publish.getId(),
                        futureImpact(semesterId, today).count(),
                        total))
                .orElseGet(() -> new WithdrawPreviewDto(false, null, null, 0, total));
    }

    @Transactional
    public WithdrawResultDto withdraw(Long draftId, String actor) {
        // Same row lock publishing takes: a concurrent publish/withdraw cannot interleave
        // between the status check and the entry delete. A recycled draft is not found.
        TimetableDraft draft = drafts.findLiveWithLockById(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        if (!"PUBLISHED".equals(draft.getStatus())) {
            throw ApiException.conflict("只有已发布的课表可以撤回为草稿");
        }
        TimetablePublish publish = publishes
                .findFirstBySemesterIdAndDraftIdAndStatusOrderByPublishedAtDesc(
                        draft.getSemesterId(), draftId, TimetablePublish.ACTIVE)
                .orElseThrow(() -> ApiException.conflict("该课表没有可撤回的发布记录，请刷新后重试"));

        FutureImpact impact = futureImpact(draft.getSemesterId(), today());
        impact.substitutions().forEach(item -> item.setStatus(Substitution.CANCELLED));
        substitutions.saveAll(impact.substitutions());
        substitutions.flush();
        cancelRequests(impact.requests(), actor);

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
                        + ", futureSubstitutions=" + impact.count()
                        + ", cancelledRequests=" + impact.requests().size()
                        + ", entries=" + removedEntries,
                impact.count(),
                0));

        return new WithdrawResultDto(
                draftId, publish.getId(), now, impact.count(), removedEntries);
    }

    /** The school-timezone date every "future" judgement in this service is made against. */
    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * What the withdrawal would take away: the overlay rows that still cover a future
     * lesson plus the requests behind them. A request stays an approval record, so it
     * must not read EFFECTIVE once its rows no longer apply — the history page
     * and the re-processing prompt both read that status.
     */
    private FutureImpact futureImpact(Long semesterId, LocalDate today) {
        Map<Long, Substitution> rows = new LinkedHashMap<>();
        substitutions.findBySemesterIdAndStatus(semesterId, Substitution.EFFECTIVE).stream()
                .filter(row -> SubstitutionFutures.affectsFuture(row, today))
                .forEach(row -> rows.put(row.getId(), row));

        // An approved request may not have produced a substitution row yet (approved for a
        // future date, not yet materialised); it still stops being actionable.
        List<SubstitutionRequest> approved =
                futureApprovedRequests(semesterId, today);
        for (SubstitutionRequest request : approved) {
            for (Substitution row : substitutions.findByRequestId(request.getId())) {
                if (Substitution.EFFECTIVE.equals(row.getStatus())) {
                    rows.putIfAbsent(row.getId(), row);
                }
            }
        }

        List<Substitution> cancelledRows = List.copyOf(rows.values());
        List<SubstitutionRequest> requestRows = new ArrayList<>();
        for (Substitution row : cancelledRows) {
            if (row.getRequestId() == null) {
                continue;
            }
            requests.findById(row.getRequestId())
                    .filter(request -> !SubstitutionRequest.CANCELLED.equals(request.getStatus()))
                    .filter(request -> requestRows.stream()
                            .noneMatch(existing -> existing.getId().equals(request.getId())))
                    .ifPresent(requestRows::add);
        }
        for (SubstitutionRequest request : approved) {
            boolean already = requestRows.stream()
                    .anyMatch(existing -> existing.getId().equals(request.getId()));
            if (!already) {
                requestRows.add(request);
            }
        }
        return new FutureImpact(cancelledRows, List.copyOf(requestRows));
    }

    /** Effective requests whose effective date is today or later: nothing ran yet. */
    private List<SubstitutionRequest> futureApprovedRequests(Long semesterId, LocalDate today) {
        return requests.findBySemesterIdAndStatusOrderByCreatedAtDesc(
                        semesterId, SubstitutionRequest.EFFECTIVE)
                .stream()
                .filter(request -> request.getEffectiveDate() == null
                        || !request.getEffectiveDate().isBefore(today))
                .toList();
    }

    /**
     * Cancels the requests and appends one history row each, in the same transaction as
     * the rows themselves: a cancelled substitution without a cancelled request would
     * leave the request page claiming an arrangement that the timetable no longer has.
     */
    private void cancelRequests(List<SubstitutionRequest> requestRows, String actor) {
        if (requestRows.isEmpty()) {
            return;
        }
        String who = actor == null ? "system" : actor;
        Instant now = Instant.now();
        for (SubstitutionRequest request : requestRows) {
            SubstitutionHistory item = new SubstitutionHistory();
            item.setRequestId(request.getId());
            item.setFromStatus(request.getStatus());
            item.setToStatus(SubstitutionRequest.CANCELLED);
            item.setActor(who);
            item.setComment(WITHDRAW_COMMENT);
            item.setCreatedAt(now);
            history.save(item);
            request.setStatus(SubstitutionRequest.CANCELLED);
            request.setUpdatedAt(now);
        }
        requests.saveAll(requestRows);
        requests.flush();
    }

    /**
     * The rows to cancel plus the requests to cancel. {@link #count()} is the number the
     * admin is told about: rows, plus approved requests that never produced a row.
     */
    private record FutureImpact(List<Substitution> substitutions, List<SubstitutionRequest> requests) {

        int count() {
            long withoutRow = requests.stream()
                    .filter(request -> substitutions.stream()
                            .noneMatch(row -> request.getId().equals(row.getRequestId())))
                    .count();
            return substitutions.size() + (int) withoutRow;
        }
    }
}
