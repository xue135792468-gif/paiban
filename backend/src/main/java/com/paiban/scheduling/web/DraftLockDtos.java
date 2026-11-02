package com.paiban.scheduling.web;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class DraftLockDtos {

    private DraftLockDtos() {}

    /**
     * Locks or unlocks an explicit set of lessons. The client expands "this day" or
     * "the whole class" itself; the server only trusts the ids it receives.
     */
    public record LockLessonsRequest(List<Long> lessonIds,
                                     @NotNull(message = "锁定状态不能为空") Boolean locked,
                                     Long expectedVersion) {}

    /** {@code requested} is the de-duplicated input size, {@code updated} the rows that really changed. */
    public record LockResult(int requested, int updated, boolean locked, long version) {}
}
