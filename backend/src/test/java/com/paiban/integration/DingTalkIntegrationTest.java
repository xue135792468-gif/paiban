package com.paiban.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.integration.dingtalk.DingTalkApiException;
import com.paiban.integration.dingtalk.FakeDingTalkClient;
import com.paiban.integration.domain.DingUser;
import com.paiban.support.ApiIntegrationTest;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DingTalkIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FakeDingTalkClient fakeDingTalk;

    /** The fake client is a shared bean; every test starts from the same two teachers. */
    @BeforeEach
    void resetFakeDirectory() {
        fakeDingTalk.resetToDefault();
    }

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
    void dingtalkLoginRequiresAnExistingLocalAccount() {
        String token = adminToken();
        configure(token, true);
        as(token).when().post("/api/integrations/dingtalk/sync").then().statusCode(200);

        // No local account linked to the teacher yet: rejected, never auto-provisioned.
        io.restassured.RestAssured.given()
                .contentType("application/json")
                .body(Map.of("authCode", "fake:EMP001"))
                .when().post("/api/auth/dingtalk")
                .then().statusCode(401);

        Long teacherId = as(token).when().get("/api/teachers")
                .then().statusCode(200).extract().jsonPath()
                .getLong("find { it.name == '张三' }.id");
        as(token).body(Map.of(
                        "username", "zhangsan",
                        "password", "teacher123",
                        "displayName", "张三",
                        "roleCodes", List.of("teacher"),
                        "teacherId", teacherId))
                .when().post("/api/users").then().statusCode(200);

        io.restassured.RestAssured.given()
                .contentType("application/json")
                .body(Map.of("authCode", "fake:EMP001"))
                .when().post("/api/auth/dingtalk")
                .then().statusCode(200)
                .body("token", notNullValue())
                .body("user.username", equalTo("zhangsan"))
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
    void syncWalksNestedDepartmentsAndDeduplicatesAcrossBranches() {
        String token = adminToken();
        configure(token, true);
        fakeDingTalk.clearDirectory()
                .addDepartment(2, "全校教职工", 1)
                .addDepartment(3, "高一年级", 2)
                .addDepartment(4, "高二年级", 2)
                .addDepartment(5, "食堂", 1)
                .addDepartment(-7, "家校通讯录", 1);
        fakeDingTalk.addUser(3, new DingUser("dd-101", "王老师", "13800000101", null, "EMP101"));
        // The same person sits in two branches: import once, keep both departments.
        fakeDingTalk.addUser(4, new DingUser("dd-101", "王老师", "13800000101", null, "EMP101"));
        fakeDingTalk.addUser(5, new DingUser("dd-201", "厨师甲", null, null, null));
        fakeDingTalk.addUser(-7, new DingUser("dd-301", "家长甲", null, null, null));

        as(token).when().post("/api/integrations/dingtalk/sync")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("created", equalTo(2))
                .body("departmentsScanned", equalTo(5))
                .body("usersScanned", equalTo(2))
                .body("message", org.hamcrest.Matchers.containsString("扫描 5 个部门 / 2 人"));

        List<String> names = teacherNames(token);
        assertThat(names).containsExactlyInAnyOrder("王老师", "厨师甲");
        assertThat(names).doesNotContain("家长甲");
        assertThat(fakeDingTalk.userListCalls(-7)).isZero();
    }

    @Test
    void syncMatchesAnExistingTeacherByEmployeeNumberInsteadOfCreatingADuplicate() {
        String token = adminToken();
        configure(token, true);
        as(token).body(Map.of("name", "旧名字", "employeeNo", "EMP101"))
                .when().post("/api/teachers").then().statusCode(200);
        fakeDingTalk.clearDirectory().addDepartment(2, "全校教职工", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-101", "王老师", "13800000101", null, "EMP101"));

        as(token).when().post("/api/integrations/dingtalk/sync")
                .then().statusCode(200)
                .body("created", equalTo(0))
                .body("updated", equalTo(1));

        List<Map<String, Object>> teachers = teachers(token);
        assertThat(teachers).hasSize(1);
        assertThat(teachers.get(0).get("name")).isEqualTo("王老师");
        assertThat(teachers.get(0).get("dingtalkUserId")).isEqualTo("dd-101");
    }

    @Test
    void syncMatchesAnExistingTeacherByPhoneWhenThereIsNoEmployeeNumber() {
        String token = adminToken();
        configure(token, true);
        as(token).body(Map.of("name", "手机老师", "phone", "13900000111"))
                .when().post("/api/teachers").then().statusCode(200);
        fakeDingTalk.clearDirectory().addDepartment(2, "全校教职工", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-102", "手机老师", "13900000111", null, null));

        as(token).when().post("/api/integrations/dingtalk/sync")
                .then().statusCode(200)
                .body("created", equalTo(0))
                .body("updated", equalTo(1));

        assertThat(teachers(token)).hasSize(1);
    }

    @Test
    void syncImportsLeaversFromALeaverDepartmentAsInactive() {
        String token = adminToken();
        configure(token, true);
        fakeDingTalk.clearDirectory().addDepartment(2, "离职人员", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-900", "离职老师", null, null, "EMP900"));

        as(token).when().post("/api/integrations/dingtalk/sync")
                .then().statusCode(200)
                .body("created", equalTo(1))
                .body("deactivated", equalTo(1));

        List<Map<String, Object>> teachers = teachers(token);
        assertThat(teachers).hasSize(1);
        assertThat(teachers.get(0).get("active")).isEqualTo(false);
    }

    @Test
    void syncImportsUsersDingTalkMarksInactiveAsInactive() {
        String token = adminToken();
        configure(token, true);
        fakeDingTalk.clearDirectory();
        fakeDingTalk.addUser(1, new DingUser("dd-800", "停用老师", null, null, "EMP800", false, List.of()));

        as(token).when().post("/api/integrations/dingtalk/sync")
                .then().statusCode(200)
                .body("deactivated", equalTo(1));

        assertThat(teachers(token).get(0).get("active")).isEqualTo(false);
    }

    @Test
    void syncDoesNotDeactivateTeachersMissingFromTheRun() {
        String token = adminToken();
        configure(token, true);
        as(token).body(Map.of("name", "手工老师", "dingtalkUserId", "dd-999"))
                .when().post("/api/teachers").then().statusCode(200);

        as(token).when().post("/api/integrations/dingtalk/sync").then().statusCode(200);

        Map<String, Object> manual = teachers(token).stream()
                .filter(teacher -> "手工老师".equals(teacher.get("name")))
                .findFirst().orElseThrow();
        assertThat(manual.get("active")).isEqualTo(true);
    }

    @Test
    void syncReportsScanCountersInTheAuditLog() {
        String token = adminToken();
        configure(token, true);

        as(token).when().post("/api/integrations/dingtalk/sync").then().statusCode(200);

        as(token).when().get("/api/integrations/dingtalk/logs")
                .then().statusCode(200)
                .body("detail", hasItem(org.hamcrest.Matchers.containsString("扫描 1 个部门 / 2 人")));
    }

    @Test
    void syncFailureCarriesTheDingTalkSubCode() {
        String token = adminToken();
        configure(token, true);
        fakeDingTalk.clearDirectory().failUserList(
                1, new DingTalkApiException("拉取部门用户", 88, 60020, "ip not in whitelist"));

        as(token).when().post("/api/integrations/dingtalk/sync")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("message", org.hamcrest.Matchers.containsString("sub_code=60020"))
                .body("message", org.hamcrest.Matchers.containsString("IP 白名单"));
        assertThat(teachers(token)).isEmpty();
    }

    @Test
    void syncStrategyMustBeValid() {
        as(adminToken()).body(Map.of("syncStrategy", "unknown"))
                .when().put("/api/integrations/dingtalk/config")
                .then().statusCode(400);
    }

    private List<String> teacherNames(String token) {
        return teachers(token).stream().map(teacher -> (String) teacher.get("name")).toList();
    }

    private List<Map<String, Object>> teachers(String token) {
        return as(token).when().get("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getList("$");
    }
}
