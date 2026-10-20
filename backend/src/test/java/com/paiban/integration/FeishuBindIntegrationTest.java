package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.integration.auth.FeishuBindTokenService;
import com.paiban.integration.feishu.FeishuUser;
import com.paiban.support.ApiIntegrationTest;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 05: a teacher arriving from Feishu without a bound identity proves who
 * they are with name + employee number/phone, then receives a local session.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FeishuBindIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FeishuBindTokenService bindTokens;

    @Autowired
    private AuditLogRepository auditLogs;

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

    private Long createTeacher(String name, String employeeNo, String phone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (employeeNo != null) {
            body.put("employeeNo", employeeNo);
        }
        if (phone != null) {
            body.put("phone", phone);
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

    /** Mirrors the real flow: an unbound Feishu user calls login and receives a bind token. */
    private String bindTokenFromLogin(String code) {
        return io.restassured.RestAssured.given()
                .contentType("application/json")
                .body(Map.of("code", code))
                .when().post("/api/auth/feishu")
                .then().statusCode(200)
                .body("status", equalTo("binding_required"))
                .body("bindToken", notNullValue())
                .extract().jsonPath().getString("bindToken");
    }

    private Response bind(String bindToken, String name, String employeeNo, String phone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bindToken", bindToken);
        body.put("name", name);
        if (employeeNo != null) {
            body.put("employeeNo", employeeNo);
        }
        if (phone != null) {
            body.put("phone", phone);
        }
        return io.restassured.RestAssured.given()
                .contentType("application/json")
                .body(body)
                .when().post("/api/auth/feishu/bind");
    }

    @Test
    void bindingByEmployeeNoIssuesASessionAndPersistsTheIdentity() {
        configure(true);
        Long teacherId = createTeacher("张三", "EMP001", "13800000001");
        createUser("bind-zhangsan", "张三", teacherId);

        String bindToken = bindTokenFromLogin("fake:EMP001");

        bind(bindToken, "张三", "EMP001", null)
                .then().statusCode(200)
                .body("token", notNullValue())
                .body("expiresInMinutes", notNullValue())
                .body("user.username", equalTo("bind-zhangsan"))
                .body("user.displayName", equalTo("张三"));

        as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200)
                .body("find { it.name == '张三' }.feishuUserId", equalTo("fs-001"))
                .body("find { it.name == '张三' }.feishuOpenId", equalTo("ou-001"))
                .body("find { it.name == '张三' }.bindingStatus", equalTo("feishu"));

        assertThat(auditLogs.findAll())
                .filteredOn(log -> "FEISHU_BIND".equals(log.getAction()))
                .singleElement()
                .satisfies(log -> assertThat(log.getDetail()).contains("张三"));
    }

    @Test
    void bindingTwiceForTheSameTeacherIsIdempotent() {
        configure(true);
        Long teacherId = createTeacher("张三", "EMP001", "13800000001");
        createUser("bind-twice", "张三", teacherId);

        String bindToken = bindTokenFromLogin("fake:EMP001");
        bind(bindToken, "张三", "EMP001", null).then().statusCode(200);

        // A fresh proof for the same Feishu identity must not conflict with itself.
        String second = bindTokens.issue(new FeishuUser("fs-001", "ou-001", "张三", null, null, null));
        bind(second, "张三", "EMP001", null).then().statusCode(200).body("token", notNullValue());

        as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200)
                .body("find { it.name == '张三' }.feishuUserId", equalTo("fs-001"));
    }

    @Test
    void wrongNameIsNotFound() {
        configure(true);
        Long teacherId = createTeacher("张三", "EMP001", "13800000001");
        createUser("bind-wrong-name", "张三", teacherId);
        String bindToken = bindTokens.issue(new FeishuUser("fs-001", "ou-001", "张三", null, null, null));

        bind(bindToken, "张三丰", "EMP001", null)
                .then().statusCode(404)
                .body("message", equalTo("未找到您的信息，请联系教务管理员"));
    }

    @Test
    void phoneMatchResolvesTheTeacher() {
        configure(true);
        Long teacherId = createTeacher("李四", "EMP002", "13900000002");
        createUser("bind-lisi", "李四", teacherId);
        String bindToken = bindTokenFromLogin("fake:EMP002");

        bind(bindToken, "李四", null, "13900000002")
                .then().statusCode(200)
                .body("token", notNullValue())
                .body("user.displayName", equalTo("李四"));

        as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200)
                .body("find { it.name == '李四' }.feishuUserId", equalTo("fs-002"));
    }

    @Test
    void feishuIdentityAlreadyBoundToAnotherTeacherConflicts() {
        configure(true);
        Long boundTeacher = createTeacher("张三", "EMP001", "13800000001");
        createUser("bind-owner", "张三", boundTeacher);
        // First teacher owns fs-001.
        bind(bindTokens.issue(new FeishuUser("fs-001", "ou-001", "张三", null, null, null)),
                "张三", "EMP001", null).then().statusCode(200);

        Long other = createTeacher("王五", "EMP009", "13900000009");
        createUser("bind-wangwu", "王五", other);

        bind(bindTokens.issue(new FeishuUser("fs-001", "ou-001", "张三", null, null, null)),
                "王五", "EMP009", null)
                .then().statusCode(409)
                .body("message", equalTo("该飞书账号已绑定其他教师，请联系教务管理员"));
    }

    @Test
    void teacherAlreadyBoundToAnotherFeishuIdentityConflicts() {
        configure(true);
        Long teacherId = as(adminToken()).body(Map.of(
                        "name", "张三",
                        "employeeNo", "EMP001",
                        "phone", "13800000001",
                        "feishuUserId", "fs-999",
                        "feishuOpenId", "ou-999"))
                .when().post("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        createUser("bind-other-identity", "张三", teacherId);

        bind(bindTokens.issue(new FeishuUser("fs-001", "ou-001", "张三", null, null, null)),
                "张三", "EMP001", null)
                .then().statusCode(409)
                .body("message", equalTo("该教师已绑定其他飞书账号，请联系教务管理员"));
    }

    @Test
    void invalidBindTokenIsUnauthorized() {
        configure(true);

        bind("not-a-token", "张三", "EMP001", null)
                .then().statusCode(401)
                .body("message", equalTo("绑定链接已过期，请重新从飞书进入"));
    }

    @Test
    void missingIdentifiersIsBadRequest() {
        configure(true);
        String bindToken = bindTokens.issue(new FeishuUser("fs-001", "ou-001", "张三", null, null, null));

        bind(bindToken, "张三", null, null).then().statusCode(400);
        bind(bindToken, "", "EMP001", null).then().statusCode(400);
    }

    @Test
    void teacherWithoutAnEnabledAccountConflicts() {
        configure(true);
        createTeacher("张三", "EMP001", "13800000001");

        bind(bindTokens.issue(new FeishuUser("fs-001", "ou-001", "张三", null, null, null)),
                "张三", "EMP001", null)
                .then().statusCode(409)
                .body("message", equalTo("该教师尚未开通系统账号，请联系教务管理员"));
    }

    @Test
    void employeeNoAndPhoneMustResolveToTheSameTeacher() {
        configure(true);
        Long first = createTeacher("张三", "EMP001", "13800000001");
        createUser("bind-mismatch", "张三", first);
        Long second = createTeacher("李四", "EMP002", "13900000002");
        createUser("bind-mismatch-2", "李四", second);

        bind(bindTokens.issue(new FeishuUser("fs-001", "ou-001", "张三", null, null, null)),
                "张三", "EMP001", "13900000002")
                .then().statusCode(404)
                .body("message", equalTo("未找到您的信息，请联系教务管理员"));
    }

    @Test
    void unboundTeacherStillGetsNoSessionFromLogin() {
        configure(true);
        createTeacher("张三", "EMP001", "13800000001");

        io.restassured.RestAssured.given()
                .contentType("application/json")
                .body(Map.of("code", "fake:EMP001"))
                .when().post("/api/auth/feishu")
                .then().statusCode(200)
                .body("status", equalTo("binding_required"))
                .body("token", nullValue());
    }
}
