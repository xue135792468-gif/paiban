package com.paiban.ai.strategy;

/**
 * Raw outcome of one HTTP attempt. {@code httpStatus} drives retry decisions in
 * the gateway and is {@code null} when the failure never reached an HTTP status
 * (connect error, timeout, unparsable body).
 */
public record AiStrategyOutcome(
        boolean success,
        String content,
        String errorCode,
        String errorMessage,
        Integer inputTokens,
        Integer outputTokens,
        String rawResponse,
        Integer httpStatus) {

    public static AiStrategyOutcome success(
            String content, Integer inputTokens, Integer outputTokens, String rawResponse) {
        return new AiStrategyOutcome(true, content, null, null, inputTokens, outputTokens, rawResponse, 200);
    }

    public static AiStrategyOutcome failure(String errorCode, String errorMessage, Integer httpStatus) {
        return new AiStrategyOutcome(false, null, errorCode, errorMessage, null, null, null, httpStatus);
    }
}
