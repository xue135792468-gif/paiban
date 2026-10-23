package com.paiban.integration.org;

import com.paiban.integration.domain.DingDepartment;
import java.util.List;

/**
 * The read-only result of a preview run: scan counters plus the classification.
 * {@code success=false} carries a DingTalk error (including {@code sub_code}) and
 * an empty plan.
 */
public record DingTalkSyncPreview(
        boolean success,
        String message,
        int departmentsScanned,
        int usersScanned,
        int skippedDepartments,
        int failedDepartments,
        long elapsedMs,
        List<DingDepartment> skippedDepartmentDetails,
        DingTalkSyncPlan plan) {

    public DingTalkSyncPreview {
        skippedDepartmentDetails =
                skippedDepartmentDetails == null ? List.of() : List.copyOf(skippedDepartmentDetails);
        plan = plan == null ? new DingTalkSyncPlan(List.of(), List.of(), 0) : plan;
    }

    public static DingTalkSyncPreview failed(String message) {
        return new DingTalkSyncPreview(false, message, 0, 0, 0, 0, 0, List.of(), null);
    }
}
