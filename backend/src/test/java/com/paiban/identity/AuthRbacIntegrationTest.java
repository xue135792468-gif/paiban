package com.paiban.identity;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.support.ApiIntegrationTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthRbacIntegrationTest extends ApiIntegrationTest {

    @Test
    void administratorCanLogInAndReceivesTokenWithPermissions() {
        String token = login("admin", "admin123");
        org.assertj.core.api.Assertions.assertThat(token).isNotBlank();

        as(token).when().get("/api/auth/me")
                .then().statusCode(200)
                .body("username", equalTo("admin"))
                .body("roles", hasItem("admin"))
                .body("permissions", hasItem("user:manage"));
    }

    @Test
    void h2ConsoleIsNotOpenWhenConsoleIsDisabled() {
        // 生产语义：未启用 H2 控制台的环境不放行 /h2-console/**（e2e profile 才显式启用）。
        io.restassured.RestAssured.given()
                .when().get("/h2-console/")
                .then().statusCode(org.hamcrest.Matchers.anyOf(equalTo(401), equalTo(403)));
    }

    @Test
    void wrongPasswordIsRejectedWith401() {
        io.restassured.RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("username", "admin", "password", "wrong-password"))
                .when().post("/api/auth/login")
                .then().statusCode(401);
    }

    @Test
    void anonymousRequestsAreRejectedWith401() {
        io.restassured.RestAssured.given()
                .when().get("/api/users")
                .then().statusCode(401);
    }

    @Test
    void teacherCannotManageUsers() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "teacher_" + suffix;
        createUser(adminToken(), username, "teacher123", "teacher");

        String teacherToken = login(username, "teacher123");
        as(teacherToken).when().get("/api/users")
                .then().statusCode(403);
    }

    @Test
    void administratorCanCreateUsersAndAssignRoles() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "head_" + suffix;
        String adminToken = adminToken();

        Long id = createUser(adminToken, username, "head12345", "head_teacher");

        as(adminToken).when().get("/api/users")
                .then().statusCode(200)
                .body("username", hasItem(username));

        as(adminToken).when().get("/api/users")
                .then().body("find { it.username == '" + username + "' }.roles", contains("head_teacher"));

        String headToken = login(username, "head12345");
        as(headToken).when().get("/api/auth/me")
                .then().statusCode(200)
                .body("id", equalTo(id.intValue()))
                .body("permissions", hasItem("timetable:view:class"));
    }

    @Test
    void administratorCanResetPassword() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "reset_" + suffix;
        String adminToken = adminToken();
        Long id = createUser(adminToken, username, "initial123", "teacher");

        as(adminToken)
                .body(Map.of("newPassword", "changed123"))
                .when().post("/api/users/" + id + "/reset-password")
                .then().statusCode(200);

        org.assertj.core.api.Assertions.assertThat(login(username, "changed123")).isNotBlank();
    }

    @Test
    void rolesEndpointExposesThreePresetRoles() {
        as(adminToken()).when().get("/api/roles")
                .then().statusCode(200)
                .body("code", hasItem("admin"))
                .body("code", hasItem("teacher"))
                .body("code", hasItem("head_teacher"));
    }

    @Test
    void creatingDuplicateUsernameReturnsConflict() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "dup_" + suffix;
        String adminToken = adminToken();
        createUser(adminToken, username, "dup12345", "teacher");

        as(adminToken)
                .body(Map.of(
                        "username", username,
                        "password", "dup12345",
                        "displayName", "重复",
                        "roleCodes", List.of("teacher")))
                .when().post("/api/users")
                .then().statusCode(409);
    }
}
