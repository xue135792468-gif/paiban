package com.paiban.scheduling.web;

import com.paiban.scheduling.service.DraftValidator;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * Transport shapes for searching and applying draft cycle plans.
 *
 * <p>A search returns a one-time {@code token} per verified plan. The token is the
 * only thing the apply endpoint trusts: the moves, the draft state and the policy
 * fingerprint are all re-read and re-verified server-side.
 */
public final class DraftCyclePlanDtos {

    private DraftCyclePlanDtos() {}

    public record SearchRequest(
            @NotNull(message = "来源课节不能为空") Long sourceLessonId,
            @NotNull(message = "落点课节不能为空") Long targetPeriodId,
            Long targetLessonId,
            Long expectedVersion,
            String rulesFingerprint,
            Integer nodeBudget,
            Integer timeBudgetMs) {}

    public record PlanMove(
            Long lessonId,
            Long fromPeriodId,
            Long toPeriodId,
            Long teacherId,
            Long courseId,
            Long classId) {}

    public record Plan(
            String token,
            String kind,
            List<PlanMove> moves,
            int movedLessons,
            int affectedTeachers,
            int affectedDays,
            int softPenaltyAfter,
            int softPenaltyDelta,
            Instant expiresAt) {}

    public record SearchResponse(
            String status,
            String message,
            long version,
            String fingerprint,
            List<Plan> plans) {}

    public record ApplyRequest(
            @NotNull(message = "方案凭证不能为空") String token,
            Long expectedVersion) {}

    public record ApplyResponse(
            boolean saved,
            boolean valid,
            long version,
            int hardConflicts,
            int softPenaltyBefore,
            int softPenaltyAfter,
            int softPenaltyDelta,
            List<DraftValidator.Violation> addedConflicts,
            List<DraftValidator.Violation> remainingConflicts,
            List<DraftValidator.Violation> conflicts,
            boolean tokenUsed) {}
}
