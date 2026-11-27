package com.paiban.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class TeachingMatrixWorkbookTest {

    @Test
    void detectsTheHeaderAndUsesSheetRowNumbersAndUnroundedStudentCounts() throws IOException {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("八年级");
            sheet.createRow(2).createCell(0).setCellValue("学校任课表");
            var header = sheet.createRow(4);
            header.createCell(0).setCellValue("班　级");
            header.createCell(1).setCellValue("人数");
            header.createCell(2).setCellValue("道德与法\n治");
            var row = sheet.createRow(7);
            row.createCell(0).setCellValue(1);
            var count = row.createCell(1);
            count.setCellValue(42.5);
            var roundedFormat = workbook.createCellStyle();
            roundedFormat.setDataFormat(workbook.createDataFormat().getFormat("0"));
            count.setCellStyle(roundedFormat);
            row.createCell(2).setCellValue("李老师");
            workbook.write(bytes);

            var input = TeachingMatrixWorkbook.read(new ByteArrayInputStream(bytes.toByteArray()));
            assertThat(input.errors()).isEmpty();
            assertThat(input.rows()).singleElement().satisfies(result -> {
                assertThat(result.number()).isEqualTo(8);
                assertThat(result.className()).isEqualTo("1");
                assertThat(result.students()).isEqualTo("42.5");
                assertThat(result.cells()).containsOnlyKeys("道德与法治").containsValue("李老师");
            });
        }
    }

    @Test
    void duplicateNormalizedSubjectHeadersAreRejected() throws IOException {
        byte[] contents = com.paiban.support.ExcelFixtures.workbook(
                java.util.List.of("班级", "道德与法治", "道德与法\n治"), java.util.List.of());
        var input = TeachingMatrixWorkbook.read(new ByteArrayInputStream(contents));
        assertThat(input.rows()).isEmpty();
        assertThat(input.errors()).singleElement().satisfies(error -> {
            assertThat(error.row()).isEqualTo(1);
            assertThat(error.field()).isEqualTo("道德与法治");
            assertThat(error.message()).contains("重复");
        });
    }
}
