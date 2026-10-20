package com.paiban.support;

import com.paiban.common.settings.SettingsService;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Shared setup for REST-level integration tests. Tests exercise the public
 * HTTP contract only, per the testing decisions in the spec.
 */
@ExtendWith(DatabaseResetExtension.class)
public abstract class ApiIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected SettingsService settingsService;

    /**
     * Explicit, order-independent baseline for the integration flags and credentials.
     * Classes that assert integration behaviour call this from {@code @BeforeEach}
     * instead of relying on defaults or on what the previous test left behind.
     */
    protected void resetIntegrationSettings() {
        settingsService.put(SettingsService.ACTIVE_PLATFORM, SettingsService.PLATFORM_NONE);
        settingsService.put(SettingsService.FEISHU_ENABLED, "false");
        settingsService.put(SettingsService.DINGTALK_ENABLED, "false");
        settingsService.put(SettingsService.FEISHU_APP_ID, "");
        settingsService.put(SettingsService.FEISHU_APP_SECRET, "");
        settingsService.put(SettingsService.DINGTALK_APP_KEY, "");
        settingsService.put(SettingsService.DINGTALK_APP_SECRET, "");
    }

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        RestAssured.basePath = "";
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    protected String login(String username, String password) {
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", password))
                .when().post("/api/auth/login");
        response.then().statusCode(200);
        return response.jsonPath().getString("token");
    }

    protected io.restassured.specification.RequestSpecification as(String token) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token);
    }

    protected String adminToken() {
        return login("admin", "admin123");
    }

    protected Long createUser(String adminToken, String username, String password, String role) {
        Response response = as(adminToken)
                .body(Map.of(
                        "username", username,
                        "password", password,
                        "displayName", username,
                        "roleCodes", java.util.List.of(role)))
                .when().post("/api/users");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }
}
