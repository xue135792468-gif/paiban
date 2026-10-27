package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.identity.domain.PermissionRepository;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ticket 14/11: the unfinished work of one account in one semester. It restores across
 * devices, never crosses accounts or semesters, has its own revision, revalidates its
 * references on read, and disappears after a successful save.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConstraintWorkspaceIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private RoleRepository roles;

    @Autowired
    private PermissionRepository permissions;

    @Test
    void roundTripAdvancesTheWorkspaceRevision() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-workspace")
                .then().statusCode(200)
                .body("exists", equalTo(false));

        saveWorkspace(token, semesterId, 0, 0, payload("王老师周三不排", List.of()))
                .then().statusCode(200)
                .body("exists", equalTo(true))
                .body("revision", equalTo(1));

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-workspace")
                .then().statusCode(200)
                .body("payload.text", equalTo("王老师周三不排"))
                .body("revision", equalTo(1));

        saveWorkspace(token, semesterId, 1, 0, payload("改成李老师", List.of()))
                .then().statusCode(200)
                .body("revision", equalTo(2));
        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-workspace")
                .then().statusCode(200)
                .body("payload.text", equalTo("改成李老师"));
    }

    @Test
    void aSecondDeviceCannotOverwriteTheFirstAutosave() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        saveWorkspace(token, semesterId, 0, 0, payload("设备甲的内容", List.of()))
                .then().statusCode(200);

        Response refused = saveWorkspace(
                token, semesterId, 0, 0, payload("设备乙的内容", List.of()));

        refused.then().statusCode(409)
                .body("workspace.revision", equalTo(1));
        assertThat(refused.jsonPath().getString("workspace.payload.text"))
                .isEqualTo("设备甲的内容");
        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-workspace")
                .then().statusCode(200)
                .body("payload.text", equalTo("设备甲的内容"));
    }

    @Test
    void accountsAndSemestersAreIsolated() {
        String admin = adminToken();
        long semesterA = semesterWithPeriods(admin);
        long semesterB = semesterWithPeriods(admin);
        saveWorkspace(admin, semesterA, 0, 0, payload("管理员的草稿", List.of()))
                .then().statusCode(200);

        String roleCode = "constraint_workspace_" + TestData.suffix();
        Role role = new Role(roleCode, "约束管理员");
        role.getPermissions().add(permissions.findByCode(Permissions.CONSTRAINT_MANAGE).orElseThrow());
        roles.save(role);
        String otherUser = "workspace" + TestData.suffix();
        createUser(admin, otherUser, "other123", role.getCode());
        String other = login(otherUser, "other123");

        as(other).queryParam("semesterId", semesterA)
                .when().get("/api/constraint-workspace")
                .then().statusCode(200)
                .body("exists", equalTo(false));
        saveWorkspace(other, semesterA, 0, 0, payload("另一位用户的草稿", List.of()))
                .then().statusCode(200);
        as(admin).queryParam("semesterId", semesterA)
                .when().get("/api/constraint-workspace")
                .then().statusCode(200)
                .body("payload.text", equalTo("管理员的草稿"));

        // The same account has nothing in another semester.
        as(admin).queryParam("semesterId", semesterB)
                .when().get("/api/constraint-workspace")
                .then().statusCode(200)
                .body("exists", equalTo(false));
    }

    @Test
    void restoringRevalidatesTheObjectReferences() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        saveWorkspace(token, semesterId, 0, 0, payload("无效引用", List.of(Map.of(
                "type", "TEACHER_UNAVAILABLE", "weight", 100, "hard", true, "enabled", true,
                "entries", List.of(Map.of("params", Map.of(
                        "teacherId", 9_999_999, "dayOfWeek", 1, "periodNo", 1)))))))
                .then().statusCode(200);

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-workspace")
                .then().statusCode(200)
                .body("invalidReferences", hasSize(1))
                .body("invalidReferences[0].field", equalTo("teacherId"));
    }

    @Test
    void aSuccessfulSaveDeletesTheWorkspace() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        saveWorkspace(token, semesterId, 0, 0, payload("待保存", List.of()))
                .then().statusCode(200);

        as(token).queryParam("semesterId", semesterId).queryParam("baseVersion", 0)
                .body(Map.of("configs", List.of(Map.of(
                        "type", "TEACHER_CONFLICT", "params", Map.of(), "weight", 100,
                        "hard", true, "enabled", true))))
                .when().put("/api/constraints")
                .then().statusCode(200);

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-workspace")
                .then().statusCode(200)
                .body("exists", equalTo(false));
    }

    @Test
    void applyingATemplateAlsoClearsTheCallersWorkspace() {
        String token = adminToken();
        long semesterId = semesterWithPeriods(token);
        as(token).queryParam("semesterId", semesterId).queryParam("baseVersion", 0)
                .body(Map.of("configs", List.of(Map.of(
                        "type", "TEACHER_CONFLICT", "params", Map.of(), "weight", 100,
                        "hard", true, "enabled", true))))
                .when().put("/api/constraints").then().statusCode(200);
        long templateId = as(token).queryParam("semesterId", semesterId)
                .body(Map.of("name", "工作区模板-" + TestData.suffix()))
                .when().post("/api/constraint-templates")
                .then().statusCode(200).extract().jsonPath().getLong("id");
        saveWorkspace(token, semesterId, 0, 0, payload("待保存", List.of()))
                .then().statusCode(200);

        as(token).queryParam("semesterId", semesterId).queryParam("baseVersion", 1)
                .when().post("/api/constraint-templates/" + templateId + "/apply")
                .then().statusCode(200);

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-workspace")
                .then().statusCode(200)
                .body("exists", equalTo(false));
    }

    @Test
    void deletingDiscardsOnlyTheCallersWork() {
        String admin = adminToken();
        long semesterId = semesterWithPeriods(admin);
        saveWorkspace(admin, semesterId, 0, 0, payload("保留", List.of()))
                .then().statusCode(200);

        String roleCode = "constraint_delete_" + TestData.suffix();
        Role role = new Role(roleCode, "约束管理员");
        role.getPermissions().add(permissions.findByCode(Permissions.CONSTRAINT_MANAGE).orElseThrow());
        roles.save(role);
        String otherUser = "deleter" + TestData.suffix();
        createUser(admin, otherUser, "other123", role.getCode());
        String other = login(otherUser, "other123");

        as(other).queryParam("semesterId", semesterId)
                .when().delete("/api/constraint-workspace").then().statusCode(200);
        as(admin).queryParam("semesterId", semesterId)
                .when().get("/api/constraint-workspace")
                .then().statusCode(200)
                .body("exists", equalTo(true))
                .body("payload.text", equalTo("保留"));
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> payload(String text, List<Map<String, Object>> configs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("configs", configs);
        payload.put("selectedProposalIds", List.of());
        payload.put("decisions", List.of());
        return payload;
    }

    private Response saveWorkspace(
            String token, long semesterId, long baseRevision, long baseVersion,
            Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseRevision", baseRevision);
        body.put("baseVersion", baseVersion);
        body.put("payload", payload);
        return as(token).queryParam("semesterId", semesterId)
                .body(body)
                .when().put("/api/constraint-workspace");
    }

    private long semesterWithPeriods(String token) {
        long semesterId = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 0))
                .when().post("/api/semesters/" + semesterId + "/periods/default")
                .then().statusCode(200);
        return semesterId;
    }
}
