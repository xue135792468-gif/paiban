package com.paiban.common.excel;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

    /** One sheet exactly as laid out: no header assumption, no column trimming. */
    public record Grid(String sheetName, List<List<String>> rows) {}

    private static final int MAX_GRID_ROWS = 5000;
    private static final int MAX_GRID_COLUMNS = 60;

    private ExcelTableReader() {}

    /**
     * Every sheet of the workbook as a raw grid, empty sheets included (they come back
     * with no rows). The caller decides what to skip: a real school workbook mixes data
     * sheets with an instructions sheet, and only the import knows its own rules.
     */
    public static List<Grid> readAllGrids(InputStream input) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Excel 中没有工作表");
            }
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<Grid> grids = new ArrayList<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                grids.add(readSheetGrid(workbook.getSheetAt(index), formatter, evaluator));
            }
            return List.copyOf(grids);
        }
    }

    /**
     * Reads the workbook's only non-empty sheet as a raw grid: used cells only, merged
     * regions expanded to every covered cell, formulas replaced by their computed value.
     * Rows and columns are bounded by the actual cells (not the workbook's declared
     * dimension), and any gaps inside that range stay as empty strings so the AI sees
     * the real layout.
     *
     * <p>An empty leading sheet is skipped rather than imported as nothing. When more
     * than one sheet has data the reader refuses to guess: the user is asked to split
     * the file or keep one sheet.
     */
    public static Grid readGrid(InputStream input) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Excel 中没有工作表");
            }
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            List<Sheet> filled = new ArrayList<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet candidate = workbook.getSheetAt(index);
                if (hasContent(candidate, formatter, evaluator)) {
                    filled.add(candidate);
                }
            }
            if (filled.size() > 1) {
                String names = filled.stream().map(Sheet::getSheetName).collect(Collectors.joining("、"));
                throw new IllegalArgumentException(
                        "工作簿包含多个有数据的工作表（" + names + "），请拆分文件或只保留一个工作表");
            }
            Sheet sheet = filled.isEmpty() ? workbook.getSheetAt(0) : filled.get(0);
            return readSheetGrid(sheet, formatter, evaluator);
        }
    }

    private static Grid readSheetGrid(
            Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        int firstRow = Integer.MAX_VALUE;
        int lastRow = -1;
        int firstColumn = Integer.MAX_VALUE;
        int lastColumn = -1;
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (formatter.formatCellValue(cell, evaluator).trim().isEmpty()) {
                    continue;
                }
                firstRow = Math.min(firstRow, row.getRowNum());
                lastRow = Math.max(lastRow, row.getRowNum());
                firstColumn = Math.min(firstColumn, cell.getColumnIndex());
                lastColumn = Math.max(lastColumn, cell.getColumnIndex());
            }
        }
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            firstRow = Math.min(firstRow, region.getFirstRow());
            lastRow = Math.max(lastRow, region.getLastRow());
            firstColumn = Math.min(firstColumn, region.getFirstColumn());
            lastColumn = Math.max(lastColumn, region.getLastColumn());
        }
        if (lastRow < 0) {
            return new Grid(sheet.getSheetName(), List.of());
        }

        int rowCount = lastRow - firstRow + 1;
        int columnCount = lastColumn - firstColumn + 1;
        if (rowCount > MAX_GRID_ROWS) {
            throw new IllegalArgumentException(
                    "工作表「" + sheet.getSheetName() + "」使用了 " + rowCount
                            + " 行，超过 " + MAX_GRID_ROWS + " 行上限，请拆分文件后重试");
        }
        if (columnCount > MAX_GRID_COLUMNS) {
            throw new IllegalArgumentException(
                    "工作表「" + sheet.getSheetName() + "」使用了 " + columnCount
                            + " 列，超过 " + MAX_GRID_COLUMNS + " 列上限，请拆分文件后重试");
        }

        Map<Long, String> mergedValues = mergedValues(sheet, formatter, evaluator);
        List<List<String>> rows = new ArrayList<>();
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            List<String> values = new ArrayList<>();
            for (int c = firstColumn; c <= lastColumn; c++) {
                Cell cell = row == null ? null : row.getCell(c);
                values.add(cellValue(r, c, cell, formatter, evaluator, mergedValues));
            }
            rows.add(List.copyOf(values));
        }
        return new Grid(sheet.getSheetName(), List.copyOf(rows));
    }

    private static boolean hasContent(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (!formatter.formatCellValue(cell, evaluator).trim().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

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
