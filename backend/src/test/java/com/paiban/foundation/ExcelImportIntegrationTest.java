package com.paiban.foundation;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ExcelFixtures;
import com.paiban.support.TestData;
import io.restassured.response.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExcelImportIntegrationTest extends ApiIntegrationTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private Response upload(String token, String entity, String action, byte[] content, Long semesterId) {
        var request = io.restassured.RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "import.xlsx", content, XLSX);
        if (semesterId != null) {
            request = request.queryParam("semesterId", semesterId);
        }
        return request.when().post("/api/import/" + entity + "/" + action);
    }

    @Test
    void previewThenCommitImportsTeachers() throws IOException {
        String token = adminToken();
        String suffix = TestData.suffix();
        byte[] file = ExcelFixtures.workbook(
                List.of("姓名", "工号", "手机号", "学科"),
                List.of(
                        List.of("导入老师甲" + suffix, "IMP-A-" + suffix, "13800000001", "语文"),
                        List.of("导入老师乙" + suffix, "IMP-B-" + suffix, "13800000002", "数学")));

        upload(token, "teachers", "preview", file, null)
                .then().statusCode(200)
                .body("totalRows", equalTo(2))
                .body("validRows", equalTo(2))
                .body("errorCount", equalTo(0))
                .body("importedRows", equalTo(0));

        upload(token, "teachers", "commit", file, null)
                .then().statusCode(200)
                .body("importedRows", equalTo(2));

        as(token).when().get("/api/teachers")
                .then().statusCode(200)
                .body("name", hasItem("导入老师甲" + suffix))
                .body("name", hasItem("导入老师乙" + suffix));
    }

    @Test
    void oneDuplicateRowBlocksTheWholeCommit() throws IOException {
        String token = adminToken();
        String suffix = TestData.suffix();
        String existingNo = "DUP-" + suffix;
        as(token).body(Map.of(
                        "name", "已存在老师" + suffix,
                        "employeeNo", existingNo,
                        "subject", "语文"))
                .when().post("/api/teachers")
                .then().statusCode(200);

        String newName = "本该被拦截的新老师" + suffix;
        byte[] file = ExcelFixtures.workbook(
                List.of("姓名", "工号", "手机号", "学科"),
                List.of(
                        List.of("重复工号老师" + suffix, existingNo, "13800000011", "语文"),
                        List.of(newName, "NEW-" + suffix, "13800000012", "数学")));

        upload(token, "teachers", "commit", file, null)
                .then().statusCode(200)
                .body("importedRows", equalTo(0))
                .body("errorCount", greaterThanOrEqualTo(1))
                .body("errors.message", hasItem(containsString("工号已存在")));

        as(token).when().get("/api/teachers")
                .then().statusCode(200)
                .body("name", not(hasItem(newName)));
    }

    @Test
    void missingRequiredColumnReturnsBadRequest() throws IOException {
        byte[] file = ExcelFixtures.workbook(List.of("工号", "手机号"), List.of(List.of("X1", "13800000000")));
        upload(adminToken(), "teachers", "preview", file, null)
                .then().statusCode(400)
                .body("message", containsString("缺少必需的列"))
                .body("message", containsString("姓名"));
    }

    @Test
    void extraColumnIsReportedAsWarning() throws IOException {
        String suffix = TestData.suffix();
        byte[] file = ExcelFixtures.workbookWithExtraColumn(
                List.of("姓名"), List.of(List.of("多余列老师" + suffix)));
        upload(adminToken(), "teachers", "preview", file, null)
                .then().statusCode(200)
                .body("warnings[0]", containsString("备注"))
                .body("validRows", equalTo(1));
    }

    @Test
    void overlongFieldIsReportedWithRowAndColumn() throws IOException {
        String longName = "超".repeat(150);
        byte[] file = ExcelFixtures.workbook(List.of("姓名"), List.of(List.of(longName)));
        upload(adminToken(), "teachers", "preview", file, null)
                .then().statusCode(200)
                .body("errorCount", equalTo(1))
                .body("errors[0].row", equalTo(2))
                .body("errors[0].field", equalTo("姓名"))
                .body("errors[0].message", containsString("长度超过"));
    }

    @Test
    void formulaCellsAreEvaluated() throws IOException {
        String name = "公式老师" + TestData.suffix();
        byte[] file = ExcelFixtures.workbookWithFormula(name);
        upload(adminToken(), "teachers", "commit", file, null)
                .then().statusCode(200)
                .body("importedRows", equalTo(1));

        as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200)
                .body("name", hasItem(name));
    }

    @Test
    void mergedCellsReuseTopLeftValue() throws IOException {
        String name = "合并老师" + TestData.suffix();
        byte[] file = ExcelFixtures.workbookWithMergedCells(name, "13900000000");
        upload(adminToken(), "teachers", "commit", file, null)
                .then().statusCode(200)
                .body("importedRows", equalTo(1));

        as(adminToken()).when().get("/api/teachers")
                .then().statusCode(200)
                .body("find { it.name == '" + name + "' }.email", equalTo("13900000000"));
    }

    @Test
    void classImportResolvesGradeAndHeadTeacher() throws IOException {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        String suffix = TestData.suffix();
        String gradeName = "初一" + suffix;
        String teacherName = "班主任" + suffix;
        TestData.grade(token, semesterId, gradeName);
        TestData.teacher(token, teacherName);

        byte[] file = ExcelFixtures.workbook(
                List.of("年级名称", "班级名称", "班主任姓名", "学生人数"),
                List.of(List.of(gradeName, "初一1班" + suffix, teacherName, "48")));

        upload(token, "classes", "commit", file, semesterId)
                .then().statusCode(200)
                .body("importedRows", equalTo(1));

        as(token).queryParam("semesterId", semesterId)
                .when().get("/api/classes")
                .then().statusCode(200)
                .body("find { it.name == '初一1班" + suffix + "' }.headTeacherName", equalTo(teacherName))
                .body("find { it.name == '初一1班" + suffix + "' }.studentCount", equalTo(48))
                .body("find { it.name == '初一1班" + suffix + "' }.teachingClassId", notNullValue());
    }

    @Test
    void classImportRejectsUnknownGrade() throws IOException {
        String token = adminToken();
        long semesterId = TestData.semester(token);
        byte[] file = ExcelFixtures.workbook(
                List.of("年级名称", "班级名称"),
                List.of(List.of("不存在的年级" + TestData.suffix(), "幽灵班" + TestData.suffix())));
        upload(token, "classes", "preview", file, semesterId)
                .then().statusCode(200)
                .body("errorCount", greaterThanOrEqualTo(1))
                .body("errors[0].field", equalTo("年级名称"));
    }

    @Test
    void exportReturnsExcelWithHeadersAndRows() throws IOException {
        String token = adminToken();
        String name = "导出老师" + TestData.suffix();
        TestData.teacher(token, name);

        byte[] content = as(token).when().get("/api/export/teachers")
                .then().statusCode(200)
                .header("Content-Disposition", containsString("attachment"))
                .extract().asByteArray();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            org.assertj.core.api.Assertions.assertThat(header.getCell(0).getStringCellValue()).isEqualTo("姓名");
            boolean found = false;
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row != null && row.getCell(0) != null && name.equals(row.getCell(0).getStringCellValue())) {
                    found = true;
                }
            }
            org.assertj.core.api.Assertions.assertThat(found).isTrue();
        }
    }

    @Test
    void commitWritesAuditLog() throws IOException {
        String token = adminToken();
        String suffix = TestData.suffix();
        byte[] file = ExcelFixtures.workbook(List.of("姓名"), List.of(List.of("审计导入" + suffix)));
        upload(token, "teachers", "commit", file, null).then().statusCode(200);

        as(token).when().get("/api/foundation/audit-logs")
                .then().statusCode(200)
                .body("entityType", hasItem("teachers"))
                .body("find { it.entityType == 'teachers' }.successRows", greaterThanOrEqualTo(1));
    }

    @Test
    void teacherRoleCannotImport() throws IOException {
        String username = "imp_" + TestData.suffix();
        createUser(adminToken(), username, "teacher123", "teacher");
        String teacherToken = login(username, "teacher123");
        byte[] file = ExcelFixtures.workbook(List.of("姓名"), List.of(List.of("越权导入")));
        upload(teacherToken, "teachers", "preview", file, null).then().statusCode(403);
    }

    @Test
    void templateDownloadContainsExpectedHeaders() throws IOException {
        byte[] content = as(adminToken()).when().get("/api/import/teachers/template")
                .then().statusCode(200).extract().asByteArray();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Row header = workbook.getSheetAt(0).getRow(0);
            List<String> headers = new java.util.ArrayList<>();
            for (int i = 0; i < header.getLastCellNum(); i++) {
                headers.add(header.getCell(i).getStringCellValue());
            }
            org.assertj.core.api.Assertions.assertThat(headers).contains("姓名", "工号", "手机号");
        }
    }
}
