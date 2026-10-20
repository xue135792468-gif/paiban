package com.paiban.ai;

/**
 * Single entry point for every AI call in the system. Business code injects this
 * interface and never talks to a provider directly.
 *
 * <p>Implementations always answer with an {@link AiResult}; a provider outage
 * must never surface as an exception in the calling business flow.
 */
public interface AiGateway {

    /** Text-only call. Any images carried by the request are ignored. */
    AiResult chat(AiRequest request);

    /**
     * Multimodal call. Rejected with {@code VISION_NOT_SUPPORTED} when the model
     * bound to the task has {@code vision_capable = false}.
     */
    AiResult chatWithVision(AiRequest request);

    /** True when both task slots resolve to an enabled provider and model. */
    boolean configured();

    /** Per-task availability; a task can be usable while the other one is not. */
    default boolean configured(AiTaskType taskType) {
        return configured();
    }
}
