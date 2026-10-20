package com.paiban.ai;

import java.util.List;

/**
 * One gateway call.
 *
 * <p>The first four components are the cross-ticket contract shape. {@code subjectType} /
 * {@code subjectId} are the audit trail the contract asks {@code ai_call_log} to carry
 * (for example {@code SUBSTITUTION_REQUEST} + request id); they stay optional so callers
 * construct requests with the four-argument convenience constructor.
 */
public record AiRequest(
        AiTaskType taskType,
        List<AiMessage> messages,
        List<AiImage> images,
        AiOptions options,
        String subjectType,
        Long subjectId) {

    public AiRequest(AiTaskType taskType, List<AiMessage> messages, List<AiImage> images, AiOptions options) {
        this(taskType, messages, images, options, null, null);
    }

    public AiRequest withSubject(String subjectType, Long subjectId) {
        return new AiRequest(taskType, messages, images, options, subjectType, subjectId);
    }
}
