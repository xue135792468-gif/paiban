package com.paiban.identity;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 票 01 权限矩阵：超级管理员 / 教务管理员 / 老师 × 账号管理、集成配置、全校课表、草稿编辑。
 *
 * <p>只打已经存在的 HTTP 端点。账号审计的读取接口在票 02 落地、审批接口在票 05 恢复，
 * 这里对这两个维度只断言角色权限点（登录后的 {@code /api/auth/me}），
 * HTTP 矩阵分别由 02 / 05 补齐，不为本票虚造端点。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RolePermissionMatrixIntegrationTest extends ApiIntegrationTest {

    @Test
    void onlySuperAdminReachesAccountManagement() {
        String superToken = adminToken();
        String schoolToken = schoolAdminToken(superToken);
        String teacherToken = teacherToken(superToken);

        as(superToken).when().get("/api/users").then().statusCode(200);
        as(schoolToken).when().get("/api/users").then().statusCode(403);
        as(schoolToken).when().get("/api/roles").then().statusCode(403);
        as(teacherToken).when().get("/api/users").then().statusCode(403);
        as(teacherToken).when().get("/api/roles").then().statusCode(403);
    }

    @Test
    void onlySuperAdminReachesDingTalkIntegrationConfiguration() {
        String superToken = adminToken();
        String schoolToken = schoolAdminToken(superToken);
        String teacherToken = teacherToken(superToken);

        as(superToken).when().get("/api/integrations/dingtalk/config").then().statusCode(200);
        as(schoolToken).when().get("/api/integrations/dingtalk/config").then().statusCode(403);
        as(schoolToken).when().post("/api/integrations/dingtalk/sync/preview").then().statusCode(403);
        as(teacherToken).when().get("/api/integrations/dingtalk/config").then().statusCode(403);
    }

    @Test
    void accountAuditPermissionPointIsSuperAdminOnly() {
        String superToken = adminToken();
        String schoolToken = schoolAdminToken(superToken);
        String teacherToken = teacherToken(superToken);

        as(superToken).when().get("/api/auth/me")
                .then().statusCode(200).body("permissions", hasItem("audit:view:account"));
        as(schoolToken).when().get("/api/auth/me")
                .then().statusCode(200).body("permissions", not(hasItem("audit:view:account")));
        as(teacherToken).when().get("/api/auth/me")
                .then().statusCode(200).body("permissions", not(hasItem("audit:view:account")));
    }

    @Test
    void everyIdentityReadsThePublishedTimetableButOnlyManagersTouchDrafts() {
        String superToken = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.published(superToken, 5);
        String schoolToken = schoolAdminToken(superToken);
        String teacherToken = teacherToken(superToken, fixture.teacherId());

        // 全校课表：三种身份都可读（老师是票 01 新增的只读权限）。
        for (String token : new String[] {superToken, schoolToken, teacherToken}) {
            as(token).when().get("/api/timetable/school")
                    .then().statusCode(200);
        }

        // 草稿与发布：老师 403，两种管理员可达。
        as(teacherToken).when().get("/api/timetable-drafts").then().statusCode(403);
        as(teacherToken).when().post("/api/timetable-drafts/" + fixture.draftId() + "/publish")
                .then().statusCode(403);
        as(schoolToken).when().get("/api/timetable-drafts").then().statusCode(200);
        as(schoolToken).when().post("/api/timetable-drafts/" + fixture.draftId() + "/publish")
                .then().statusCode(409);
    }

    @Test
    void teacherKeepsSelfServiceButNeverTheManagerCombination() {
        String superToken = adminToken();
        java.time.LocalDate monday = ScheduleFixture.nextMonday();
        ScheduleFixture.Result fixture = ScheduleFixture.publishedIn(
                superToken, 5, TestData.semesterStarting(superToken, monday));
        long entryId = entryOfTeacher(superToken, fixture);
        String lessonDate = lessonDateOf(superToken, fixture);
        String schoolToken = schoolAdminToken(superToken);
        String teacherToken = teacherToken(superToken, fixture.teacherId());

        // 老师：本人的调代记录可读（自助接口），全局记录与管理端工作台都不可达。
        as(teacherToken).when().get("/api/substitutions/my")
                .then().statusCode(200);
        as(teacherToken).when().get("/api/substitutions")
                .then().statusCode(403);
        as(teacherToken).queryParam("entryId", entryId).queryParam("lessonDate", lessonDate)
                .when().get("/api/substitution-workbench/candidates")
                .then().statusCode(403);
        as(teacherToken).body(Map.of("entryId", entryId, "lessonDate", lessonDate,
                        "substituteTeacherId", fixture.otherTeacherId()))
                .when().post("/api/substitution-workbench/previews")
                .then().statusCode(403);

        // 教务管理员：同一入口用真实课节与日期拿到候选（200），说明组合权限齐全。
        as(schoolToken).queryParam("entryId", entryId)
                .queryParam("lessonDate", lessonDate)
                .when().get("/api/substitution-workbench/candidates")
                .then().statusCode(200)
                .body("baselineTeacherId", org.hamcrest.Matchers.notNullValue());

        // 审批权限点：只有管理员有（HTTP 端点在票 05 恢复）。
        as(teacherToken).when().get("/api/auth/me")
                .then().body("permissions", not(hasItem("substitution:approve")));
        as(schoolToken).when().get("/api/auth/me")
                .then().body("permissions", hasItem("substitution:approve"));
    }

    /** 哪位老师有一条已发布课节，就用它做管理端工作台的真实入参。 */
    private long entryOfTeacher(String token, ScheduleFixture.Result fixture) {
        return ScheduleFixture.classEntries(token, fixture.classId()).stream()
                .filter(entry -> ((Number) entry.get("teacherId")).longValue() == fixture.teacherId())
                .map(entry -> ((Number) entry.get("id")).longValue())
                .findFirst()
                .orElseThrow();
    }

    private String lessonDateOf(String token, ScheduleFixture.Result fixture) {
        java.util.Map<String, Object> entry = ScheduleFixture.classEntries(token, fixture.classId()).stream()
                .filter(item -> ((Number) item.get("teacherId")).longValue() == fixture.teacherId())
                .findFirst()
                .orElseThrow();
        int dayOfWeek = ((Number) entry.get("dayOfWeek")).intValue();
        return ScheduleFixture.nextMonday().plusDays(dayOfWeek - 1L).toString();
    }

    @Test
    void settingsStayWithManagersAndOutOfReachForTeachers() {
        String superToken = adminToken();
        String schoolToken = schoolAdminToken(superToken);
        String teacherToken = teacherToken(superToken);

        as(superToken).when().get("/api/settings").then().statusCode(200);
        as(schoolToken).when().get("/api/settings").then().statusCode(200);
        as(teacherToken).when().get("/api/settings").then().statusCode(403);
    }

    private String schoolAdminToken(String superToken) {
        String username = "edu_" + TestData.suffix();
        createUser(superToken, username, "edu12345", "admin");
        return login(username, "edu12345");
    }

    private String teacherToken(String superToken) {
        return teacherToken(superToken, null);
    }

    private String teacherToken(String superToken, Long teacherId) {
        String username = "tch_" + TestData.suffix();
        java.util.Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "username", username,
                "password", "tch12345",
                "displayName", username,
                "roleCodes", java.util.List.of("teacher")));
        if (teacherId != null) {
            body.put("teacherId", teacherId);
        }
        as(superToken).body(body).when().post("/api/users").then().statusCode(200);
        return login(username, "tch12345");
    }
}
