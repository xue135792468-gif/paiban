package com.paiban.common.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelTableReaderTest {

    @Test
    void keepsTitleRowsAndMergedCellsInTheRawGrid() throws Exception {
        byte[] bytes = workbook(sheet -> {
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("2026 春季课表");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("班级");
            header.createCell(1).setCellValue("星期一");
            header.createCell(2).setCellValue("星期二");
            Row data = sheet.createRow(2);
            data.createCell(0).setCellValue("一年级1班");
            data.createCell(1).setCellValue("语文");
            data.createCell(2).setCellValue("数学");
        });

        ExcelTableReader.Grid grid = ExcelTableReader.readGrid(new ByteArrayInputStream(bytes));

        assertThat(grid.sheetName()).isEqualTo("课表");
        assertThat(grid.rows()).containsExactly(
                List.of("2026 春季课表", "2026 春季课表", "2026 春季课表"),
                List.of("班级", "星期一", "星期二"),
                List.of("一年级1班", "语文", "数学"));
    }

    @Test
    void formulasAreReadAsTheirComputedValue() throws Exception {
        byte[] bytes = workbook(sheet -> {
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("合计");
            row.createCell(1).setCellFormula("2*3");
        });

        ExcelTableReader.Grid grid = ExcelTableReader.readGrid(new ByteArrayInputStream(bytes));

        assertThat(grid.rows()).containsExactly(List.of("合计", "6"));
    }

    @Test
    void emptySheetHasNoRows() throws Exception {
        byte[] bytes = workbook(sheet -> {
            // Deliberately left blank.
        });

        ExcelTableReader.Grid grid = ExcelTableReader.readGrid(new ByteArrayInputStream(bytes));

        assertThat(grid.sheetName()).isEqualTo("课表");
        assertThat(grid.rows()).isEmpty();
    }

    @Test
    void boundsComeFromActualCellsRatherThanTheSheetOrigin() throws Exception {
        byte[] bytes = workbook(sheet -> {
            Row row = sheet.createRow(3);
            row.createCell(2).setCellValue("值");
        });

        ExcelTableReader.Grid grid = ExcelTableReader.readGrid(new ByteArrayInputStream(bytes));

        assertThat(grid.rows()).containsExactly(List.of("值"));
    }

    @Test
    void acceptsARealFiveHundredAndSixtyRowSheet() throws Exception {
        byte[] bytes = workbook(sheet -> {
            for (int r = 0; r < 560; r++) {
                sheet.createRow(r).createCell(0).setCellValue("第" + r + "行");
            }
        });

        ExcelTableReader.Grid grid = ExcelTableReader.readGrid(new ByteArrayInputStream(bytes));

        assertThat(grid.rows()).hasSize(560);
    }

    @Test
    void rejectsMoreThanFiveThousandRows() throws Exception {
        byte[] bytes = workbook(sheet -> {
            for (int r = 0; r <= 5000; r++) {
                sheet.createRow(r).createCell(0).setCellValue("第" + r + "行");
            }
        });

        assertThatThrownBy(() -> ExcelTableReader.readGrid(new ByteArrayInputStream(bytes)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5001")
                .hasMessageContaining("5000")
                .hasMessageContaining("拆分");
    }

    @Test
    void rejectsMoreThanSixtyColumns() throws Exception {
        byte[] bytes = workbook(sheet -> {
            Row row = sheet.createRow(0);
            for (int c = 0; c <= 60; c++) {
                row.createCell(c).setCellValue("列" + c);
            }
        });

        assertThatThrownBy(() -> ExcelTableReader.readGrid(new ByteArrayInputStream(bytes)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("60")
                .hasMessageContaining("拆分");
    }

    @Test
    void skipsAnEmptyLeadingSheetAndReadsTheSheetWithData() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("说明");
            Sheet second = workbook.createSheet("课表");
            second.createRow(0).createCell(0).setCellValue("一年级1班");
            workbook.write(out);
            bytes = out.toByteArray();
        }

        ExcelTableReader.Grid grid = ExcelTableReader.readGrid(new ByteArrayInputStream(bytes));

        assertThat(grid.sheetName()).isEqualTo("课表");
        assertThat(grid.rows()).containsExactly(List.of("一年级1班"));
    }

    @Test
    void rejectsWorkbooksWithMoreThanOneNonEmptySheet() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet first = workbook.createSheet("课表");
            first.createRow(0).createCell(0).setCellValue("第一张");
            Sheet second = workbook.createSheet("说明");
            second.createRow(0).createCell(0).setCellValue("第二张");
            workbook.write(out);
            bytes = out.toByteArray();
        }

        assertThatThrownBy(() -> ExcelTableReader.readGrid(new ByteArrayInputStream(bytes)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多个有数据的工作表")
                .hasMessageContaining("课表")
                .hasMessageContaining("说明")
                .hasMessageContaining("拆分");
    }

    @Test
    void multiLineCellContentIsKept() throws Exception {
        byte[] bytes = workbook(sheet -> {
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("语文\n数学");
        });

        ExcelTableReader.Grid grid = ExcelTableReader.readGrid(new ByteArrayInputStream(bytes));

        assertThat(grid.rows()).containsExactly(List.of("语文\n数学"));
    }

    private byte[] workbook(SheetBuilder builder) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("课表");
            builder.build(sheet);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    @FunctionalInterface
    private interface SheetBuilder {
        void build(Sheet sheet) throws Exception;
    }
}
