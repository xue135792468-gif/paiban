package com.paiban.scheduling.web;

import com.paiban.scheduling.service.DraftValidator;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class SwapDtos {

    private SwapDtos() {}

    public record SwapRequest(@NotNull(message = "课节不能为空") Long lessonId,
                              @NotNull(message = "目标课节不能为空") Long targetLessonId,
                              Boolean swapRooms) {}

    public record SwapResult(boolean saved, boolean valid, int hardConflicts, String type,
                             List<DraftValidator.Conflict> conflicts, List<String> warnings) {}
}
