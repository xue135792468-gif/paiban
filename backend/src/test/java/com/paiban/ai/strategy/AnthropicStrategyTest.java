package com.paiban.ai.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiImage;
import com.paiban.ai.AiMessage;
import com.paiban.ai.AiOptions;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiTaskType;
import com.paiban.ai.domain.AiModel;
import com.paiban.ai.domain.AiProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicStrategyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicStrategy strategy = new AnthropicStrategy(mapper, 5);

    @Test
    void supportsOnlyTheAnthropicProtocol() {
        assertThat(strategy.supports(provider("anthropic"))).isTrue();
        assertThat(strategy.supports(provider("openai"))).isFalse();
        assertThat(strategy.supports(provider("gemini"))).isFalse();
    }

    @Test
    void bodySplitsSystemMessagesAndAddsJsonInstruction() throws Exception {
        AiRequest request = new AiRequest(
                AiTaskType.DATA_IMPORT,
                List.of(
                        new AiMessage("system", "你是教务助手"),
                        new AiMessage("user", "你好"),
                        new AiMessage("assistant", "在"),
                        new AiMessage("user", "继续")),
                null,
                AiOptions.asJson());

        JsonNode body = mapper.readTree(strategy.body(invocation(request)));

        assertThat(body.path("model").asText()).isEqualTo("claude-sonnet-4-5");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1024);
        assertThat(body.path("system").asText()).contains("你是教务助手").contains("JSON");
        assertThat(body.path("messages").size()).isEqualTo(3);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(body.has("response_format")).isFalse();
    }

    @Test
    void bodyCarriesInlineImagesForVision() throws Exception {
        AiRequest request = new AiRequest(
                AiTaskType.DATA_IMPORT,
                List.of(new AiMessage("user", "读出这张表")),
                List.of(new AiImage("image/png", new byte[] {1, 2, 3})),
                AiOptions.NONE);

        JsonNode body = mapper.readTree(strategy.body(invocation(request)));
        JsonNode content = body.path("messages").get(0).path("content");

        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image");
        assertThat(content.get(1).path("source").path("media_type").asText()).isEqualTo("image/png");
        assertThat(content.get(1).path("source").path("data").asText()).isNotBlank();
    }

    @Test
    void interpretParsesTextAndUsage() {
        AiStrategyOutcome outcome = strategy.interpret(
                200,
                """
                {"content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}],
                 "usage":{"input_tokens":11,"output_tokens":7}}
                """);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.content()).isEqualTo("第一段第二段");
        assertThat(outcome.inputTokens()).isEqualTo(11);
        assertThat(outcome.outputTokens()).isEqualTo(7);
    }

    @Test
    void interpretSurfacesAnthropicErrors() {
        AiStrategyOutcome outcome = strategy.interpret(
                401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.httpStatus()).isEqualTo(401);
        assertThat(outcome.errorMessage()).contains("invalid x-api-key");
    }

    @Test
    void interpretRejectsATruncatedResponse() {
        AiStrategyOutcome outcome = strategy.interpret(
                200,
                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"rows\\\":[\"}],\"stop_reason\":\"max_tokens\"}");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("TRUNCATED");
        assertThat(outcome.errorMessage()).contains("max_tokens");
    }

    private AiInvocation invocation(AiRequest request) {
        return new AiInvocation(provider("anthropic"), "sk-ant-test", model(), request);
    }

    private AiProvider provider(String protocol) {
        AiProvider provider = new AiProvider();
        provider.setName("Anthropic");
        provider.setType("preset");
        provider.setProtocol(protocol);
        provider.setBaseUrl("https://api.anthropic.com");
        provider.setEnabled(true);
        return provider;
    }

    private AiModel model() {
        AiModel model = new AiModel();
        model.setProviderId(1L);
        model.setModelName("claude-sonnet-4-5");
        model.setDisplayName("Claude Sonnet 4.5");
        model.setVisionCapable(true);
        model.setEnabled(true);
        return model;
    }
}
