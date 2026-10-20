package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;

import com.paiban.support.ApiIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 03: admin-facing Feishu configuration, full sync and the public event
 * callback. Everything is exercised through the HTTP contract; the Feishu client
 * is the deterministic fake from the test profile.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FeishuOrgSyncIntegrationTest extends ApiIntegrationTest {

    private static final String VERIFICATION_TOKEN = "feishu-verify-token";

    @BeforeEach
    void resetSettingsBaseline() {
        resetIntegrationSettings();
    }

    @AfterEach
    void disableIntegration() {
        try {
            as(adminToken()).body(Map.of("enabled", false))
                    .when().put("/api/integrations/feishu/config")
                    .then().statusCode(200);
        } catch (RuntimeException ignored) {
            // best effort: never leak an enabled integration into the next test class
        }
    }

    private void configure(Map<String, Object> overrides) {
        Map<String, Object> body = new HashMap<>();
        body.put("enabled", true);
        body.put("appId", "cli_test_app");
        body.put("appSecret", "test-app-secret");
        body.put("verificationToken", VERIFICATION_TOKEN);
        body.put("syncStrategy", "feishu");
        body.putAll(overrides);
        as(adminToken()).body(body)
                .when().put("/api/integrations/feishu/config")
                .then().statusCode(200);
    }

    private void configure() {
        configure(Map.of());
    }

    private Response postEvent(String token, Map<String, Object> body) {
        var spec = RestAssured.given().contentType(ContentType.JSON);
        if (token != null) {
            spec = spec.header("X-Feishu-Token", token);
        }
        return spec.body(body).when().post("/api/integrations/feishu/event");
    }

    private Map<String, Object> teacherByEmployeeNo(String employeeNo) {
        return as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200).extract().jsonPath()
                .getMap("find { it.employeeNo == '" + employeeNo + "' }");
    }

    private List<Map<String, Object>> teachers() {
        return as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getList("$");
    }

    @Test
    void configRoundTripNeverReturnsTheSecret() {
        configure();

        Response config = as(adminToken()).when().get("/api/integrations/feishu/config");

        config.then().statusCode(200)
                .body("enabled", is(true))
                .body("appId", is("cli_test_app"))
                .body("hasSecret", is(true))
                .body("syncStrategy", is("feishu"))
                .body("verificationTokenConfigured", is(true));
        assertThat(config.asString()).doesNotContain("appSecret").doesNotContain("test-app-secret");

        // A blank secret means "unchanged": the stored secret survives the round-trip.
        as(adminToken()).body(Map.of("enabled", true, "appId", "cli_test_app", "appSecret", ""))
                .when().put("/api/integrations/feishu/config")
                .then().statusCode(200)
                .body("hasSecret", is(true));
    }

    @Test
    void settingsEndpointDoesNotExposeTheVerificationToken() {
        configure();

        Response all = as(adminToken()).when().get("/api/settings");

        all.then().statusCode(200);
        assertThat(all.asString())
                .doesNotContain("integration.feishu.verification-token")
                .doesNotContain(VERIFICATION_TOKEN);
    }

    @Test
    void manualSyncImportsTeachersAndWritesAnAuditLog() {
        configure();

        as(adminToken()).when().post("/api/integrations/feishu/sync")
                .then().statusCode(200)
                .body("created", is(2))
                .body("updated", is(0))
                .body("message", containsString("新增 2"));

        as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200)
                .body("name", hasItems("张三", "李四"));

        as(adminToken()).when().get("/api/integrations/feishu/logs")
                .then().statusCode(200)
                .body("action", hasItem("FEISHU_SYNC"));
    }

    @Test
    void contactEventsUpsertAndDeactivateWithoutDeleting() {
        configure();

        postEvent(VERIFICATION_TOKEN, Map.of(
                        "schema", "2.0",
                        "header", Map.of("event_type", "contact.user.created_v3", "token", VERIFICATION_TOKEN),
                        "event", Map.of("object", Map.of(
                                "user_id", "fs-100",
                                "open_id", "ou-100",
                                "name", "王五",
                                "mobile", "13700000000",
                                "email", "wang@example.com",
                                "employee_no", "EMP100"))))
                .then().statusCode(200);

        Map<String, Object> created = teacherByEmployeeNo("EMP100");
        assertThat(created).isNotNull();
        assertThat(created.get("name")).isEqualTo("王五");
        assertThat(created.get("phone")).isEqualTo("13700000000");
        assertThat(created.get("active")).isEqualTo(true);

        postEvent(VERIFICATION_TOKEN, Map.of(
                        "schema", "2.0",
                        "header", Map.of("event_type", "contact.user.updated_v3"),
                        "event", Map.of("object", Map.of(
                                "user_id", "fs-100",
                                "open_id", "ou-100",
                                "name", "王五改",
                                "mobile", "13700000009",
                                "email", "wang@example.com",
                                "employee_no", "EMP100"))))
                .then().statusCode(200);

        Map<String, Object> updated = teacherByEmployeeNo("EMP100");
        assertThat(updated.get("name")).isEqualTo("王五改");
        assertThat(updated.get("phone")).isEqualTo("13700000009");
        assertThat(teachers()).filteredOn(t -> "EMP100".equals(t.get("employeeNo"))).hasSize(1);

        postEvent(VERIFICATION_TOKEN, Map.of(
                        "schema", "2.0",
                        "header", Map.of("event_type", "contact.user.deleted_v3"),
                        "event", Map.of("object", Map.of(
                                "user_id", "fs-100",
                                "open_id", "ou-100"))))
                .then().statusCode(200);

        Map<String, Object> deleted = teacherByEmployeeNo("EMP100");
        assertThat(deleted).isNotNull();
        assertThat(deleted.get("active")).isEqualTo(false);
        assertThat(teachers()).filteredOn(t -> "EMP100".equals(t.get("employeeNo"))).hasSize(1);
    }

    @Test
    void eventEndpointEnforcesTokenChallengeAndEncryptionRules() {
        configure();

        // URL verification echoes the challenge even before any event handling.
        postEvent(null, Map.of("challenge", "challenge-value", "type", "url_verification"))
                .then().statusCode(200)
                .body("challenge", is("challenge-value"));

        postEvent("wrong-token", Map.of(
                        "header", Map.of("event_type", "contact.user.created_v3"),
                        "event", Map.of("object", Map.of("user_id", "fs-200", "name", "赵六"))))
                .then().statusCode(403);

        postEvent(null, Map.of(
                        "header", Map.of("event_type", "contact.user.created_v3"),
                        "event", Map.of("object", Map.of("user_id", "fs-200", "name", "赵六"))))
                .then().statusCode(403);

        postEvent(VERIFICATION_TOKEN, Map.of("encrypt", "ciphertext"))
                .then().statusCode(400)
                .body("message", containsString("加密"));

        as(adminToken()).body(Map.of("syncStrategy", "dingtalk"))
                .when().put("/api/integrations/feishu/config")
                .then().statusCode(400);

        as(adminToken()).body(Map.of("enabled", false))
                .when().put("/api/integrations/feishu/config")
                .then().statusCode(200);
        postEvent(VERIFICATION_TOKEN, Map.of(
                        "header", Map.of("event_type", "contact.user.created_v3"),
                        "event", Map.of("object", Map.of("user_id", "fs-200", "name", "赵六"))))
                .then().statusCode(400);
    }

    @Test
    void localStrategyKeepsExistingValuesWhileFeishuStrategyOverwritesThem() {
        as(adminToken()).body(Map.of(
                        "name", "本地张",
                        "employeeNo", "EMP001",
                        "phone", "13900000000",
                        "email", "local@example.com"))
                .when().post("/api/teachers")
                .then().statusCode(200);

        configure(Map.of("syncStrategy", "local"));
        as(adminToken()).when().post("/api/integrations/feishu/sync").then().statusCode(200);

        Map<String, Object> afterLocal = teacherByEmployeeNo("EMP001");
        assertThat(afterLocal.get("name")).isEqualTo("本地张");
        assertThat(afterLocal.get("phone")).isEqualTo("13900000000");
        assertThat(afterLocal.get("email")).isEqualTo("local@example.com");

        configure(Map.of("syncStrategy", "feishu"));
        as(adminToken()).when().post("/api/integrations/feishu/sync").then().statusCode(200);

        Map<String, Object> afterFeishu = teacherByEmployeeNo("EMP001");
        assertThat(afterFeishu.get("name")).isEqualTo("张三");
        assertThat(afterFeishu.get("phone")).isEqualTo("13800000001");
        assertThat(afterFeishu.get("email")).isEqualTo("zhang@example.com");
    }
}
