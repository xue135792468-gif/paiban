package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ExcelFixtures;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TeachingMatrixIntegrationTest extends ApiIntegrationTest {

    private static final String ROOT = "/api/assignments/matrix";
    private static final String HOURS = """
            {"weeklyHours":{"道德与法治":3,"体育":2},"saveAsDefaults":true}
            """;

    @Autowired private JdbcTemplate jdbc;

    @Test
    void previewsReadOnlyThenImportsExportsAndMergesOnlyNamedAssignments() throws IOException {
        String token = adminToken();
        long semester = TestData.semester(token);
        long grade = TestData.grade(token, semester, "八年级");
        byte[] original = ExcelFixtures.workbook(List.of("学校八年级任课表"), List.of(
                List.of(" 班 级 ", "人数", "班主任", "道德与法\n治", "体育"),
                List.of("1", "42", "张老师", "张老师", "李老师"),
                List.of("2", "39", "李老师", "", "李老师")));
        upload(token, "preview", semester, grade, original, null).then().statusCode(200)
                .body("courses.find { it.name == '道德与法治' }.missingHours", equalTo(true))
                .body("summary.classes.created", equalTo(2))
                .body("summary.teachers.created", equalTo(2));
        upload(token, "preview", semester, grade, original, HOURS).then().statusCode(200)
                .body("warnings[0].requested", equalTo(5))
                .body("rows[0].assignments[0].weeklyLessons", equalTo(3));
        assertEmptyBusinessTables();
        assertThat(count("audit_log")).isZero();

        upload(token, "commit", semester, grade, original, HOURS).then().statusCode(200)
                .body("summary.assignments.created", equalTo(3));
        as(token).queryParam("semesterId", semester).when().get("/api/classes")
                .then().statusCode(200)
                .body("find { it.name == '八年级（1）班' }.studentCount", equalTo(42))
                .body("find { it.name == '八年级（1）班' }.headTeacherName", equalTo("张老师"));
        as(token).queryParam("type", "TEACHER").when().get("/api/rule-groups")
                .then().statusCode(200)
                .body("find { it.name == '班主任' }.members.name", hasSize(2))
                .body("find { it.name == '八年级班主任' }.members.name", hasItem("张老师"));

        byte[] template = download(token, semester, grade, "template");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(template))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("班级");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("八年级（1）班");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("张老师");
            assertThat(sheet.getRow(1).getCell(3)).isNull();
        }

        byte[] partial = ExcelFixtures.workbook(List.of("班级", "人数", "班主任", "道德与法治"),
                List.of(List.of("01", "46", "李老师", "李老师"), List.of("2", "", "", "")));
        upload(token, "commit", semester, grade, partial,
                "{\"weeklyHours\":{\"道德与法治\":4}}").then().statusCode(200)
                .body("rows[0].assignments[0].action", equalTo("UPDATE_TEACHER"))
                .body("summary.assignments.updated", equalTo(1));
        assertThat(count("assignment")).isEqualTo(3);
        as(token).when().get("/api/courses").then().statusCode(200)
                .body("find { it.name == '道德与法治' }.weeklyHours", equalTo(3));

        byte[] exported = download(token, semester, grade, "export");
        upload(token, "preview", semester, grade, exported, null).then().statusCode(200)
                .body("errors", hasSize(0))
                .body("rows.action", everyItem(equalTo("UNCHANGED")))
                .body("summary.assignments.unchanged", equalTo(3));
        upload(token, "commit", semester, grade, exported, null).then().statusCode(200)
                .body("summary.assignments.updated", equalTo(0));
        as(token).queryParam("semesterId", semester).when().get("/api/assignments").then().statusCode(200)
                .body("find { it.courseName == '道德与法治' }.weeklyLessons", equalTo(4));
    }

    @Test
    void invalidRowsRejectTheWholeBatchAndAreAudited() throws IOException {
        String token = adminToken();
        long semester = TestData.semester(token);
        long grade = TestData.grade(token, semester, "八年级");
        TestData.teacher(token, "重名");
        TestData.teacher(token, "重名");
        byte[] invalid = ExcelFixtures.workbook(List.of("班级", "人数", "班主任", "体育"),
                List.of(List.of("1", "40", "有效教师", "有效教师"),
                        List.of("2", "-1", "重名", "重名"),
                        List.of("01", "", "", "")));
        upload(token, "preview", semester, grade, invalid, "{\"weeklyHours\":{\"体育\":2}}")
                .then().statusCode(200)
                .body("errors.row", hasItem(3))
                .body("errors.row", hasItem(4))
                .body("errors.message", hasItem(containsString("同名教师")))
                .body("errors.message", hasItem(containsString("正整数")))
                .body("errors.message", hasItem(containsString("班级重复")));
        upload(token, "commit", semester, grade, invalid, "{\"weeklyHours\":{\"体育\":2}}")
                .then().statusCode(400);
        assertThat(count("teacher")).isEqualTo(2);
        assertThat(count("course")).isZero();
        assertThat(count("admin_class")).isZero();
        assertThat(count("assignment")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'teaching-matrix'"
                + " and success_rows = 0 and failed_rows > 0", Integer.class)).isOne();

        long anotherSemester = TestData.semester(token);
        upload(token, "commit", anotherSemester, grade, invalid, null)
                .then().statusCode(400).body("errors[0].field", equalTo("年级"));
        upload(null, "preview", semester, grade, invalid, null).then().statusCode(401);
        long teacherId = TestData.teacher(token, "只有查看权限");
        as(token).body(Map.of("username", "matrix_reader", "displayName", "任课教师",
                        "password", "testpassword123", "roleCodes", List.of("teacher"), "teacherId", teacherId))
                .when().post("/api/users").then().statusCode(200);
        String reader = login("matrix_reader", "testpassword123");
        upload(reader, "preview", semester, grade, invalid, null).then().statusCode(403);
        upload(reader, "commit", semester, grade, invalid, null).then().statusCode(403);
    }

    @Test
    void rejectsMissingHoursAndRecycleConflictsWhileLeavingExistingDataAlone() throws IOException {
        String token = adminToken();
        long semester = TestData.semester(token);
        long grade = TestData.grade(token, semester, "八年级");
        byte[] file = ExcelFixtures.workbook(List.of("班级", "体育"), List.of(List.of("1", "金老师")));
        upload(token, "commit", semester, grade, file, null).then().statusCode(400)
                .body("courses[0].missingHours", equalTo(true));
        assertEmptyBusinessTables();

        upload(token, "commit", semester, grade, file, "{\"weeklyHours\":{\"体育\":2}}")
                .then().statusCode(200);
        int id = as(token).queryParam("semesterId", semester).when().get("/api/assignments")
                .then().statusCode(200).extract().path("[0].id");
        as(token).when().delete("/api/assignments/" + id).then().statusCode(200);
        upload(token, "preview", semester, grade, file, null).then().statusCode(200)
                .body("errors[0].row", equalTo(2))
                .body("errors[0].message", containsString("最近删除"));
        upload(token, "commit", semester, grade, file, null).then().statusCode(400);
        assertThat(count("assignment")).isOne();
    }

    @Test
    void aWriteFailureRollsBackEarlierTeachersCoursesAndClasses() throws IOException {
        String token = adminToken();
        long semester = TestData.semester(token);
        long grade = TestData.grade(token, semester, "八年级");
        long broken = TestData.adminClass(token, semester, grade, "八（2）");
        jdbc.update("delete from teaching_class where admin_class_id = ?", broken);
        byte[] file = ExcelFixtures.workbook(List.of("班级", "班主任", "体育"),
                List.of(List.of("1", "金老师", "金老师"), List.of("2", "高老师", "高老师")));
        upload(token, "commit", semester, grade, file, "{\"weeklyHours\":{\"体育\":2}}")
                .then().statusCode(400).body("errors[0].message", containsString("缺少对应的教学班"));
        assertThat(count("teacher")).isZero();
        assertThat(count("course")).isZero();
        assertThat(count("admin_class")).isOne();
        assertThat(count("assignment")).isZero();
        assertThat(count("rule_group_member")).isZero();
    }

    private Response upload(String token, String phase, long semester, long grade, byte[] workbook, String options) {
        var request = (token == null ? io.restassured.RestAssured.given() : as(token))
                .contentType("multipart/form-data").multiPart("file", "matrix.xlsx", workbook)
                .queryParam("semesterId", semester).queryParam("gradeId", grade);
        if (options != null) {
            request.multiPart("options", "options.json", options.getBytes(StandardCharsets.UTF_8), "application/json");
        }
        return request.when().post(ROOT + "/import/" + phase);
    }

    private byte[] download(String token, long semester, long grade, String type) {
        return as(token).queryParam("semesterId", semester).queryParam("gradeId", grade)
                .when().get(ROOT + "/" + type).then().statusCode(200).extract().asByteArray();
    }

    private void assertEmptyBusinessTables() {
        assertThat(count("teacher")).isZero();
        assertThat(count("course")).isZero();
        assertThat(count("admin_class")).isZero();
        assertThat(count("assignment")).isZero();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
