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
                // 票 01：预置的 admin 账号是超级管理员（旧 admin 角色编码改给教务管理员）。
                .body("roles", hasItem("super_admin"))
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

        Long id = createUser(adminToken, username, "head12345", "teacher");

        as(adminToken).when().get("/api/users")
                .then().statusCode(200)
                .body("username", hasItem(username));

        as(adminToken).when().get("/api/users")
                .then().body("find { it.username == '" + username + "' }.roles", contains("teacher"));

        String headToken = login(username, "head12345");
        as(headToken).when().get("/api/auth/me")
                .then().statusCode(200)
                .body("id", equalTo(id.intValue()))
                // 票 01：老师拿到只读的「查看全部课表」。
                .body("permissions", hasItem("timetable:view"));
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
                .body("code", hasItem("super_admin"))
                .body("code", org.hamcrest.Matchers.not(hasItem("head_teacher")));
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

    @Test
    void compositeEditSavesNameRolesAndEnabledStateTogether() {
        String adminToken = adminToken();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "comp_" + suffix;
        Long id = createUser(adminToken, username, "comp12345", "teacher");

        // 票 01：姓名、身份、启用状态一次提交，三个字段都必须落库（停用不能吞掉改名/改身份）。
        as(adminToken)
                .body(Map.of(
                        "displayName", "复合改名",
                        "roleCodes", List.of("admin"),
                        "enabled", false))
                .when().put("/api/users/" + id)
                .then().statusCode(200);

        as(adminToken).when().get("/api/users")
                .then().statusCode(200)
                .body("find { it.username == '" + username + "' }.displayName", equalTo("复合改名"))
                .body("find { it.username == '" + username + "' }.roles", contains("admin"))
                .body("find { it.username == '" + username + "' }.enabled", equalTo(false));
    }
}
