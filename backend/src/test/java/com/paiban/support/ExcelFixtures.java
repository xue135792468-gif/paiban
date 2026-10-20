package com.paiban.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ExcelFixtures {

    private ExcelFixtures() {}

    public static byte[] workbook(List<String> headers, List<List<String>> rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    row.createCell(c).setCellValue(values.get(c));
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] workbookWithFormula(String name) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("姓名");
            header.createCell(1).setCellValue("工号");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellFormula("\"" + name + "\"");
            row.createCell(1).setCellValue("T-FORMULA-" + System.nanoTime());
            workbook.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] workbookWithMergedCells(String name, String phone) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("姓名");
            header.createCell(1).setCellValue("手机号");
            header.createCell(2).setCellValue("邮箱");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(name);
            row.createCell(1).setCellValue(phone);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 1, 2));
            workbook.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] workbookWithExtraColumn(List<String> headers, List<List<String>> rows) throws IOException {
        List<String> extendedHeaders = new java.util.ArrayList<>(headers);
        extendedHeaders.add("备注");
        List<List<String>> extendedRows = new java.util.ArrayList<>();
        for (List<String> row : rows) {
            List<String> copy = new java.util.ArrayList<>(row);
            copy.add("无关列");
            extendedRows.add(copy);
        }
        return workbook(extendedHeaders, extendedRows);
    }
}
