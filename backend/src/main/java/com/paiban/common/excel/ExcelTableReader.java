package com.paiban.common.excel;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;

/**
 * Reads the first sheet of an Excel workbook into a header-keyed table.
 * Tolerates merged cells, formula cells and extra columns.
 */
public final class ExcelTableReader {

    public record TableRow(int rowNumber, Map<String, String> values) {}

    public record Table(List<String> headers, List<TableRow> rows, List<String> extraHeaders) {}

    private ExcelTableReader() {}

    public static Table read(InputStream input) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Excel 中没有工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            int headerRowIndex = findHeaderRow(sheet, formatter, evaluator);
            if (headerRowIndex < 0) {
                throw new IllegalArgumentException("Excel 中没有表头行");
            }
            Map<Long, String> mergedValues = mergedValues(sheet, formatter, evaluator);

            Map<Integer, String> headerByColumn = new LinkedHashMap<>();
            List<String> headers = new ArrayList<>();
            Row headerRow = sheet.getRow(headerRowIndex);
            for (Cell cell : headerRow) {
                String value = cellValue(cell, formatter, evaluator, mergedValues);
                if (!value.isBlank()) {
                    headerByColumn.put(cell.getColumnIndex(), value);
                    headers.add(value);
                }
            }
            if (headerByColumn.isEmpty()) {
                throw new IllegalArgumentException("Excel 表头为空");
            }

            List<TableRow> rows = new ArrayList<>();
            for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                Map<String, String> values = new LinkedHashMap<>();
                boolean hasContent = false;
                for (Map.Entry<Integer, String> entry : headerByColumn.entrySet()) {
                    Cell cell = row == null ? null : row.getCell(entry.getKey());
                    String value = cellValue(r, entry.getKey(), cell, formatter, evaluator, mergedValues);
                    values.put(entry.getValue(), value);
                    if (!value.isBlank()) {
                        hasContent = true;
                    }
                }
                if (hasContent) {
                    rows.add(new TableRow(r + 1, values));
                }
            }

            List<String> extraHeaders = new ArrayList<>();
            if (sheet.getRow(headerRowIndex) != null) {
                for (Cell cell : sheet.getRow(headerRowIndex)) {
                    String value = cellValue(cell, formatter, evaluator, mergedValues);
                    if (!value.isBlank() && !headers.contains(value)) {
                        extraHeaders.add(value);
                    }
                }
            }
            return new Table(headers, rows, extraHeaders);
        }
    }

    private static int findHeaderRow(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), 20); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (!formatter.formatCellValue(cell, evaluator).trim().isEmpty()) {
                    return r;
                }
            }
        }
        return -1;
    }

    private static Map<Long, String> mergedValues(
            Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<Long, String> merged = new HashMap<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            Row firstRow = sheet.getRow(region.getFirstRow());
            if (firstRow == null) {
                continue;
            }
            Cell topLeft = firstRow.getCell(region.getFirstColumn());
            String value = topLeft == null ? "" : formatter.formatCellValue(topLeft, evaluator).trim();
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    merged.put(key(r, c), value);
                }
            }
        }
        return merged;
    }

    private static String cellValue(
            Cell cell, DataFormatter formatter, FormulaEvaluator evaluator, Map<Long, String> merged) {
        if (cell == null) {
            return "";
        }
        return cellValue(
                cell.getRowIndex(), cell.getColumnIndex(), cell, formatter, evaluator, merged);
    }

    private static String cellValue(
            int rowIndex,
            int columnIndex,
            Cell cell,
            DataFormatter formatter,
            FormulaEvaluator evaluator,
            Map<Long, String> merged) {
        String mergedValue = merged.get(key(rowIndex, columnIndex));
        if (mergedValue != null) {
            return mergedValue.trim();
        }
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell, evaluator).trim();
    }

    private static long key(int row, int column) {
        return ((long) row << 16) | column;
    }
}
