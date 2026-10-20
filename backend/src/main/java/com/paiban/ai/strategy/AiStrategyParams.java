package com.paiban.ai.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiOptions;
import com.paiban.ai.domain.AiModel;

/**
 * Reads {@code temperature} and {@code maxTokens} / {@code max_tokens} out of a
 * model's free-form {@code params_json}; explicit {@link AiOptions} win over it.
 */
final class AiStrategyParams {

    private AiStrategyParams() {}

    static Double temperature(AiModel model, AiOptions options, ObjectMapper objectMapper) {
        if (options != null && options.temperature() != null) {
            return options.temperature();
        }
        JsonNode params = parse(model, objectMapper);
        JsonNode value = params == null ? null : params.get("temperature");
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

    static Integer maxTokens(AiModel model, AiOptions options, ObjectMapper objectMapper) {
        if (options != null && options.maxTokens() != null) {
            return options.maxTokens();
        }
        JsonNode params = parse(model, objectMapper);
        if (params == null) {
            return null;
        }
        JsonNode value = params.hasNonNull("maxTokens") ? params.get("maxTokens") : params.get("max_tokens");
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    static boolean jsonMode(AiOptions options) {
        return options != null && options.jsonMode();
    }

    private static JsonNode parse(AiModel model, ObjectMapper objectMapper) {
        String paramsJson = model == null ? null : model.getParamsJson();
        if (paramsJson == null || paramsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(paramsJson);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ex) {
            // A malformed params blob must not fail the call; model defaults apply.
            return null;
        }
    }
}
