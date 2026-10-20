package com.paiban.support;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Shared setup for REST-level integration tests. Tests exercise the public
 * HTTP contract only, per the testing decisions in the spec.
 */
public abstract class ApiIntegrationTest {

    @LocalServerPort
    protected int port;

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
