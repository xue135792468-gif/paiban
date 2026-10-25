package com.paiban.ai.imports.workbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paiban.common.excel.ExcelTableReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * The reader's two contracts: the workbook pipeline sees every sheet, and the legacy
 * single-sheet reader keeps refusing a multi-sheet file. Row capacity is the phase-11
 * change (500 → 5000) and the error has to name both the actual count and the limit.
 */
class WorkbookReaderLimitsTest {

    @Test
    void everySheetIsReadIncludingEmptyOnes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new XSSFWorkbook()) {
            write(workbook, "数据源", 3);
            workbook.createSheet("空表");
            write(workbook, "使用说明", 2);
            workbook.write(out);
        }

        List<ExcelTableReader.Grid> grids =
                ExcelTableReader.readAllGrids(new ByteArrayInputStream(out.toByteArray()));

        assertThat(grids).extracting(ExcelTableReader.Grid::sheetName)
                .containsExactly("数据源", "空表", "使用说明");
        assertThat(grids.get(0).rows()).hasSize(3);
        assertThat(grids.get(1).rows()).isEmpty();
    }

    @Test
    void legacyReaderStillRefusesAMultiSheetWorkbook() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new XSSFWorkbook()) {
            write(workbook, "数据源", 2);
            write(workbook, "班级课表", 2);
            workbook.write(out);
        }

        assertThatThrownBy(() -> ExcelTableReader.readGrid(new ByteArrayInputStream(out.toByteArray())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多个有数据的工作表");
    }

    @Test
    void aSheetOverFiveThousandRowsNamesTheActualCountAndTheLimit() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new XSSFWorkbook()) {
            write(workbook, "数据源", 5001);
            workbook.write(out);
        }
        byte[] bytes = out.toByteArray();

        assertThatThrownBy(() -> ExcelTableReader.readAllGrids(new ByteArrayInputStream(bytes)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5001")
                .hasMessageContaining("5000");
    }

    private void write(Workbook workbook, String name, int rows) {
        Sheet sheet = workbook.createSheet(name);
        List<String> values = new ArrayList<>(List.of("班级", "星期", "节次"));
        Row header = sheet.createRow(0);
        for (int c = 0; c < values.size(); c++) {
            header.createCell(c).setCellValue(values.get(c));
        }
        for (int r = 1; r < rows; r++) {
            Row row = sheet.createRow(r);
            row.createCell(0).setCellValue("八（1）");
            row.createCell(1).setCellValue("星期一");
            row.createCell(2).setCellValue(String.valueOf((r % 8) + 1));
        }
    }
}
