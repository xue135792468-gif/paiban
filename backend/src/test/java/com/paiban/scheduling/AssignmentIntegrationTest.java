package com.paiban.scheduling;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ExcelFixtures;
import com.paiban.support.TestData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AssignmentIntegrationTest extends ApiIntegrationTest {

    private record Dataset(long semesterId, List<Long> classIds, List<Long> courseIds, List<Long> teacherIds) {}

    private Dataset seed(String token, String suffix, int classes, int courses) {
        long semesterId = TestData.semester(token);
        long gradeId = TestData.grade(token, semesterId, "高一" + suffix);
        List<Long> classIds = new ArrayList<>();
        for (int i = 1; i <= classes; i++) {
            classIds.add(TestData.adminClass(token, semesterId, gradeId, "高一" + i + "班" + suffix));
        }
        List<Long> courseIds = new ArrayList<>();
        List<Long> teacherIds = new ArrayList<>();
        String[] names = {"语文", "数学", "英语", "物理", "化学"};
        for (int i = 0; i < courses; i++) {
            courseIds.add(TestData.course(token, names[i] + suffix));
            teacherIds.add(TestData.teacher(token, names[i] + "老师" + suffix));
        }
        return new Dataset(semesterId, classIds, courseIds, teacherIds);
    }

    private long createAssignment(String token, long classId, long courseId, long teacherId, int weekly) {
        var response = as(token).body(Map.of(
                        "classId", classId,
                        "courseId", courseId,
                        "teacherId", teacherId,
                        "weeklyLessons", weekly))
                .when().post("/api/assignments");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    @Test
    void threeClassesTenAssignmentsCanBeListed() {
        String token = adminToken();
        String suffix = TestData.suffix();
        Dataset data = seed(token, suffix, 3, 4);

        int created = 0;
        for (int c = 0; c < data.classIds().size() && created < 10; c++) {
            for (int k = 0; k < data.courseIds().size() && created < 10; k++) {
                createAssignment(
                        token,
                        data.classIds().get(c),
                        data.courseIds().get(k),
                        data.teacherIds().get(k),
                        3 + k);
                created++;
            }
        }

        as(token).queryParam("semesterId", data.semesterId())
                .when().get("/api/assignments")
                .then().statusCode(200)
                .body("$", hasSize(10));
    }

    @Test
    void filtersByClassTeacherAndCourse() {
        String token = adminToken();
        String suffix = TestData.suffix();
        Dataset data = seed(token, suffix, 2, 2);

        createAssignment(token, data.classIds().get(0), data.courseIds().get(0), data.teacherIds().get(0), 5);
        createAssignment(token, data.classIds().get(1), data.courseIds().get(1), data.teacherIds().get(1), 3);

        as(token).queryParam("semesterId", data.semesterId())
                .queryParam("classId", data.classIds().get(0))
                .when().get("/api/assignments")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].courseName", equalTo("语文" + suffix));

        as(token).queryParam("semesterId", data.semesterId())
                .queryParam("teacherId", data.teacherIds().get(1))
                .when().get("/api/assignments")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].weeklyLessons", equalTo(3));
    }

    @Test
    void teacherOnlySeesOwnAssignments() {
        String token = adminToken();
        String suffix = TestData.suffix();
        Dataset data = seed(token, suffix, 1, 2);
        createAssignment(token, data.classIds().get(0), data.courseIds().get(0), data.teacherIds().get(0), 5);
        createAssignment(token, data.classIds().get(0), data.courseIds().get(1), data.teacherIds().get(1), 4);

        String username = "at_" + suffix;
        as(token).body(Map.of(
                        "username", username,
                        "password", "teacher123",
                        "displayName", "任课教师",
                        "roleCodes", List.of("teacher"),
                        "teacherId", data.teacherIds().get(0)))
                .when().post("/api/users").then().statusCode(200);
        String teacherToken = login(username, "teacher123");

        as(teacherToken).queryParam("semesterId", data.semesterId())
                .when().get("/api/assignments")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("teacherId", everyItem(equalTo(data.teacherIds().get(0).intValue())));

        as(teacherToken).when().get("/api/assignments/mine")
                .then().statusCode(200)
                .body("$", hasSize(1));
    }

    @Test
    void duplicateClassCourseAssignmentIsRejected() {
        String token = adminToken();
        Dataset data = seed(token, TestData.suffix(), 1, 1);
        createAssignment(token, data.classIds().get(0), data.courseIds().get(0), data.teacherIds().get(0), 5);

        as(token).body(Map.of(
                        "classId", data.classIds().get(0),
                        "courseId", data.courseIds().get(0),
                        "teacherId", data.teacherIds().get(0),
                        "weeklyLessons", 4))
                .when().post("/api/assignments")
                .then().statusCode(409);
    }

    @Test
    void classFromAnotherSemesterIsRejected() {
        String token = adminToken();
        Dataset first = seed(token, TestData.suffix(), 1, 1);
        long otherSemester = TestData.semester(token);
        long otherGrade = TestData.grade(token, otherSemester, "高三" + TestData.suffix());
        long otherClass = TestData.adminClass(token, otherSemester, otherGrade, "高三1班" + TestData.suffix());
        TestData.setCurrent(token, first.semesterId());

        as(token).body(Map.of(
                        "classId", otherClass,
                        "courseId", first.courseIds().get(0),
                        "teacherId", first.teacherIds().get(0),
                        "weeklyLessons", 5))
                .when().post("/api/assignments")
                .then().statusCode(400);
    }

    @Test
    void weeklyLessonsMustBePositive() {
        String token = adminToken();
        Dataset data = seed(token, TestData.suffix(), 1, 1);
        as(token).body(Map.of(
                        "classId", data.classIds().get(0),
                        "courseId", data.courseIds().get(0),
                        "teacherId", data.teacherIds().get(0),
                        "weeklyLessons", 0))
                .when().post("/api/assignments")
                .then().statusCode(400);
    }

    @Test
    void excelImportPreviewAndCommit() throws IOException {
        String token = adminToken();
        String suffix = TestData.suffix();
        Dataset data = seed(token, suffix, 1, 2);
        String className = "高一1班" + suffix;

        byte[] file = ExcelFixtures.workbook(
                List.of("班级名称", "课程名称", "教师姓名", "每周节数"),
                List.of(
                        List.of(className, "语文" + suffix, "语文老师" + suffix, "5"),
                        List.of(className, "数学" + suffix, "数学老师" + suffix, "4")));

        var preview = io.restassured.RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "assignments.xlsx", file,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .queryParam("semesterId", data.semesterId())
                .when().post("/api/assignments/import/preview");
        preview.then().statusCode(200)
                .body("validRows", equalTo(2))
                .body("errorCount", equalTo(0));

        io.restassured.RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "assignments.xlsx", file,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .queryParam("semesterId", data.semesterId())
                .when().post("/api/assignments/import/commit")
                .then().statusCode(200)
                .body("importedRows", equalTo(2));

        as(token).queryParam("semesterId", data.semesterId())
                .when().get("/api/assignments")
                .then().statusCode(200)
                .body("$", hasSize(2));
    }

    @Test
    void excelImportReportsUnknownTeacher() throws IOException {
        String token = adminToken();
        String suffix = TestData.suffix();
        Dataset data = seed(token, suffix, 1, 1);
        byte[] file = ExcelFixtures.workbook(
                List.of("班级名称", "课程名称", "教师姓名", "每周节数"),
                List.of(List.of("高一1班" + suffix, "语文" + suffix, "不存在老师", "5")));

        io.restassured.RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "assignments.xlsx", file,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .queryParam("semesterId", data.semesterId())
                .when().post("/api/assignments/import/preview")
                .then().statusCode(200)
                .body("errorCount", greaterThanOrEqualTo(1))
                .body("errors[0].field", equalTo("教师姓名"));
    }
}
