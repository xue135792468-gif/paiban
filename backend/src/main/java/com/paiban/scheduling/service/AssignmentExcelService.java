package com.paiban.scheduling.service;

import com.paiban.foundation.service.FoundationService;
import com.paiban.scheduling.web.AssignmentDtos.AssignmentDto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class AssignmentExcelService {

    private final AssignmentService assignments;
    private final FoundationService foundation;
    private final AssignmentImportService importService;

    public AssignmentExcelService(
            AssignmentService assignments,
            FoundationService foundation,
            AssignmentImportService importService) {
        this.assignments = assignments;
        this.foundation = foundation;
        this.importService = importService;
    }

    public byte[] template() {
        List<String> headers = importService.headers();
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("导入模板");
            CellStyle style = headerStyle(workbook);
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                var cell = header.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(style);
                sheet.setColumnWidth(i, 18 * 256);
            }
            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("高一(1)班");
            sample.createCell(1).setCellValue("数学");
            sample.createCell(2).setCellValue("张老师");
            sample.createCell(3).setCellValue(5);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成模板失败", ex);
        }
    }

    public byte[] export(Long semesterId) {
        Long resolved = foundation.resolveSemester(semesterId);
        List<AssignmentDto> rows = assignments.list(resolved, null, null, null);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("授课任务");
            CellStyle style = headerStyle(workbook);
            Row header = sheet.createRow(0);
            String[] headers = {"班级名称", "课程名称", "教师姓名", "每周节数"};
            for (int i = 0; i < headers.length; i++) {
                var cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(style);
                sheet.setColumnWidth(i, 20 * 256);
            }
            int index = 1;
            for (AssignmentDto row : rows) {
                Row excelRow = sheet.createRow(index++);
                excelRow.createCell(0).setCellValue(row.className() == null ? "" : row.className());
                excelRow.createCell(1).setCellValue(row.courseName() == null ? "" : row.courseName());
                excelRow.createCell(2).setCellValue(row.teacherName() == null ? "" : row.teacherName());
                excelRow.createCell(3).setCellValue(row.weeklyLessons());
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("导出失败", ex);
        }
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
