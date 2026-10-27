package com.paiban.scheduling.domain;

/**
 * One terminal update of a natural-language generation run. It replaces the long
 * positional parameter list that both the service and the repository used to carry;
 * the error message is clipped here because the column is 1000 characters.
 */
public record ConstraintRunCompletion(
        Long runId,
        String status,
        String rawResponse,
        String modelContent,
        String proposalsJson,
        String errorCode,
        String errorMessage,
        String providerName,
        String modelName,
        Integer inputTokens,
        Integer outputTokens,
        long latencyMs) {

    public ConstraintRunCompletion {
        if (errorMessage != null && errorMessage.length() > 1000) {
            errorMessage = errorMessage.substring(0, 1000);
        }
    }
}
