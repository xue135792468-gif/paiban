package com.paiban.ai.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.ai.AiMessage;
import com.paiban.ai.AiOptions;
import com.paiban.ai.AiRequest;
import com.paiban.ai.AiTaskType;
import com.paiban.ai.domain.AiModel;
import com.paiban.ai.domain.AiProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executors;
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

    @Test
    void aStalledResponseBodyReturnsTimeoutInsteadOfBlocking() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            AiProvider provider = new AiProvider();
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            provider.setProtocol("openai");
            AiModel model = new AiModel();
            model.setModelName("test-model");
            AiRequest request = new AiRequest(
                    AiTaskType.NATURAL_LANGUAGE_CONSTRAINTS,
                    List.of(AiMessage.user("hi")),
                    List.of(),
                    new AiOptions(null, 16, true).withLimits(1, 1));
            OpenAiCompatibleStrategy fast =
                    new OpenAiCompatibleStrategy(new ObjectMapper(), 1);

            AiStrategyOutcome outcome =
                    fast.invoke(new AiInvocation(provider, "test-key", model, request));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.errorCode()).isEqualTo("TIMEOUT");
        } finally {
            server.stop(0);
        }
    }
}
