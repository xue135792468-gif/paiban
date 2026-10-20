package com.paiban.ai.history;

import java.util.List;

public final class AiHistoryDtos {

    private AiHistoryDtos() {}

    public record HistoryItem(
            String type,
            Long id,
            String taskType,
            String username,
            String createdAt,
            String summary,
            boolean success,
            String providerName,
            String modelName,
            Long refId,
            Long userId,
            Long providerId) {}

    public record HistoryPage(List<HistoryItem> items, long total, int page, int size) {}

    public record ImportDetail(
            Long id,
            String importType,
            String targetType,
            String username,
            String fileName,
            Long fileSize,
            String status,
            int rowCount,
            int importedCount,
            String explanation,
            Object preview,
            Object confirmed,
            Object result,
            String aiRawResponse,
            String createdAt,
            String updatedAt) {}

    public record Candidate(Long teacherId, String name, String reason, Integer score) {}

    public record SubstitutionContext(
            Long requestId,
            String originalTeacher,
            String courseName,
            String className,
            String effectiveDate,
            Integer periodNo,
            String status,
            String finalSubstituteTeacher,
            List<Candidate> recommendations) {}

    public record CallDetail(
            Long id,
            String taskType,
            String username,
            String providerName,
            String modelName,
            boolean success,
            String errorCode,
            String errorMessage,
            Integer inputTokens,
            Integer outputTokens,
            Long latencyMs,
            Object request,
            Object response,
            String createdAt,
            SubstitutionContext context) {}
}
