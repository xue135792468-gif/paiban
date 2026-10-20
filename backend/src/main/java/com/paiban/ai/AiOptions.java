package com.paiban.ai;

/**
 * Per-call overrides. A {@code null} value keeps whatever the model's
 * {@code params_json} declares.
 */
public record AiOptions(Double temperature, Integer maxTokens, boolean jsonMode) {

    public static final AiOptions NONE = new AiOptions(null, null, false);

    /** Model default parameters with a JSON-object response requested. */
    public static AiOptions asJson() {
        return new AiOptions(null, null, true);
    }
}
