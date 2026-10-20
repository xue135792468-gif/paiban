package com.paiban.ai.strategy;

import com.paiban.ai.domain.AiProvider;

/**
 * Provider protocol implementation. The gateway picks the first non-fallback
 * strategy that {@link #supports} the provider and falls back to the
 * OpenAI-compatible one otherwise.
 */
public interface AiStrategy {

    /** True when this strategy speaks the provider's protocol. */
    boolean supports(AiProvider provider);

    /** Marks the protocol-agnostic implementation used when nothing else matches. */
    default boolean fallback() {
        return false;
    }

    /** Performs exactly one HTTP attempt. Transport errors come back as a failed outcome. */
    AiStrategyOutcome invoke(AiInvocation invocation);
}
