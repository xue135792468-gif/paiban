package com.paiban.foundation;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SemesterCopyIntegrationTest extends ApiIntegrationTest {

    @Test
    void copiesPeriodsGradesClassesAndAssignments() {
        String token = adminToken();
        String suffix = TestData.suffix();
        long source = TestData.semester(token);
        as(token).body(Map.of("morningCount", 4, "afternoonCount", 4, "eveningCount", 2))
                .when().post("/api/semesters/" + source + "/periods/default").then().statusCode(200);
        long gradeId = TestData.grade(token, source, "高一" + suffix);
        long classId = TestData.adminClass(token, source, gradeId, "高一1班" + suffix);
        long courseId = TestData.course(token, "数学" + suffix);
        long teacherId = TestData.teacher(token, "数学老师" + suffix);
        as(token).body(Map.of(
                        "classId", classId,
                        "courseId", courseId,
                        "teacherId", teacherId,
                        "weeklyLessons", 5))
                .when().post("/api/assignments").then().statusCode(200);

        long target = TestData.semester(token);
        as(token).when().post("/api/semesters/" + target + "/copy-from/" + source)
                .then().statusCode(200)
                .body("periods", equalTo(50))
                .body("grades", equalTo(1))
                .body("classes", equalTo(1))
                .body("assignments", equalTo(1));

        as(token).when().get("/api/semesters/" + target + "/periods")
                .then().statusCode(200).body("$", hasSize(50));
        as(token).queryParam("semesterId", target).when().get("/api/grades")
                .then().statusCode(200).body("name", org.hamcrest.Matchers.hasItem("高一" + suffix));
        as(token).queryParam("semesterId", target).when().get("/api/classes")
                .then().statusCode(200).body("$", hasSize(1));
        as(token).queryParam("semesterId", target).when().get("/api/assignments")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].weeklyLessons", equalTo(5));
    }

    @Test
    void copyingIntoItselfIsRejected() {
        String token = adminToken();
        long semester = TestData.semester(token);
        as(token).when().post("/api/semesters/" + semester + "/copy-from/" + semester)
                .then().statusCode(400);
    }

    @Test
    void copyingTwiceConflictsOnDuplicateNames() {
        String token = adminToken();
        String suffix = TestData.suffix();
        long source = TestData.semester(token);
        long gradeId = TestData.grade(token, source, "初二" + suffix);
        TestData.adminClass(token, source, gradeId, "初二1班" + suffix);
        long target = TestData.semester(token);

        as(token).when().post("/api/semesters/" + target + "/copy-from/" + source).then().statusCode(200);
        as(token).when().post("/api/semesters/" + target + "/copy-from/" + source).then().statusCode(409);
    }

    @Test
    void teacherCannotCopyStructure() {
        String token = adminToken();
        long source = TestData.semester(token);
        long target = TestData.semester(token);
        String username = "cp_" + TestData.suffix();
        createUser(token, username, "teacher123", "teacher");
        String teacherToken = login(username, "teacher123");

        as(teacherToken).when().post("/api/semesters/" + target + "/copy-from/" + source)
                .then().statusCode(403);
    }

    @Test
    void copyResultListsTargetData() {
        String token = adminToken();
        long source = TestData.semester(token);
        long target = TestData.semester(token);
        as(token).when().post("/api/semesters/" + target + "/copy-from/" + source)
                .then().statusCode(200)
                .body("periods", equalTo(0))
                .body("grades", equalTo(0))
                .body("classes", equalTo(0))
                .body("assignments", equalTo(0));
        List<Map<String, Object>> semesters = as(token).when().get("/api/semesters")
                .then().statusCode(200).extract().jsonPath().getList("$");
        org.assertj.core.api.Assertions.assertThat(semesters)
                .anyMatch(item -> ((Number) item.get("id")).longValue() == target);
    }
}
