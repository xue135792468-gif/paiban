package com.paiban.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import com.paiban.support.ApiIntegrationTest;
import com.paiban.support.ScheduleFixture;
import com.paiban.support.TestData;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TimetableExportIntegrationTest extends ApiIntegrationTest {

    @Test
    void classExcelExportContainsHeaderAndLessons() throws Exception {
        String token = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.published(token, 5);

        byte[] content = as(token).when()
                .get("/api/timetable/class/" + fixture.classId() + "/export/excel")
                .then().statusCode(200)
                .header("Content-Disposition", containsString("attachment"))
                .extract().asByteArray();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("课表");
            Row header = sheet.getRow(2);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("节次");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("周一");

            boolean hasCourse = false;
            for (int r = 3; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                for (int c = 1; c < row.getLastCellNum(); c++) {
                    var cell = row.getCell(c);
                    if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                            && (cell.getStringCellValue().contains("语文")
                            || cell.getStringCellValue().contains("数学"))) {
                        hasCourse = true;
                    }
                }
            }
            assertThat(hasCourse).isTrue();
        }
    }

    @Test
    void classPdfExportIsParseable() throws Exception {
        String token = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.published(token, 5);

        byte[] content = as(token).when()
                .get("/api/timetable/class/" + fixture.classId() + "/export/pdf")
                .then().statusCode(200)
                .extract().asByteArray();

        assertThat(content.length).isGreaterThan(500);
        try (PDDocument document = Loader.loadPDF(content)) {
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void schoolExcelHasOneSheetPerClass() throws Exception {
        String token = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.published(token, 5);

        byte[] content = as(token).when().get("/api/timetable/school/export/excel")
                .then().statusCode(200).extract().asByteArray();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
        }
    }

    @Test
    void teacherAndRoomExportsWork() throws Exception {
        String token = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.published(token, 5);

        byte[] teacherExcel = as(token).when()
                .get("/api/timetable/teacher/" + fixture.teacherId() + "/export/excel")
                .then().statusCode(200).extract().asByteArray();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(teacherExcel))) {
            var sheet = workbook.getSheetAt(0);
            boolean hasLesson = false;
            for (int r = 3; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                for (int c = 1; c < row.getLastCellNum(); c++) {
                    var cell = row.getCell(c);
                    if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                            && !cell.getStringCellValue().isBlank()) {
                        hasLesson = true;
                    }
                }
            }
            assertThat(hasLesson).isTrue();
        }

        as(token).when().get("/api/timetable/room/" + fixture.roomId() + "/export/pdf")
                .then().statusCode(200);
    }

    @Test
    void teacherCannotExport() {
        String token = adminToken();
        ScheduleFixture.Result fixture = ScheduleFixture.published(token, 5);

        String username = "exp_" + TestData.suffix();
        as(token).body(java.util.Map.of(
                        "username", username,
                        "password", "teacher123",
                        "displayName", "教师",
                        "roleCodes", List.of("teacher"),
                        "teacherId", fixture.teacherId()))
                .when().post("/api/users").then().statusCode(200);
        String teacherToken = login(username, "teacher123");

        as(teacherToken).when().get("/api/timetable/class/" + fixture.classId() + "/export/excel")
                .then().statusCode(403);
    }
}
