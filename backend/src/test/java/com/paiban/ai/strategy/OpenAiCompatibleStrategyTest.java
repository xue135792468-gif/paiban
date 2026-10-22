package com.paiban.ai.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleStrategyTest {

    private final OpenAiCompatibleStrategy strategy =
            new OpenAiCompatibleStrategy(new ObjectMapper(), 5);

    @Test
    void interpretRejectsToolCallsInsteadOfJson() {
        AiStrategyOutcome outcome = strategy.interpret(
                200,
                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":\"\"}}]}");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("TOOL_CALL");
        assertThat(outcome.errorMessage()).contains("工具调用");
    }

    @Test
    void interpretRejectsLengthTruncation() {
        AiStrategyOutcome outcome = strategy.interpret(
                200,
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"rows\\\":\"}}]}");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("TRUNCATED");
        assertThat(outcome.errorMessage()).contains("截断");
    }
}
