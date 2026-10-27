package com.paiban.scheduling.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ConstraintProposalDtos {

    private ConstraintProposalDtos() {}

    /**
     * {@code clientRunId} is generated once per click. Repeating the request with the
     * same id returns the same run instead of sending a second model call.
     */
    public record GenerateRequest(
            @NotBlank(message = "运行标识不能为空") String clientRunId,
            @NotBlank(message = "请先输入排课要求")
            @Size(max = 2000, message = "要求原文最多 2000 字") String text) {}

    /** One choice the user has to make before a proposal can become saveable. */
    public record ProposalCandidateDto(String field, String label, List<ObjectOptionDto> options) {}

    public record ObjectOptionDto(Long id, String name) {}

    /** One normalized entry, already shaped like the editable requirement rows. */
    public record ProposalEntryDto(Map<String, Object> params, String groupKey,
                                   boolean enabled, Boolean hard, Integer weight) {}

    /**
     * One independent requirement. {@code entries} is empty unless the status is
     * {@code READY}: a proposal with a missing object or an illegal slot stays visible
     * with its reason, but nothing half-resolved can be added to the table.
     */
    public record ConstraintProposalDto(
            String proposalId,
            String sourceText,
            String type,
            String typeName,
            String strength,
            boolean effectiveHard,
            Integer effectiveWeight,
            String status,
            String explanation,
            String reason,
            Map<String, Object> params,
            List<ProposalEntryDto> entries,
            List<ProposalCandidateDto> candidates) {}

    public record RunDto(
            Long id,
            String clientRunId,
            Long semesterId,
            String status,
            String inputText,
            String errorCode,
            String errorMessage,
            String providerName,
            String modelName,
            Integer inputTokens,
            Integer outputTokens,
            Long latencyMs,
            Instant startedAt,
            Instant finishedAt,
            List<ConstraintProposalDto> proposals) {}

    /** The run plus the raw answer and the context, for the owner and global admins. */
    public record RunDetailDto(RunDto run, String rawResponse, String modelContent,
                               String lateResponse, String contextJson) {}

    /**
     * Completes one proposal without calling the model again: the user names the
     * homonym or supplies the missing condition, and the server re-runs the same
     * normalization against the current semester.
     */
    public record ResolveProposalRequest(
            @NotBlank(message = "建议标识不能为空") String proposalId,
            Long teacherId,
            Long courseId,
            Long classId,
            String strength,
            List<Integer> days,
            List<Integer> periods,
            String section,
            List<Integer> sectionOrdinals,
            Map<String, Object> values) {}

    /** List row: enough to reopen the run without shipping every raw answer. */
    public record RunSummaryDto(Long id, String status, String inputText, String errorCode,
                                String errorMessage, Instant startedAt, Instant finishedAt,
                                int proposalCount) {}

    /**
     * Whether the entry can generate at all. The page always shows the entry and tells
     * the user the reason instead of hiding a capability they could use after setup.
     */
    public record AvailabilityDto(boolean available, String reason, String message) {}
}
