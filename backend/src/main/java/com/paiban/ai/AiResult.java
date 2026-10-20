package com.paiban.ai;

/** Outcome of every gateway call. Failures are data, never exceptions. */
public record AiResult(
        boolean success,
        String content,
        String errorCode,
        String errorMessage,
        Long providerId,
        String providerName,
        Long modelId,
        String modelName,
        Integer inputTokens,
        Integer outputTokens,
        long latencyMs,
        String rawResponse) {}
