package com.paiban.integration;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.support.ApiIntegrationTest;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DingTalkIntegrationTest extends ApiIntegrationTest {

    private void configure(String token, boolean enabled) {
        as(token).body(Map.of(
                        "enabled", enabled,
                        "appKey", "test-key",
                        "appSecret", "test-secret",
                        "agentId", "1000",
                        "syncStrategy", "dingtalk"))
                .when().put("/api/integrations/dingtalk/config")
                .then().statusCode(200)
                .body("enabled", equalTo(enabled));
        as(token).body(Map.of("integration.dingtalk.event-token", "test-event-token"))
                .when().put("/api/settings")
                .then().statusCode(200);
    }

    @AfterEach
    void disableIntegration() {
        try {
            configure(adminToken(), false);
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    @Test
    void configCanBeReadAndUpdated() {
        String token = adminToken();
        configure(token, true);
        as(token).when().get("/api/integrations/dingtalk/config")
                .then().statusCode(200)
                .body("enabled", equalTo(true))
                .body("appKey", equalTo("test-key"))
                .body("hasSecret", equalTo(true))
                .body("syncStrategy", equalTo("dingtalk"));
    }

    @Test
    void syncImportsTeachersFromDingTalk() {
        String token = adminToken();
        configure(token, true);

        as(token).when().post("/api/integrations/dingtalk/sync")
                .then().statusCode(200)
                .body("created", org.hamcrest.Matchers.greaterThanOrEqualTo(0))
                .body("message", notNullValue());

        as(token).when().get("/api/teachers")
                .then().statusCode(200)
                .body("name", hasItem("张三"));

        as(token).when().get("/api/integrations/dingtalk/logs")
                .then().statusCode(200)
                .body("action", hasItem("DINGTALK_SYNC"));
    }

    @Test
    void dingtalkLoginProvisionsLocalAccount() {
        String token = adminToken();
        configure(token, true);
        as(token).when().post("/api/integrations/dingtalk/sync").then().statusCode(200);

        Response response = io.restassured.RestAssured.given()
                .contentType("application/json")
                .body(Map.of("authCode", "fake:EMP001"))
                .when().post("/api/auth/dingtalk");
        response.then().statusCode(200)
                .body("token", notNullValue())
                .body("user.username", equalTo("EMP001"))
                .body("user.roles", hasItem("teacher"));
    }

    @Test
    void disabledIntegrationRejectsDingTalkLogin() {
        configure(adminToken(), false);
        io.restassured.RestAssured.given()
                .contentType("application/json")
                .body(Map.of("authCode", "fake:EMP001"))
                .when().post("/api/auth/dingtalk")
                .then().statusCode(400);
    }

    @Test
    void leaveEventDeactivatesTeacher() {
        String token = adminToken();
        configure(token, true);
        as(token).when().post("/api/integrations/dingtalk/sync").then().statusCode(200);

        io.restassured.RestAssured.given()
                .contentType("application/json")
                .header("X-DingTalk-Token", "test-event-token")
                .body(Map.of("type", "user_leave", "userId", "dd-002", "name", "李四"))
                .when().post("/api/integrations/dingtalk/event")
                .then().statusCode(200);

        List<Map<String, Object>> teachers = as(token).when().get("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getList("$");
        Map<String, Object> li = teachers.stream()
                .filter(teacher -> "李四".equals(teacher.get("name")))
                .findFirst()
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(li.get("active")).isEqualTo(false);
    }

    @Test
    void eventEndpointRejectsMissingToken() {
        configure(adminToken(), true);
        io.restassured.RestAssured.given()
                .contentType("application/json")
                .body(Map.of("type", "user_leave", "userId", "dd-002"))
                .when().post("/api/integrations/dingtalk/event")
                .then().statusCode(403);
    }

    @Test
    void syncStrategyMustBeValid() {
        as(adminToken()).body(Map.of("syncStrategy", "unknown"))
                .when().put("/api/integrations/dingtalk/config")
                .then().statusCode(400);
    }
}
