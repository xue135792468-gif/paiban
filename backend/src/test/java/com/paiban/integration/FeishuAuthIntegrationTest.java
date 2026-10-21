package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.paiban.integration.auth.FeishuBindTokenService;
import com.paiban.integration.feishu.FeishuUser;
import com.paiban.support.ApiIntegrationTest;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 02: Feishu H5免登. Codes are single-use, so the controller must exchange
 * the authorization code exactly once and then map the returned user locally.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FeishuAuthIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FeishuBindTokenService bindTokens;

    @BeforeEach
    void resetSettingsBaseline() {
        resetIntegrationSettings();
    }

    @AfterEach
    void disableIntegration() {
        try {
            configure(false);
        } catch (RuntimeException ignored) {
            // best effort: never leak an enabled integration into the next test class
        }
    }

    private void configure(boolean enabled) {
        as(adminToken()).body(Map.of(
                        "integration.feishu.enabled", String.valueOf(enabled),
                        "integration.feishu.app-id", "test-app-id",
                        "integration.feishu.app-secret", "test-app-secret"))
                .when().put("/api/settings")
                .then().statusCode(200);
    }

    private Long createTeacher(String name, String dingtalkUserId, String feishuUserId, String feishuOpenId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (dingtalkUserId != null) {
            body.put("dingtalkUserId", dingtalkUserId);
        }
        if (feishuUserId != null) {
            body.put("feishuUserId", feishuUserId);
        }
        if (feishuOpenId != null) {
            body.put("feishuOpenId", feishuOpenId);
        }
        return as(adminToken()).body(body)
                .when().post("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    private void createUser(String username, String displayName, Long teacherId) {
        as(adminToken()).body(Map.of(
                        "username", username,
                        "password", "teacher123",
                        "displayName", displayName,
                        "roleCodes", List.of("teacher"),
                        "teacherId", teacherId))
                .when().post("/api/users").then().statusCode(200);
    }

    @Test
    void publicConfigExposesEnabledAndAppId() {
        configure(true);

        io.restassured.RestAssured.given()
                .when().get("/api/auth/feishu/config")
                .then().statusCode(200)
                .body("enabled", equalTo(true))
                .body("appId", equalTo("test-app-id"));
    }

    @Test
    void boundTeacherLogsInWithASingleCodeExchange() {
        configure(true);
        Long teacherId = createTeacher("张三", null, "fs-001", "ou-001");
        createUser("fs-zhangsan", "张三", teacherId);

        as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200)
                .body("find { it.name == '张三' }.feishuUserId", equalTo("fs-001"))
                .body("find { it.name == '张三' }.bindingStatus", equalTo("feishu"));

        Response response = io.restassured.RestAssured.given()
                .contentType("application/json")
                .body(Map.of("code", "fake:EMP001"))
                .when().post("/api/auth/feishu")
                .then().statusCode(200)
                .body("status", equalTo("ok"))
                .body("token", notNullValue())
                .body("expiresInMinutes", notNullValue())
                .body("user.username", equalTo("fs-zhangsan"))
                .body("user.displayName", equalTo("张三"))
                .body("bindToken", nullValue())
                .extract().response();

        // A session token must never be accepted as a bind token (purpose-separated key).
        assertThat(bindTokens.parse(response.jsonPath().getString("token"))).isEmpty();
    }

    @Test
    void unboundTeacherGetsABindTokenInsteadOfAToken() {
        configure(true);
        createTeacher("张三", null, "fs-001", "ou-001");

        Response response = io.restassured.RestAssured.given()
                .contentType("application/json")
                .body(Map.of("code", "fake:EMP001"))
                .when().post("/api/auth/feishu")
                .then().statusCode(200)
                .body("status", equalTo("binding_required"))
                .body("token", nullValue())
                .body("user", nullValue())
                .body("bindToken", notNullValue())
                .extract().response();

        assertThat(bindTokens.parse(response.jsonPath().getString("bindToken")))
                .contains(new FeishuUser("fs-001", "ou-001", "张三", null, null, null));
        assertThat(bindTokens.parse("not-a-token")).isEmpty();
    }

    @Test
    void disabledIntegrationRejectsFeishuLogin() {
        configure(false);

        io.restassured.RestAssured.given()
                .contentType("application/json")
                .body(Map.of("code", "fake:EMP001"))
                .when().post("/api/auth/feishu")
                .then().statusCode(400);
    }

    @Test
    void unknownCodeIsUnauthorized() {
        configure(true);

        io.restassured.RestAssured.given()
                .contentType("application/json")
                .body(Map.of("code", "fake:UNKNOWN"))
                .when().post("/api/auth/feishu")
                .then().statusCode(401);
    }

    @Test
    void teacherBindingStatusReflectsBothPlatforms() {
        configure(true);
        createTeacher("王五", "dd-005", "fs-005", "ou-005");

        as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200)
                .body("find { it.name == '王五' }.dingtalkUserId", equalTo("dd-005"))
                .body("find { it.name == '王五' }.feishuOpenId", equalTo("ou-005"))
                .body("find { it.name == '王五' }.bindingStatus", equalTo("both"));
    }
}
