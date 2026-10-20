package com.paiban.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.paiban.ai.domain.AiCallLog;
import com.paiban.ai.domain.AiCallLogRepository;
import com.paiban.ai.service.AiConfigResolver;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end gateway coverage against a local HTTP stub: routing, status endpoint,
 * retry on 5xx and the audit row. No external network is touched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AiCallLogIntegrationTest extends ApiIntegrationTest {

    private static final String API_KEY = "sk-integration-secret-9876";
    private static HttpServer stub;
    private static int stubPort;
    private static final AtomicInteger failingCalls = new AtomicInteger();

    @Autowired
    private AiGateway gateway;

    @Autowired
    private AiConfigResolver resolver;

    @Autowired
    private AiCallLogRepository callLogs;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"本地桩回复\"}}],"
                            + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.createContext("/fail/chat/completions", exchange -> {
            failingCalls.incrementAndGet();
            byte[] body = "{\"error\":\"stub failure\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        stubPort = stub.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @BeforeEach
    void clearRoutingCache() {
        SecurityContextHolder.clearContext();
        failingCalls.set(0);
        // DatabaseResetExtension truncates the tables without going through the services,
        // so the in-memory routing cache has to be dropped explicitly.
        resolver.invalidate();
    }

    @Test
    void gatewayCallIsAuditedAndStatusIsReadableByTeachers() {
        String admin = adminToken();
        long providerId = createProvider(admin, "stub-ok", "/v1");
        long modelId = createModel(admin, providerId);

        String username = "ai_reader_" + TestData.suffix();
        createUser(admin, username, "teacher123", "teacher");
        String teacher = login(username, "teacher123");
        as(teacher).when().get("/api/ai/status")
                .then().statusCode(200)
                .body("configured", equalTo(true))
                .body("tasks.data_import.configured", equalTo(true))
                .body("tasks.substitution_recommend.configured", equalTo(true));

        try {
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken("admin", null, List.of()));
            AiRequest request = new AiRequest(
                            AiTaskType.DATA_IMPORT,
                            List.of(new AiMessage("system", "你是教务助手"), new AiMessage("user", "你好")),
                            null,
                            new AiOptions(0.2, 128, false))
                    .withSubject("SUBSTITUTION_REQUEST", 99L);
            AiResult result = gateway.chat(request);

            assertThat(result.success()).as(result.errorMessage()).isTrue();
            assertThat(result.content()).isEqualTo("本地桩回复");
            assertThat(result.providerId()).isEqualTo(providerId);
            assertThat(result.modelId()).isEqualTo(modelId);
            assertThat(result.inputTokens()).isEqualTo(12);
            assertThat(result.outputTokens()).isEqualTo(8);

            List<AiCallLog> rows = callLogs.findAll();
            assertThat(rows).hasSize(1);
            AiCallLog row = rows.get(0);
            assertThat(row.getTaskType()).isEqualTo("data_import");
            assertThat(row.isSuccess()).isTrue();
            assertThat(row.getProviderId()).isEqualTo(providerId);
            assertThat(row.getModelId()).isEqualTo(modelId);
            assertThat(row.getUserId()).isNotNull();
            assertThat(row.getSubjectType()).isEqualTo("SUBSTITUTION_REQUEST");
            assertThat(row.getSubjectId()).isEqualTo(99L);
            assertThat(row.getRequestJson()).contains("你好").doesNotContain(API_KEY);
            assertThat(row.getResponseJson()).contains("本地桩回复").doesNotContain(API_KEY);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void serverErrorsAreRetriedThenLoggedAsFailure() {
        String admin = adminToken();
        long providerId = createProvider(admin, "stub-fail", "/fail");
        createModel(admin, providerId);

        AiResult result = gateway.chat(new AiRequest(
                AiTaskType.SUBSTITUTION_RECOMMEND,
                List.of(new AiMessage("user", "推荐")),
                null,
                AiOptions.NONE));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROVIDER_ERROR");
        assertThat(failingCalls).hasValue(3);

        List<AiCallLog> rows = callLogs.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isSuccess()).isFalse();
        assertThat(rows.get(0).getErrorCode()).isEqualTo("PROVIDER_ERROR");
        assertThat(rows.get(0).getErrorMessage()).contains("HTTP 500");
    }

    @Test
    void unconfiguredSystemReportsFalseForEveryTask() {
        String admin = adminToken();
        String username = "ai_empty_" + TestData.suffix();
        createUser(admin, username, "teacher123", "teacher");
        String teacher = login(username, "teacher123");

        as(teacher).when().get("/api/ai/status")
                .then().statusCode(200)
                .body("configured", equalTo(false))
                .body("tasks.data_import.configured", equalTo(false))
                .body("tasks.substitution_recommend.configured", equalTo(false));
    }

    @Test
    void teacherCannotChangeTaskRouting() {
        String admin = adminToken();
        String username = "ai_denied_" + TestData.suffix();
        createUser(admin, username, "teacher123", "teacher");
        String teacher = login(username, "teacher123");

        as(teacher).body(Map.of("mode", "unified", "providerId", 1, "modelId", 1))
                .when().put("/api/ai-task-config")
                .then().statusCode(403);
    }

    private long createProvider(String admin, String name, String path) {
        return as(admin).body(Map.of(
                        "name", name + "-" + TestData.suffix(),
                        "type", "custom",
                        "baseUrl", "http://127.0.0.1:" + stubPort + path,
                        "apiKey", API_KEY,
                        "enabled", true))
                .when().post("/api/ai-providers")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");
    }

    private long createModel(String admin, long providerId) {
        return as(admin).body(Map.of(
                        "modelName", "stub-model",
                        "displayName", "Stub 模型",
                        "visionCapable", true,
                        "enabled", true))
                .when().post("/api/ai-providers/" + providerId + "/models")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");
    }
}
