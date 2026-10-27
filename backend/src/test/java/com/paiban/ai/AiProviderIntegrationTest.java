package com.paiban.ai;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * REST contract for AI provider, model and task assignment management.
 * The connectivity test talks to a local JDK HttpServer stub, never the internet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AiProviderIntegrationTest extends ApiIntegrationTest {

    private static final String SECRET = "sk-very-secret-cdef";

    private HttpServer stub;
    private int stubPort;
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();

    @BeforeEach
    void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubPort = stub.getAddress().getPort();
        stub.createContext("/v1/models", exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"object\":\"list\",\"data\":["
                            + "{\"id\":\"deepseek-chat\"},"
                            + "{\"id\":\"deepseek-reasoner\"},"
                            + "{\"id\":\"deepseek-flash\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        stub.start();
    }

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    void providerAndModelCrud() {
        String token = adminToken();

        long providerId = createProvider(token, "DeepSeek 主账号", "preset", "https://api.deepseek.com", null);
        as(token).when().get("/api/ai-providers")
                .then().statusCode(200)
                .body("find { it.id == " + providerId + " }.name", equalTo("DeepSeek 主账号"))
                .body("find { it.id == " + providerId + " }.type", equalTo("preset"))
                .body("find { it.id == " + providerId + " }.hasApiKey", equalTo(false));

        as(token).body(Map.of(
                        "name", "DeepSeek 主账号（改名）",
                        "type", "preset",
                        "baseUrl", "https://api.deepseek.com",
                        "enabled", false))
                .when().put("/api/ai-providers/" + providerId)
                .then().statusCode(200)
                .body("name", equalTo("DeepSeek 主账号（改名）"))
                .body("enabled", equalTo(false));

        long modelId = createModel(token, providerId, "deepseek-chat", "DeepSeek Chat", false);
        as(token).body(Map.of(
                        "modelName", "deepseek-chat",
                        "displayName", "DeepSeek 对话",
                        "visionCapable", true,
                        "enabled", false,
                        "paramsJson", "{\"temperature\":0.3,\"max_tokens\":2048}"))
                .when().put("/api/ai-providers/" + providerId + "/models/" + modelId)
                .then().statusCode(200)
                .body("displayName", equalTo("DeepSeek 对话"))
                .body("visionCapable", equalTo(true))
                .body("enabled", equalTo(false))
                .body("paramsJson", equalTo("{\"temperature\":0.3,\"max_tokens\":2048}"));

        as(token).when().get("/api/ai-providers/" + providerId)
                .then().statusCode(200)
                .body("models", hasSize(1))
                .body("models[0].modelName", equalTo("deepseek-chat"));

        as(token).body(Map.of("modelName", "deepseek-chat", "displayName", "重复"))
                .when().post("/api/ai-providers/" + providerId + "/models")
                .then().statusCode(409);

        as(token).body(Map.of("modelName", "orphan", "displayName", "无供应商"))
                .when().post("/api/ai-providers/999999/models")
                .then().statusCode(404);

        as(token).when().delete("/api/ai-providers/" + providerId + "/models/" + modelId)
                .then().statusCode(200);
        as(token).when().delete("/api/ai-providers/" + providerId)
                .then().statusCode(200);
        as(token).when().get("/api/ai-providers")
                .then().statusCode(200)
                .body("size()", equalTo(0));

        as(token).body(Map.of("name", "重复名称", "type", "custom", "baseUrl", "https://example.com/v1"))
                .when().post("/api/ai-providers")
                .then().statusCode(200);
        as(token).body(Map.of("name", "重复名称", "type", "custom", "baseUrl", "https://example.com/v1"))
                .when().post("/api/ai-providers")
                .then().statusCode(409);
    }

    @Test
    void apiKeyIsMaskedAndNeverReturnedInPlainText() {
        String token = adminToken();
        long providerId = createProvider(
                token, "密钥供应商" + TestData.suffix(), "custom", "https://api.example.com/v1", SECRET);

        String single = as(token).when().get("/api/ai-providers/" + providerId)
                .then().statusCode(200)
                .body("hasApiKey", equalTo(true))
                .body("apiKeyMasked", equalTo("sk-****cdef"))
                .extract().asString();
        org.junit.jupiter.api.Assertions.assertFalse(single.contains(SECRET), "single response leaked the key");

        String list = as(token).when().get("/api/ai-providers")
                .then().statusCode(200).extract().asString();
        org.junit.jupiter.api.Assertions.assertFalse(list.contains(SECRET), "list response leaked the key");
        org.junit.jupiter.api.Assertions.assertFalse(list.contains("apiKeyCipher"), "ciphertext must not be exposed");
    }

    @Test
    void connectionTestSucceedsAgainstLocalStub() {
        String token = adminToken();
        long providerId = createProvider(
                token, "本地桩" + TestData.suffix(), "custom", "http://127.0.0.1:" + stubPort + "/v1", SECRET);

        as(token).when().post("/api/ai-providers/" + providerId + "/test")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("modelNames", hasSize(3))
                .body("modelNames", hasItem("deepseek-chat"))
                .body("latencyMs", greaterThanOrEqualTo(0))
                .body("message", containsString("连接成功"));

        org.junit.jupiter.api.Assertions.assertEquals(
                "Bearer " + SECRET, receivedAuthorization.get(), "API key must be sent as a Bearer token");

        as(token).when().post("/api/ai-providers/" + providerId + "/fetch-models")
                .then().statusCode(200)
                .body("$", hasSize(3))
                .body("$", hasItem("deepseek-reasoner"));
    }

    @Test
    void connectionTestFailureReturns200WithSuccessFalse() throws IOException {
        String token = adminToken();
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        long providerId = createProvider(
                token, "不可达" + TestData.suffix(), "custom", "http://127.0.0.1:" + closedPort + "/v1", SECRET);

        as(token).when().post("/api/ai-providers/" + providerId + "/test")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", notNullValue())
                .body("modelNames", hasSize(0));

        long noKeyId = createProvider(
                token, "无密钥" + TestData.suffix(), "custom", "http://127.0.0.1:" + stubPort + "/v1", null);
        as(token).when().post("/api/ai-providers/" + noKeyId + "/test")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", containsString("API Key"));
    }

    @Test
    void unifiedAndCustomTaskAssignment() {
        String token = adminToken();
        long firstProvider = createProvider(
                token, "一号" + TestData.suffix(), "custom", "http://127.0.0.1:" + stubPort + "/v1", SECRET);
        long firstModel = createModel(token, firstProvider, "deepseek-flash", "DeepSeek Flash", false);
        long secondProvider = createProvider(
                token, "二号" + TestData.suffix(), "custom", "http://127.0.0.1:" + stubPort + "/v1", SECRET);
        long secondModel = createModel(token, secondProvider, "deepseek-v4-pro", "DeepSeek V4 Pro", true);

        as(token).body(Map.of("mode", "unified", "providerId", firstProvider, "modelId", firstModel))
                .when().put("/api/ai-task-config")
                .then().statusCode(200)
                .body("mode", equalTo("unified"))
                .body("tasks", hasSize(3))
                .body("tasks.find { it.taskType == 'data_import' }.modelId", equalTo((int) firstModel))
                .body("tasks.find { it.taskType == 'substitution_recommend' }.modelId", equalTo((int) firstModel));

        as(token).body(Map.of(
                        "mode", "custom",
                        "tasks", List.of(
                                Map.of("taskType", "data_import", "providerId", firstProvider, "modelId", firstModel),
                                Map.of("taskType", "substitution_recommend", "providerId", secondProvider,
                                        "modelId", secondModel),
                                Map.of("taskType", "natural_language_constraints",
                                        "providerId", firstProvider, "modelId", firstModel))))
                .when().put("/api/ai-task-config")
                .then().statusCode(200)
                .body("mode", equalTo("custom"))
                .body("tasks.find { it.taskType == 'data_import' }.providerId", equalTo((int) firstProvider))
                .body("tasks.find { it.taskType == 'substitution_recommend' }.providerId",
                        equalTo((int) secondProvider))
                .body("tasks.find { it.taskType == 'natural_language_constraints' }.providerId",
                        equalTo((int) firstProvider));

        as(token).when().get("/api/ai-task-config")
                .then().statusCode(200)
                .body("mode", equalTo("custom"))
                .body("tasks.find { it.taskType == 'data_import' }.providerName", notNullValue())
                .body("tasks.find { it.taskType == 'substitution_recommend' }.configured", equalTo(true));

        // model must belong to the selected provider
        as(token).body(Map.of("mode", "unified", "providerId", firstProvider, "modelId", secondModel))
                .when().put("/api/ai-task-config")
                .then().statusCode(400)
                .body("message", containsString("模型不属于该供应商"));

        // disabled models cannot be assigned
        as(token).body(Map.of("modelName", "disabled-model", "displayName", "停用", "enabled", false))
                .when().post("/api/ai-providers/" + firstProvider + "/models")
                .then().statusCode(200);
        long disabledModelId = as(token).when().get("/api/ai-providers/" + firstProvider)
                .then().statusCode(200)
                .extract().jsonPath().getLong("models.find { it.modelName == 'disabled-model' }.id");
        as(token).body(Map.of("mode", "unified", "providerId", firstProvider, "modelId", disabledModelId))
                .when().put("/api/ai-task-config")
                .then().statusCode(400)
                .body("message", containsString("停用"));
    }

    @Test
    void firstProviderAndModelBecomeDefault() {
        String token = adminToken();

        as(token).when().get("/api/ai-task-config")
                .then().statusCode(200)
                .body("mode", equalTo("unified"))
                .body("tasks.find { it.taskType == 'data_import' }.configured", equalTo(false))
                .body("tasks.find { it.taskType == 'data_import' }.providerId", nullValue());

        long firstProvider = createProvider(
                token, "首个" + TestData.suffix(), "custom", "http://127.0.0.1:" + stubPort + "/v1", SECRET);
        long firstModel = createModel(token, firstProvider, "deepseek-flash", "Flash", false);

        as(token).when().get("/api/ai-task-config")
                .then().statusCode(200)
                .body("mode", equalTo("unified"))
                .body("tasks.find { it.taskType == 'data_import' }.configured", equalTo(true))
                .body("tasks.find { it.taskType == 'data_import' }.providerId", equalTo((int) firstProvider))
                .body("tasks.find { it.taskType == 'data_import' }.modelId", equalTo((int) firstModel))
                .body("tasks.find { it.taskType == 'substitution_recommend' }.modelId",
                        equalTo((int) firstModel));

        long secondProvider = createProvider(
                token, "第二" + TestData.suffix(), "custom", "http://127.0.0.1:" + stubPort + "/v1", SECRET);
        createModel(token, secondProvider, "other-model", "Other", false);

        as(token).when().get("/api/ai-task-config")
                .then().statusCode(200)
                .body("tasks.find { it.taskType == 'data_import' }.providerId", equalTo((int) firstProvider));
    }

    @Test
    void presetsExposeDefaultBaseUrlsAndModels() {
        String token = adminToken();

        as(token).when().get("/api/ai-providers/presets")
                .then().statusCode(200)
                .body("size()", equalTo(5))
                .body("find { it.code == 'deepseek' }.protocol", equalTo("openai"))
                .body("find { it.code == 'anthropic' }.protocol", equalTo("anthropic"))
                .body("find { it.code == 'anthropic' }.baseUrl", equalTo("https://api.anthropic.com"))
                .body("find { it.code == 'deepseek' }.baseUrl", equalTo("https://api.deepseek.com"))
                .body("find { it.code == 'qwen' }.baseUrl",
                        equalTo("https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .body("find { it.code == 'zhipu' }.baseUrl", equalTo("https://open.bigmodel.cn/api/paas/v4"))
                .body("find { it.code == 'gemini' }.baseUrl",
                        equalTo("https://generativelanguage.googleapis.com/v1beta"))
                .body("find { it.code == 'deepseek' }.models", not(hasSize(0)));
    }

    @Test
    void teacherCannotManageAiProviders() {
        String admin = adminToken();
        String suffix = TestData.suffix();
        createUser(admin, "ai-teacher-" + suffix, "teacher123", "teacher");
        String teacher = login("ai-teacher-" + suffix, "teacher123");

        as(teacher).when().get("/api/ai-providers")
                .then().statusCode(403);
        as(teacher).body(Map.of("name", "越权", "type", "custom", "baseUrl", "https://example.com"))
                .when().post("/api/ai-providers")
                .then().statusCode(403);
        as(teacher).body(Map.of("mode", "unified", "providerId", 1, "modelId", 1))
                .when().put("/api/ai-task-config")
                .then().statusCode(403);

        // model reading remains admin-only as well
        as(teacher).when().get("/api/ai-task-config")
                .then().statusCode(403);
    }

    @Test
    void anonymousIsUnauthorized() {
        given().when().get("/api/ai-providers")
                .then().statusCode(401);
    }

    private long createProvider(String token, String name, String type, String baseUrl, String apiKey) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("name", name);
        payload.put("type", type);
        payload.put("baseUrl", baseUrl);
        if (apiKey != null) {
            payload.put("apiKey", apiKey);
        }
        return as(token).body(payload)
                .when().post("/api/ai-providers")
                .then().statusCode(200)
                .body("id", notNullValue())
                .extract().jsonPath().getLong("id");
    }

    private long createModel(String token, long providerId, String modelName, String displayName,
                             boolean visionCapable) {
        return as(token).body(Map.of(
                        "modelName", modelName,
                        "displayName", displayName,
                        "visionCapable", visionCapable))
                .when().post("/api/ai-providers/" + providerId + "/models")
                .then().statusCode(200)
                .body("id", notNullValue())
                .extract().jsonPath().getLong("id");
    }
}
