package com.paiban.integration.web;

import com.paiban.integration.org.DingTalkSyncPlan;
import com.paiban.integration.org.DingTalkSyncPlan.Category;
import com.paiban.integration.org.DingTalkSyncPlan.FieldChange;
import com.paiban.integration.org.DingTalkSyncPlan.Item;
import com.paiban.integration.org.DingTalkDeactivationPlanner;
import com.paiban.integration.org.DingTalkSyncPreview;
import com.paiban.integration.domain.DingDepartment;
import com.paiban.integration.domain.DingUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wire format of the DingTalk sync preview and confirm flow. */
public final class DingTalkSyncDtos {

    private DingTalkSyncDtos() {}

    public record SyncPreviewDto(
            boolean success,
            String message,
            int departmentsScanned,
            int usersScanned,
            int skippedDepartments,
            int failedDepartments,
            long elapsedMs,
            List<String> skippedDepartmentNames,
            SummaryDto summary,
            List<DepartmentGroupDto> departments,
            boolean scanComplete,
            String deactivationWarning,
            List<DeactivationCandidateDto> deactivations) {}

    /** 票 04：预演里的「将停用」候选；confirm 会按 {@code accountId} 明确选择。 */
    public record DeactivationCandidateDto(
            Long accountId,
            String accountName,
            String displayName,
            Long teacherId,
            String teacherName,
            String dingtalkUserId) {}

    public record SummaryDto(long create, long merge, long suspected, long skip) {}

    public record DepartmentGroupDto(String department, List<SyncItemDto> items) {}

    public record SyncItemDto(
            String userId,
            String name,
            String employeeNo,
            String phone,
            List<String> departments,
            boolean active,
            String category,
            String reason,
            Long matchedTeacherId,
            String matchedTeacherName,
            List<FieldChangeDto> fills,
            List<FieldChangeDto> conflicts,
            List<CandidateDto> candidates) {}

    public record FieldChangeDto(String field, String label, String localValue, String remoteValue) {}

    public record CandidateDto(Long id, String name, String employeeNo, String phone) {}

    /**
     * Selection sent to the preview and confirm endpoints. All fields are optional:
     * an empty body means "everything readable, no suspected merges".
     */
    public record ConfirmRequest(
            List<String> excludedDepartments,
            List<String> excludedUserIds,
            List<SuspectedDecisionDto> suspectedDecisions) {}

    /** {@code action} is MERGE, CREATE or SKIP; MERGE requires {@code teacherId}. */
    public record SuspectedDecisionDto(String userId, String action, Long teacherId) {}

    public static SyncPreviewDto from(DingTalkSyncPreview preview) {
        DingTalkSyncPlan plan = preview.plan();
        Map<String, List<SyncItemDto>> grouped = new LinkedHashMap<>();
        for (Item item : plan.items()) {
            grouped.computeIfAbsent(groupName(item.user()), ignored -> new ArrayList<>()).add(item(item));
        }
        List<DepartmentGroupDto> departments = grouped.entrySet().stream()
                .map(entry -> new DepartmentGroupDto(entry.getKey(), entry.getValue()))
                .toList();
        return new SyncPreviewDto(
                preview.success(),
                preview.message(),
                preview.departmentsScanned(),
                preview.usersScanned(),
                preview.skippedDepartments(),
                preview.failedDepartments(),
                preview.elapsedMs(),
                preview.skippedDepartmentDetails().stream().map(DingDepartment::name).toList(),
                new SummaryDto(
                        plan.count(Category.CREATE),
                        plan.count(Category.MERGE),
                        plan.count(Category.SUSPECTED),
                        plan.count(Category.SKIP)),
                departments,
                preview.scanComplete(),
                preview.deactivationWarning(),
                preview.deactivations().stream()
                        .map(DingTalkSyncDtos::deactivation)
                        .toList());
    }

    private static DeactivationCandidateDto deactivation(DingTalkDeactivationPlanner.Candidate candidate) {
        return new DeactivationCandidateDto(
                candidate.accountId(),
                candidate.accountName(),
                candidate.displayName(),
                candidate.teacherId(),
                candidate.teacherName(),
                candidate.dingtalkUserId());
    }

    private static String groupName(DingUser user) {
        String department = user.primaryDepartmentName();
        return department == null || department.isBlank() ? "未分组" : department;
    }

    private static SyncItemDto item(Item item) {
        DingUser user = item.user();
        return new SyncItemDto(
                user.userId(),
                user.name(),
                user.jobNumber(),
                user.mobile(),
                user.departmentNames(),
                user.active(),
                item.category().name(),
                item.reason(),
                item.matchedTeacherId(),
                item.matchedTeacherName(),
                item.fills().stream().map(DingTalkSyncDtos::change).toList(),
                item.conflicts().stream().map(DingTalkSyncDtos::change).toList(),
                item.candidates().stream()
                        .map(candidate -> new CandidateDto(
                                candidate.id(), candidate.name(), candidate.employeeNo(), candidate.phone()))
                        .toList());
    }

    private static FieldChangeDto change(FieldChange change) {
        return new FieldChangeDto(
                change.field(), change.label(), change.localValue(), change.remoteValue());
    }
}
