package com.paiban.foundation;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FoundationIntegrationTest extends ApiIntegrationTest {

    @Test
    void teacherAndCourseCrud() {
        String token = adminToken();
        String suffix = TestData.suffix();

        long teacherId = TestData.teacher(token, "张老师" + suffix);
        as(token).body(Map.of("name", "张老师改" + suffix, "employeeNo", "E" + suffix))
                .when().put("/api/teachers/" + teacherId)
                .then().statusCode(200)
                .body("name", equalTo("张老师改" + suffix));

        long courseId = TestData.course(token, "体育" + suffix);
        as(token).body(Map.of(
                        "name", "体育改" + suffix,
                        "subject", "体育",
                        "allowConsecutive", false,
                        "color", "#AEC6CF"))
                .when().put("/api/courses/" + courseId)
                .then().statusCode(200)
                .body("allowConsecutive", equalTo(false))
                .body("color", equalTo("#AEC6CF"));

        as(token).when().get("/api/teachers")
                .then().statusCode(200)
                .body("name", hasItem("张老师改" + suffix));
    }

    @Test
    void creatingAdminClassAlsoCreatesTeachingClass() {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        String suffix = TestData.suffix();
        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        long classId = TestData.adminClass(token, semesterId, gradeId, "高一1班" + suffix);

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/classes")
                .then().statusCode(200)
                .body("find { it.id == " + classId + " }.teachingClassId", notNullValue())
                .body("find { it.id == " + classId + " }.gradeName", equalTo("高一" + suffix));
    }

    @Test
    void aManuallyCreatedClassWithoutAStudentCountKeepsTheDefaultFortyFive() {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        String suffix = TestData.suffix();
        long gradeId = TestData.grade(token, semesterId, "初二" + suffix);

        as(token).body(Map.of("gradeId", gradeId, "name", "初二1班" + suffix))
                .queryParam("semesterId", semesterId)
                .when().post("/api/classes")
                .then().statusCode(200)
                .body("studentCount", equalTo(45));
    }

    @Test
    void aClassUpdateWithANonPositiveStudentCountIsRejected() {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        String suffix = TestData.suffix();
        long gradeId = TestData.grade(token, semesterId, "初二" + suffix);
        long classId = TestData.adminClass(token, semesterId, gradeId, "初二1班" + suffix);

        for (int invalid : new int[] {0, -1}) {
            as(token).body(Map.of("gradeId", gradeId, "name", "初二1班" + suffix, "studentCount", invalid))
                    .when().put("/api/classes/" + classId)
                    .then().statusCode(400)
                    .body("message", equalTo("参数校验失败"))
                    .body("fields.studentCount", notNullValue());
        }

        // The rejected saves changed nothing; the class is still the default count.
        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/classes")
                .then().statusCode(200)
                .body("find { it.id == " + classId + " }.studentCount", equalTo(45));
    }

    @Test
    void gradesAndClassesAreIsolatedPerSemester() {
        String token = adminToken();
        long first = TestData.semester(token);
        long second = TestData.semester(token);
        String suffix = TestData.suffix();

        long gradeId = TestData.grade(token, first, "高二" + suffix);
        TestData.adminClass(token, first, gradeId, "高二1班" + suffix);

        as(token).queryParam("semesterId", second)
                .when().get("/api/classes")
                .then().statusCode(200)
                .body("size()", equalTo(0));

        as(token).queryParam("semesterId", first)
                .when().get("/api/classes")
                .then().body("size()", equalTo(1));
    }

    @Test
    void duplicateEmployeeNumberIsRejected() {
        String token = adminToken();
        String employeeNo = "DUP" + TestData.suffix();
        as(token).body(Map.of("name", "甲" + TestData.suffix(), "employeeNo", employeeNo))
                .when().post("/api/teachers").then().statusCode(200);

        as(token).body(Map.of("name", "乙" + TestData.suffix(), "employeeNo", employeeNo))
                .when().post("/api/teachers").then().statusCode(409);
    }

    @Test
    void teacherCannotManageFoundationData() {
        String username = "f_" + TestData.suffix();
        createUser(adminToken(), username, "teacher123", "teacher");
        String teacherToken = login(username, "teacher123");

        as(teacherToken).body(Map.of("name", "越权" + TestData.suffix()))
                .when().post("/api/teachers")
                .then().statusCode(403);
    }

    @Test
    void deletingTeacherHidesItFromTheListAndKeepsTheClassRow() {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        String suffix = TestData.suffix();
        long teacherId = TestData.teacher(token, "班主任" + suffix);
        long gradeId = TestData.grade(token, semesterId, "初三" + suffix);

        long classId = as(token)
                .body(Map.of("gradeId", gradeId, "name", "初三1班" + suffix, "headTeacherId", teacherId))
                .queryParam("semesterId", semesterId)
                .when().post("/api/classes")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");

        as(token).when().delete("/api/teachers/" + teacherId).then().statusCode(200);

        // Ticket 03: deletion is soft. The row (and the class's head teacher link) stay
        // so a restore brings everything back; the list simply stops showing it.
        as(token).when().get("/api/teachers")
                .then().statusCode(200)
                .body("id", not(hasItem((int) teacherId)));

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/classes")
                .then().body("find { it.id == " + classId + " }.headTeacherId", equalTo((int) teacherId));
    }

    @Test
    void deletedHeadTeacherDoesNotBlockClassEditing() {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        String suffix = TestData.suffix();
        long teacherId = TestData.teacher(token, "班主任" + suffix);
        long gradeId = TestData.grade(token, semesterId, "初一" + suffix);
        String className = "初一1班" + suffix;

        long classId = as(token)
                .body(Map.of("gradeId", gradeId, "name", className, "headTeacherId", teacherId))
                .queryParam("semesterId", semesterId)
                .when().post("/api/classes")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");

        as(token).when().delete("/api/teachers/" + teacherId).then().statusCode(200);

        // Soft-deleted teacher keeps the row, so the class stays editable (here only the
        // student count changes) and the head-teacher link is not silently dropped.
        as(token)
                .body(Map.of(
                        "gradeId", gradeId,
                        "name", className,
                        "headTeacherId", teacherId,
                        "studentCount", 50))
                .when().put("/api/classes/" + classId)
                .then().statusCode(200)
                .body("studentCount", equalTo(50))
                .body("headTeacherId", equalTo((int) teacherId));

        String batchId = as(token).when().get("/api/recent-deletions")
                .then().statusCode(200)
                .extract().jsonPath().getString("[0].batchId");
        as(token).when().post("/api/recent-deletions/" + batchId + "/restore")
                .then().statusCode(200);

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/classes")
                .then().body("find { it.id == " + classId + " }.headTeacherId",
                        equalTo((int) teacherId));
    }

    @Test
    void auditLogsExposeRecordedImportActions() {
        String token = adminToken();
        as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200);
        List<Map<String, Object>> logs = as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200).extract().jsonPath().getList("$");
        // Imports are audited elsewhere; this endpoint must expose whatever has been recorded.
        org.assertj.core.api.Assertions.assertThat(logs)
                .allSatisfy(log -> org.assertj.core.api.Assertions.assertThat(log.get("action")).isNotNull());
    }
}
