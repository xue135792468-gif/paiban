package com.paiban.ai;

/**
 * Per-call overrides. A {@code null} value keeps whatever the model's
 * {@code params_json} declares.
 *
 * <p>{@code timeoutSeconds} and {@code maxAttempts} are task-level boundaries: the
 * gateway keeps its 120-second/three-attempt default for every existing task, while a
 * task that must answer within 60 seconds and never retry says so here (ticket 14/08).
 */
public record AiOptions(Double temperature, Integer maxTokens, boolean jsonMode,
                        Integer timeoutSeconds, Integer maxAttempts) {

    public static final AiOptions NONE = new AiOptions(null, null, false);

    public AiOptions(Double temperature, Integer maxTokens, boolean jsonMode) {
        this(temperature, maxTokens, jsonMode, null, null);
    }

    /** Model default parameters with a JSON-object response requested. */
    public static AiOptions asJson() {
        return new AiOptions(null, null, true);
    }

    /** The same options with an explicit per-call timeout and attempt cap. */
    public AiOptions withLimits(Integer seconds, Integer attempts) {
        return new AiOptions(temperature, maxTokens, jsonMode, seconds, attempts);
    }
}
