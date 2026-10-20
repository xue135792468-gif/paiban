package com.paiban.foundation.service;

import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.foundation.web.FoundationDtos.CourseDto;
import com.paiban.foundation.web.FoundationDtos.GradeDto;
import com.paiban.foundation.web.FoundationDtos.RoomDto;
import com.paiban.foundation.web.FoundationDtos.TeacherDto;
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
public class FoundationExcelService {

    private final FoundationService foundation;
    private final FoundationImportService importService;

    public FoundationExcelService(FoundationService foundation, FoundationImportService importService) {
        this.foundation = foundation;
        this.importService = importService;
    }

    public byte[] template(String entity) {
        List<String> headers = importService.spec(entity).headers();
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("导入模板");
            writeHeader(workbook, sheet, headers);
            Row sample = sheet.createRow(1);
            for (int i = 0; i < headers.size(); i++) {
                sample.createCell(i).setCellValue(sampleValue(entity, headers.get(i)));
                sheet.setColumnWidth(i, Math.max(16, headers.get(i).length() * 4) * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成模板失败", ex);
        }
    }

    public byte[] export(String entity, Long semesterId) {
        List<String> headers = exportHeaders(entity);
        List<List<String>> rows = exportRows(entity, semesterId);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("数据");
            writeHeader(workbook, sheet, headers);
            int index = 1;
            for (List<String> values : rows) {
                Row row = sheet.createRow(index++);
                for (int i = 0; i < values.size(); i++) {
                    row.createCell(i).setCellValue(values.get(i) == null ? "" : values.get(i));
                }
            }
            for (int i = 0; i < headers.size(); i++) {
                sheet.setColumnWidth(i, 18 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("导出失败", ex);
        }
    }

    private List<String> exportHeaders(String entity) {
        return switch (entity) {
            case "teachers" -> List.of("姓名", "工号", "手机号", "邮箱", "学科", "状态");
            case "rooms" -> List.of("教室名称", "容量", "类型", "楼栋");
            case "courses" -> List.of("课程名称", "学科", "是否允许连堂", "是否必须室外", "所需教室类型", "颜色");
            case "grades" -> List.of("年级名称", "排序");
            case "classes" -> List.of("年级名称", "班级名称", "班主任姓名", "学生人数");
            default -> throw new IllegalArgumentException("不支持导出的实体: " + entity);
        };
    }

    private List<List<String>> exportRows(String entity, Long semesterId) {
        return switch (entity) {
            case "teachers" -> foundation.listTeachers().stream()
                    .map(this::teacherRow)
                    .toList();
            case "rooms" -> foundation.listRooms().stream()
                    .map(this::roomRow)
                    .toList();
            case "courses" -> foundation.listCourses().stream()
                    .map(this::courseRow)
                    .toList();
            case "grades" -> foundation.listGrades(semesterId).stream()
                    .map(this::gradeRow)
                    .toList();
            case "classes" -> foundation.listClasses(semesterId).stream()
                    .map(this::classRow)
                    .toList();
            default -> throw new IllegalArgumentException("不支持导出的实体: " + entity);
        };
    }

    private List<String> teacherRow(TeacherDto teacher) {
        return java.util.Arrays.asList(
                teacher.name(),
                teacher.employeeNo(),
                teacher.phone(),
                teacher.email(),
                teacher.subject(),
                teacher.active() ? "启用" : "停用");
    }

    private List<String> roomRow(RoomDto room) {
        return java.util.Arrays.asList(
                room.name(), String.valueOf(room.capacity()), room.type(), room.building());
    }

    private List<String> courseRow(CourseDto course) {
        return java.util.Arrays.asList(
                course.name(),
                course.subject(),
                course.allowConsecutive() ? "是" : "否",
                course.requireOutdoor() ? "是" : "否",
                course.requiredRoomType(),
                course.color());
    }

    private List<String> gradeRow(GradeDto grade) {
        return java.util.Arrays.asList(grade.name(), String.valueOf(grade.sortOrder()));
    }

    private List<String> classRow(ClassDto adminClass) {
        return java.util.Arrays.asList(
                adminClass.gradeName(),
                adminClass.name(),
                adminClass.headTeacherName(),
                String.valueOf(adminClass.studentCount()));
    }

    private void writeHeader(XSSFWorkbook workbook, Sheet sheet, List<String> headers) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            var cell = header.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(style);
        }
    }

    private String sampleValue(String entity, String header) {
        return switch (entity + ":" + header) {
            case "teachers:姓名" -> "张老师";
            case "teachers:工号" -> "T001";
            case "teachers:手机号" -> "13800000000";
            case "teachers:邮箱" -> "zhang@example.com";
            case "teachers:学科" -> "数学";
            case "rooms:教室名称" -> "高一(1)班教室";
            case "rooms:容量" -> "50";
            case "rooms:类型" -> "NORMAL";
            case "rooms:楼栋" -> "教学楼A";
            case "courses:课程名称" -> "数学";
            case "courses:学科" -> "数学";
            case "courses:是否允许连堂" -> "是";
            case "courses:是否必须室外" -> "否";
            case "courses:所需教室类型" -> "NORMAL";
            case "courses:颜色" -> "#AEC6CF";
            case "grades:年级名称" -> "高一";
            case "grades:排序" -> "1";
            case "classes:年级名称" -> "高一";
            case "classes:班级名称" -> "高一(1)班";
            case "classes:班主任姓名" -> "张老师";
            case "classes:学生人数" -> "45";
            default -> "";
        };
    }
}
