package com.paiban.scheduling.web;

import com.paiban.scheduling.service.DraftValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Transport shapes for the one atomic "apply these lesson changes" endpoint.
 *
 * <p>Nullable targets mean "keep the current value" for teacher; a null
 * {@code toPeriodId} means "move this lesson to the unscheduled pool", which the draft
 * editor allows but publishing will still refuse until it is placed again.
 */
public final class DraftChangeDtos {

    private DraftChangeDtos() {}

    public record LessonChangeItem(
            @NotNull(message = "课节不能为空") Long lessonId,
            Long toPeriodId,
            Long toTeacherId,
            Long expectedPeriodId,
            Long expectedTeacherId) {}

    public record ChangeRequest(
            @NotEmpty(message = "请至少提交一节课的变更") @Valid List<LessonChangeItem> changes,
            Long expectedVersion,
            String rulesFingerprint,
            Boolean verifySource) {}

    public record ChangeResult(
            boolean saved,
            boolean valid,
            long version,
            int hardConflicts,
            int softPenaltyBefore,
            int softPenaltyAfter,
            int softPenaltyDelta,
            List<DraftValidator.Violation> addedConflicts,
            List<DraftValidator.Violation> remainingConflicts,
            List<DraftValidator.Violation> conflicts) {}
}
