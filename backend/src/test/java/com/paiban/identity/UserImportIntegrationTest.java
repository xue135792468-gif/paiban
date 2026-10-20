package com.paiban.identity;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ExcelFixtures;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserImportIntegrationTest extends ApiIntegrationTest {

    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private Response upload(String token, String action, byte[] file) {
        return io.restassured.RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "users.xlsx", file, XLSX)
                .when().post("/api/users/import/" + action);
    }

    @Test
    void importsAccountsWithRolesAndTeacherMapping() throws IOException {
        String token = adminToken();
        String suffix = TestData.suffix();
        long teacherId = TestData.teacher(token, "导入教师" + suffix);
        String employeeNo = as(token).when().get("/api/teachers")
                .then().statusCode(200).extract().jsonPath()
                .getString("find { it.id == " + teacherId + " }.employeeNo");

        String usernameA = "uimp_" + suffix + "_a";
        byte[] file = ExcelFixtures.workbook(
                List.of("用户名", "姓名", "初始密码", "角色", "教师工号"),
                List.of(
                        List.of(usernameA, "导入甲", "secret123", "teacher", employeeNo),
                        List.of("uimp_" + suffix + "_b", "导入乙", "secret123", "班主任", "")));

        upload(token, "preview", file)
                .then().statusCode(200)
                .body("validRows", equalTo(2))
                .body("errorCount", equalTo(0));

        upload(token, "commit", file)
                .then().statusCode(200)
                .body("importedRows", equalTo(2));

        org.assertj.core.api.Assertions.assertThat(login(usernameA, "secret123")).isNotBlank();
        as(token).when().get("/api/users")
                .then().statusCode(200)
                .body("find { it.username == '" + usernameA + "' }.teacherId", equalTo((int) teacherId))
                .body("find { it.username == 'uimp_" + suffix + "_b' }.roles",
                        org.hamcrest.Matchers.contains("head_teacher"));
    }

    @Test
    void duplicateUsernameIsReported() throws IOException {
        String token = adminToken();
        String username = "udup_" + TestData.suffix();
        createUser(token, username, "teacher123", "teacher");
        byte[] file = ExcelFixtures.workbook(
                List.of("用户名", "姓名", "初始密码", "角色"),
                List.of(
                        List.of(username, "重复", "secret123", "teacher"),
                        List.of(username + "_2", "正常", "secret123", "teacher"),
                        List.of(username + "_2", "文件内重复", "secret123", "teacher")));

        upload(token, "preview", file)
                .then().statusCode(200)
                .body("validRows", equalTo(1))
                .body("errorCount", equalTo(2))
                .body("errors.message", org.hamcrest.Matchers.hasItem(containsString("已存在")));
    }

    @Test
    void unknownEmployeeNumberIsReported() throws IOException {
        byte[] file = ExcelFixtures.workbook(
                List.of("用户名", "姓名", "初始密码", "角色", "教师工号"),
                List.of(List.of("unouser_" + TestData.suffix(), "无教师", "secret123", "teacher", "NO-SUCH")));

        upload(adminToken(), "preview", file)
                .then().statusCode(200)
                .body("errorCount", equalTo(1))
                .body("errors[0].field", equalTo("教师工号"));
    }

    @Test
    void shortPasswordIsRejected() throws IOException {
        byte[] file = ExcelFixtures.workbook(
                List.of("用户名", "姓名", "初始密码", "角色"),
                List.of(List.of("ushort_" + TestData.suffix(), "短密码", "123", "teacher")));

        upload(adminToken(), "preview", file)
                .then().statusCode(200)
                .body("errorCount", equalTo(1))
                .body("errors[0].field", equalTo("初始密码"));
    }

    @Test
    void templateHasExpectedHeaders() throws IOException {
        byte[] content = as(adminToken()).when().get("/api/users/import/template")
                .then().statusCode(200).extract().asByteArray();
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory
                .create(new java.io.ByteArrayInputStream(content))) {
            var header = workbook.getSheetAt(0).getRow(0);
            List<String> headers = new java.util.ArrayList<>();
            for (int i = 0; i < header.getLastCellNum(); i++) {
                headers.add(header.getCell(i).getStringCellValue());
            }
            org.assertj.core.api.Assertions.assertThat(headers)
                    .containsExactly("用户名", "姓名", "初始密码", "角色", "教师工号");
        }
    }

    @Test
    void teacherCannotImportAccounts() throws IOException {
        String username = "uimp_t_" + TestData.suffix();
        createUser(adminToken(), username, "teacher123", "teacher");
        String teacherToken = login(username, "teacher123");
        byte[] file = ExcelFixtures.workbook(
                List.of("用户名", "姓名", "初始密码", "角色"),
                List.of(List.of("x_" + TestData.suffix(), "越权", "secret123", "teacher")));
        upload(teacherToken, "preview", file).then().statusCode(403);
    }
}
