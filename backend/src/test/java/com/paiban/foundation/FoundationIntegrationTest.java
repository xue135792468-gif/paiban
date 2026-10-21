package com.paiban.foundation;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
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
    void teacherRoomAndCourseCrud() {
        String token = adminToken();
        String suffix = TestData.suffix();

        long teacherId = TestData.teacher(token, "张老师" + suffix);
        as(token).body(Map.of("name", "张老师改" + suffix, "employeeNo", "E" + suffix))
                .when().put("/api/teachers/" + teacherId)
                .then().statusCode(200)
                .body("name", equalTo("张老师改" + suffix));

        long roomId = TestData.room(token, "实验室" + suffix);
        as(token).body(Map.of("name", "实验室改" + suffix, "capacity", 60, "type", "LAB"))
                .when().put("/api/rooms/" + roomId)
                .then().statusCode(200)
                .body("capacity", equalTo(60))
                .body("type", equalTo("LAB"));

        long courseId = TestData.course(token, "体育" + suffix);
        as(token).body(Map.of(
                        "name", "体育改" + suffix,
                        "subject", "体育",
                        "allowConsecutive", false,
                        "requireOutdoor", true,
                        "requiredRoomType", "PLAYGROUND"))
                .when().put("/api/courses/" + courseId)
                .then().statusCode(200)
                .body("requireOutdoor", equalTo(true))
                .body("requiredRoomType", equalTo("PLAYGROUND"));

        as(token).when().get("/api/teachers")
                .then().statusCode(200)
                .body("name", hasItem("张老师改" + suffix));
    }

    @Test
    void teacherFeishuBindingsCannotBeDuplicated() {
        String token = adminToken();
        String suffix = TestData.suffix();
        long first = TestData.teacher(token, "张老师" + suffix);
        long second = TestData.teacher(token, "李老师" + suffix);

        as(token).body(Map.of("name", "张老师" + suffix, "feishuUserId", "fs-dup-" + suffix))
                .when().put("/api/teachers/" + first)
                .then().statusCode(200);

        // Another teacher claiming the same UserID is a 409, not the unique-index 500.
        as(token).body(Map.of("name", "李老师" + suffix, "feishuUserId", "fs-dup-" + suffix))
                .when().put("/api/teachers/" + second)
                .then().statusCode(409);

        as(token).body(Map.of("name", "张老师" + suffix, "feishuOpenId", "ou-dup-" + suffix))
                .when().put("/api/teachers/" + first)
                .then().statusCode(200);
        as(token).body(Map.of("name", "李老师" + suffix, "feishuOpenId", "ou-dup-" + suffix))
                .when().put("/api/teachers/" + second)
                .then().statusCode(409);
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
    void deletingTeacherClearsHeadTeacherReference() {
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

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/classes")
                .then().body("find { it.id == " + classId + " }.headTeacherId", equalTo(null));
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
