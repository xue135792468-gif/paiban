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
        // A non-empty local name is never overwritten; the preview flags it instead.
        assertThat(teachers.get(0).get("name")).isEqualTo("旧名字");
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

    @Test
    void previewIsReadOnlyAndClassifiesEveryUser() {
        String token = adminToken();
        configure(token, true);
        // MERGE: matched by employee number, phone is missing locally.
        long mergeTarget = createTeacher(token, "王老师", "EMP101", null, null);
        // SUSPECTED: same name, no matching key.
        createTeacher(token, "李四", null, null, null);
        // SKIP: already identical, including the binding.
        createTeacher(token, "赵六", "EMP104", "13900000104", "dd-104");
        fakeDingTalk.clearDirectory().addDepartment(2, "全校教职工", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-101", "王老师", "13800000101", null, "EMP101"));
        fakeDingTalk.addUser(2, new DingUser("dd-102", "李四", "13800000102", null, "EMP102"));
        fakeDingTalk.addUser(2, new DingUser("dd-103", "张三", "13800000103", null, "EMP103"));
        fakeDingTalk.addUser(2, new DingUser("dd-104", "赵六", "13900000104", null, "EMP104"));

        Map<String, Object> preview = preview(token, null);

        assertThat(preview.get("success")).isEqualTo(true);
        Map<String, Object> summary = summary(preview);
        assertThat(number(summary.get("create"))).isEqualTo(1);
        assertThat(number(summary.get("merge"))).isEqualTo(1);
        assertThat(number(summary.get("suspected"))).isEqualTo(1);
        assertThat(number(summary.get("skip"))).isEqualTo(1);
        assertThat(previewItems(preview)).extracting(item -> item.get("category"))
                .containsExactlyInAnyOrder("CREATE", "MERGE", "SUSPECTED", "SKIP");

        // Strictly read-only: no teacher row and no audit row.
        assertThat(teachers(token)).hasSize(3);
        as(token).when().get("/api/integrations/dingtalk/logs")
                .then().statusCode(200)
                .body("$", org.hamcrest.Matchers.hasSize(0));
        assertThat(mergeTarget).isPositive();
    }

    @Test
    void confirmWritesCreatesAndMergesButLeavesSuspectedUsersUntouchedByDefault() {
        String token = adminToken();
        configure(token, true);
        long mergeTarget = createTeacher(token, "王老师", "EMP101", null);
        createTeacher(token, "李四", null, null);
        fakeDingTalk.clearDirectory().addDepartment(2, "全校教职工", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-101", "王老师", "13800000101", null, "EMP101"));
        fakeDingTalk.addUser(2, new DingUser("dd-102", "李四", "13800000102", null, "EMP102"));
        fakeDingTalk.addUser(2, new DingUser("dd-103", "张三", "13800000103", null, "EMP103"));

        as(token).when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("created", equalTo(1))
                .body("updated", equalTo(1))
                .body("skipped", equalTo(0))
                .body("suspected", equalTo(1));

        List<Map<String, Object>> after = teachers(token);
        assertThat(after).hasSize(3);
        assertThat(after).filteredOn(teacher -> "李四".equals(teacher.get("name"))).hasSize(1);
        assertThat(after).extracting(teacher -> teacher.get("name")).contains("张三");
        Map<String, Object> merged = byId(after, mergeTarget);
        assertThat(merged.get("phone")).isEqualTo("13800000101");
        assertThat(merged.get("dingtalkUserId")).isEqualTo("dd-101");
    }

    @Test
    void confirmMergesASuspectedUserWhenTheAdminConfirmsTheCandidate() {
        String token = adminToken();
        configure(token, true);
        long existing = createTeacher(token, "李四", null, null);
        fakeDingTalk.clearDirectory().addDepartment(2, "全校教职工", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-102", "李四", "13800000102", null, "EMP102"));

        as(token).body(Map.of("suspectedDecisions", List.of(
                        Map.of("userId", "dd-102", "action", "MERGE", "teacherId", existing))))
                .when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(200)
                .body("created", equalTo(0))
                .body("updated", equalTo(1));

        List<Map<String, Object>> after = teachers(token);
        assertThat(after).hasSize(1);
        Map<String, Object> teacher = after.get(0);
        assertThat(teacher.get("employeeNo")).isEqualTo("EMP102");
        assertThat(teacher.get("phone")).isEqualTo("13800000102");
        assertThat(teacher.get("dingtalkUserId")).isEqualTo("dd-102");
    }

    @Test
    void confirmCreatesASuspectedUserAsNewWhenTheAdminSaysTheyAreDifferentPeople() {
        String token = adminToken();
        configure(token, true);
        createTeacher(token, "李四", null, null);
        fakeDingTalk.clearDirectory().addDepartment(2, "全校教职工", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-102", "李四", "13800000102", null, "EMP102"));

        as(token).body(Map.of("suspectedDecisions", List.of(
                        Map.of("userId", "dd-102", "action", "CREATE"))))
                .when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(200)
                .body("created", equalTo(1));

        assertThat(teachers(token)).hasSize(2);
    }

    @Test
    void confirmHonoursDepartmentAndUserExclusions() {
        String token = adminToken();
        configure(token, true);
        fakeDingTalk.clearDirectory()
                .addDepartment(2, "全校教职工", 1)
                .addDepartment(3, "食堂", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-101", "王老师", null, null, "EMP101"));
        fakeDingTalk.addUser(3, new DingUser("dd-201", "厨师甲", null, null, "EMP201"));
        fakeDingTalk.addUser(3, new DingUser("dd-202", "厨师乙", null, null, "EMP202"));
        // Also a canteen member, but this one also belongs to 全校教职工.
        fakeDingTalk.addUser(3, new DingUser("dd-301", "双部门老师", null, null, "EMP301"));
        fakeDingTalk.addUser(2, new DingUser("dd-301", "双部门老师", null, null, "EMP301"));

        as(token).body(Map.of(
                        "excludedDepartments", List.of("食堂"),
                        "excludedUserIds", List.of("dd-101")))
                .when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(200)
                .body("created", equalTo(1));

        assertThat(teacherNames(token)).containsExactly("双部门老师");
    }

    @Test
    void confirmIsIdempotent() {
        String token = adminToken();
        configure(token, true);
        createTeacher(token, "王老师", "EMP101", null);
        fakeDingTalk.clearDirectory().addDepartment(2, "全校教职工", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-101", "王老师", "13800000101", null, "EMP101"));
        fakeDingTalk.addUser(2, new DingUser("dd-103", "张三", "13800000103", null, "EMP103"));

        as(token).when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(200)
                .body("created", equalTo(1))
                .body("updated", equalTo(1));

        as(token).when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(200)
                .body("created", equalTo(0))
                .body("updated", equalTo(0));

        assertThat(teachers(token)).hasSize(2);
    }

    @Test
    void confirmRejectsAnUnknownMergeTargetAndAnUnknownAction() {
        String token = adminToken();
        configure(token, true);
        createTeacher(token, "李四", null, null);
        fakeDingTalk.clearDirectory().addDepartment(2, "全校教职工", 1);
        fakeDingTalk.addUser(2, new DingUser("dd-102", "李四", "13800000102", null, "EMP102"));

        as(token).body(Map.of("suspectedDecisions", List.of(
                        Map.of("userId", "dd-102", "action", "MERGE", "teacherId", 99999))))
                .when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(400);
        as(token).body(Map.of("suspectedDecisions", List.of(
                        Map.of("userId", "dd-102", "action", "BOGUS"))))
                .when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(400);
    }

    @Test
    void fortySevenExcelTeachersAreShownAsSuspectedAndNeverDuplicated() {
        String token = adminToken();
        configure(token, true);
        fakeDingTalk.clearDirectory().addDepartment(2, "全校教职工", 1);
        List<Map<String, Object>> mergeDecisions = new java.util.ArrayList<>();
        for (int i = 1; i <= 47; i++) {
            String name = "存量老师" + i;
            long id = createTeacher(token, name, null, null);
            fakeDingTalk.addUser(2, new DingUser("dd-" + i, name, "1380000" + String.format("%04d", i), null,
                    "EMP" + String.format("%04d", i)));
            mergeDecisions.add(Map.of("userId", "dd-" + i, "action", "MERGE", "teacherId", id));
        }

        Map<String, Object> preview = preview(token, null);
        assertThat(number(summary(preview).get("suspected"))).isEqualTo(47);
        assertThat(number(summary(preview).get("create"))).isZero();
        assertThat(teachers(token)).hasSize(47);

        // Confirming without decisions must not create 47 duplicates.
        as(token).when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(200)
                .body("created", equalTo(0));
        assertThat(teachers(token)).hasSize(47);

        // Explicit merges keep the roster at 47 and bind every DingTalk account.
        as(token).body(Map.of("suspectedDecisions", mergeDecisions))
                .when().post("/api/integrations/dingtalk/sync/confirm")
                .then().statusCode(200)
                .body("created", equalTo(0))
                .body("updated", equalTo(47));
        List<Map<String, Object>> after = teachers(token);
        assertThat(after).hasSize(47);
        assertThat(after).allSatisfy(teacher -> assertThat(teacher.get("dingtalkUserId")).isNotNull());
    }

    private long createTeacher(String token, String name, String employeeNo, String phone) {
        return createTeacher(token, name, employeeNo, phone, null);
    }

    private long createTeacher(String token, String name, String employeeNo, String phone, String dingtalkUserId) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("name", name);
        if (employeeNo != null) {
            payload.put("employeeNo", employeeNo);
        }
        if (phone != null) {
            payload.put("phone", phone);
        }
        if (dingtalkUserId != null) {
            payload.put("dingtalkUserId", dingtalkUserId);
        }
        return as(token).body(payload).when().post("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getLong("id");
    }

    private Map<String, Object> preview(String token, Object body) {
        var request = as(token);
        if (body != null) {
            request = request.body(body);
        }
        return request.when().post("/api/integrations/dingtalk/sync/preview")
                .then().statusCode(200).extract().jsonPath().getMap("$");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> previewItems(Map<String, Object> preview) {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) preview.get("departments")) {
            items.addAll((List<Map<String, Object>>) group.get("items"));
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Map<String, Object> preview) {
        return (Map<String, Object>) preview.get("summary");
    }

    private int number(Object value) {
        return ((Number) value).intValue();
    }

    private Map<String, Object> byId(List<Map<String, Object>> teachers, long id) {
        return teachers.stream()
                .filter(teacher -> ((Number) teacher.get("id")).longValue() == id)
                .findFirst().orElseThrow();
    }

    private List<String> teacherNames(String token) {
        return teachers(token).stream().map(teacher -> (String) teacher.get("name")).toList();
    }

    private List<Map<String, Object>> teachers(String token) {
        return as(token).when().get("/api/teachers")
                .then().statusCode(200).extract().jsonPath().getList("$");
    }
}
