package com.paiban.scheduling.service;

import com.paiban.common.excel.ExcelTableReader;
import com.paiban.foundation.web.ImportDtos.ImportError;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class TeachingMatrixWorkbook {

    static final String CLASS = "班级";
    static final String STUDENTS = "人数";
    static final String HOMEROOM = "班主任";
    static final List<String> CLASS_HEADERS = List.of(CLASS, STUDENTS, HOMEROOM);

    record InputRow(int number, String className, String students, String homeroom, Map<String, String> cells) {}
    record Input(List<InputRow> rows, List<ImportError> errors) {}
    private record Tab(Sheet sheet, Row header, Map<Integer, String> columns) {}

    private TeachingMatrixWorkbook() {}

    static Input read(InputStream input) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Tab tab = null;
            for (Sheet sheet : workbook) {
                Tab candidate = header(sheet, formatter, evaluator);
                if (candidate != null) {
                    if (tab != null) {
                        return failure("找到多张任课表，请只保留目标年级的工作表");
                    }
                    tab = candidate;
                }
            }
            if (tab == null) {
                return failure("未找到包含「班级」的表头");
            }
            return readRows(tab, formatter, evaluator);
        }
    }

    private static Tab header(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (Row row : sheet) {
            Map<Integer, String> columns = new LinkedHashMap<>();
            for (Cell cell : row) {
                String text = cleanHeader(formatter.formatCellValue(cell, evaluator));
                if (!text.isEmpty()) {
                    columns.put(cell.getColumnIndex(), text);
                }
            }
            if (columns.containsValue(CLASS)) {
                return new Tab(sheet, row, columns);
            }
        }
        return null;
    }

    private static Input readRows(Tab tab, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<ImportError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int headerLine = tab.header().getRowNum() + 1;
        for (String name : tab.columns().values()) {
            if (!seen.add(name)) {
                errors.add(new ImportError(headerLine, name, "表头重复"));
            } else if (name.length() > 100) {
                errors.add(new ImportError(headerLine, name, "课程名称不能超过 100 个字符"));
            }
        }
        if (!errors.isEmpty()) {
            return new Input(List.of(), errors);
        }

        List<InputRow> rows = new ArrayList<>();
        for (Row row : tab.sheet()) {
            if (row.getRowNum() < headerLine) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (var column : tab.columns().entrySet()) {
                String name = column.getValue();
                Cell cell = row.getCell(column.getKey());
                String value = numericOrText(cell, formatter, evaluator, name);
                if (!value.isEmpty()) {
                    values.put(name, value);
                }
            }
            if (!values.isEmpty()) {
                String className = values.remove(CLASS);
                String students = values.remove(STUDENTS);
                String homeroom = values.remove(HOMEROOM);
                rows.add(new InputRow(row.getRowNum() + 1, className, students, homeroom, values));
            }
        }
        return new Input(rows, errors);
    }

    private static String numericOrText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator, String header) {
        if (cell == null) {
            return "";
        }
        if (CLASS.equals(header) || STUDENTS.equals(header)) {
            String number = ExcelTableReader.exactNumber(cell, evaluator);
            if (number != null) {
                return number;
            }
        }
        return formatter.formatCellValue(cell, evaluator).replaceAll("^[\\p{Z}\\s]+|[\\p{Z}\\s]+$", "");
    }

    static String cleanHeader(String text) {
        return text.replaceAll("[\\p{Z}\\s]+", "");
    }

    static Input failure(String message) {
        return new Input(List.of(), List.of(new ImportError(0, "文件", message)));
    }

    static byte[] write(List<String> headers, List<List<String>> values) throws IOException {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("任课表");
            int rowIndex = 0;
            var font = workbook.createFont();
            font.setBold(true);
            var headerStyle = workbook.createCellStyle();
            headerStyle.setFont(font);
            Row header = sheet.createRow(rowIndex++);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 18 * 256);
            }
            for (List<String> valuesInRow : values) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < valuesInRow.size(); i++) {
                    String value = valuesInRow.get(i);
                    if (value != null && !value.isEmpty()) {
                        row.createCell(i).setCellValue(value);
                    }
                }
            }
            sheet.createFreezePane(3, 1);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
