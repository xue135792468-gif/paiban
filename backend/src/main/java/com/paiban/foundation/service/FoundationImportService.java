package com.paiban.foundation.service;

import com.paiban.common.excel.ExcelTableReader;
import com.paiban.common.excel.ExcelTableReader.Table;
import com.paiban.common.excel.ExcelTableReader.TableRow;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.GradeRepository;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.web.FoundationDtos.SaveClassRequest;
import com.paiban.foundation.web.FoundationDtos.SaveCourseRequest;
import com.paiban.foundation.web.FoundationDtos.SaveGradeRequest;
import com.paiban.foundation.web.FoundationDtos.SaveRoomRequest;
import com.paiban.foundation.web.FoundationDtos.SaveTeacherRequest;
import com.paiban.foundation.web.ImportDtos.ImportError;
import com.paiban.foundation.web.ImportDtos.ImportReport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Two-phase Excel import: preview validates every row without touching the
 * database, commit inserts the valid rows and writes an audit entry.
 */
@Service
public class FoundationImportService {

    public static final List<String> ENTITIES = List.of("teachers", "rooms", "courses", "grades", "classes");

    private record Column(String header, boolean required, int maxLength) {}

    public record Spec(String entity, String displayName, List<Column> columns) {
        public List<String> headers() {
            return columns.stream().map(Column::header).toList();
        }

        public List<String> requiredHeaders() {
            return columns.stream().filter(Column::required).map(Column::header).toList();
        }
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        SPECS.put("teachers", new Spec("teachers", "教师", List.of(
                new Column("姓名", true, 100),
                new Column("工号", false, 50),
                new Column("手机号", false, 30),
                new Column("邮箱", false, 120),
                new Column("学科", false, 100))));
        SPECS.put("rooms", new Spec("rooms", "教室", List.of(
                new Column("教室名称", true, 100),
                new Column("容量", false, 10),
                new Column("类型", false, 50),
                new Column("楼栋", false, 100))));
        SPECS.put("courses", new Spec("courses", "课程", List.of(
                new Column("课程名称", true, 100),
                new Column("学科", false, 100),
                new Column("是否允许连堂", false, 10),
                new Column("是否必须室外", false, 10),
                new Column("所需教室类型", false, 50),
                new Column("颜色", false, 20))));
        SPECS.put("grades", new Spec("grades", "年级", List.of(
                new Column("年级名称", true, 100),
                new Column("排序", false, 10))));
        SPECS.put("classes", new Spec("classes", "行政班", List.of(
                new Column("年级名称", true, 100),
                new Column("班级名称", true, 100),
                new Column("班主任姓名", false, 100),
                new Column("学生人数", false, 10))));
    }

    private final FoundationService foundation;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;
    private final CourseRepository courses;
    private final GradeRepository grades;
    private final AdminClassRepository adminClasses;

    public FoundationImportService(
            FoundationService foundation,
            TeacherRepository teachers,
            RoomRepository rooms,
            CourseRepository courses,
            GradeRepository grades,
            AdminClassRepository adminClasses) {
        this.foundation = foundation;
        this.teachers = teachers;
        this.rooms = rooms;
        this.courses = courses;
        this.grades = grades;
        this.adminClasses = adminClasses;
    }

    public Spec spec(String entity) {
        Spec spec = SPECS.get(entity);
        if (spec == null) {
            throw ApiException.notFound("不支持导入的实体: " + entity);
        }
        return spec;
    }

    public ImportReport preview(String entity, MultipartFile file, Long semesterId) {
        return handle(entity, file, semesterId, false, "system");
    }

    @Transactional
    public ImportReport commit(String entity, MultipartFile file, Long semesterId, String actor) {
        return handle(entity, file, semesterId, true, actor);
    }

    private ImportReport handle(String entity, MultipartFile file, Long semesterId, boolean commit, String actor) {
        Spec spec = spec(entity);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请上传 Excel 文件");
        }
        Long resolvedSemester = ("grades".equals(entity) || "classes".equals(entity))
                ? foundation.resolveSemester(semesterId)
                : semesterId;

        Table table;
        try {
            table = ExcelTableReader.read(file.getInputStream());
        } catch (IOException | RuntimeException ex) {
            throw ApiException.badRequest("无法解析 Excel 文件: " + ex.getMessage());
        }

        List<String> missing = spec.requiredHeaders().stream()
                .filter(header -> !table.headers().contains(header))
                .toList();
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("缺少必需的列: " + String.join("、", missing));
        }

        List<ImportError> errors = new ArrayList<>();
        List<Map<String, String>> validRows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> extraHeaders = table.headers().stream()
                .filter(header -> !spec.headers().contains(header))
                .toList();
        if (!extraHeaders.isEmpty()) {
            warnings.add("以下列不会被导入，已忽略: " + String.join("、", extraHeaders));
        }

        Set<String> seenKeys = new HashSet<>();
        for (TableRow row : table.rows()) {
            List<ImportError> rowErrors = new ArrayList<>();
            Map<String, String> normalized = normalize(spec, row, resolvedSemester, seenKeys, rowErrors);
            if (rowErrors.isEmpty()) {
                validRows.add(normalized);
            } else {
                errors.addAll(rowErrors);
            }
        }

        int imported = 0;
        if (commit && !validRows.isEmpty()) {
            for (Map<String, String> row : validRows) {
                insert(spec.entity(), row, resolvedSemester);
                imported++;
            }
            foundation.audit(
                    actor,
                    "IMPORT",
                    spec.entity(),
                    "文件: " + file.getOriginalFilename() + ", 成功 " + imported + " 行, 失败 "
                            + (table.rows().size() - imported) + " 行",
                    imported,
                    table.rows().size() - imported);
        }

        return new ImportReport(
                spec.entity(),
                spec.displayName(),
                table.rows().size(),
                validRows.size(),
                errors.size(),
                imported,
                errors,
                warnings,
                commit ? List.of() : validRows);
    }

    private Map<String, String> normalize(
            Spec spec,
            TableRow row,
            Long semesterId,
            Set<String> seenKeys,
            List<ImportError> errors) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Column column : spec.columns()) {
            String value = row.values().getOrDefault(column.header(), "").trim();
            if (column.required() && value.isEmpty()) {
                errors.add(new ImportError(row.rowNumber(), column.header(), "不能为空"));
            }
            if (value.length() > column.maxLength()) {
                errors.add(new ImportError(
                        row.rowNumber(), column.header(), "长度超过 " + column.maxLength() + " 个字符"));
            }
            values.put(column.header(), value);
        }

        switch (spec.entity()) {
            case "teachers" -> normalizeTeacher(row.rowNumber(), values, seenKeys, errors);
            case "rooms" -> normalizeRoom(row.rowNumber(), values, seenKeys, errors);
            case "courses" -> normalizeCourse(row.rowNumber(), values, seenKeys, errors);
            case "grades" -> normalizeGrade(row.rowNumber(), values, semesterId, seenKeys, errors);
            case "classes" -> normalizeClass(row.rowNumber(), values, semesterId, seenKeys, errors);
            default -> throw ApiException.notFound("不支持导入的实体: " + spec.entity());
        }
        return values;
    }

    private void normalizeTeacher(int row, Map<String, String> v, Set<String> seen, List<ImportError> errors) {
        String name = v.get("姓名");
        String employeeNo = v.get("工号");
        if (!name.isEmpty() && !seen.add("name:" + name)) {
            errors.add(new ImportError(row, "姓名", "文件内姓名重复"));
        }
        if (!employeeNo.isEmpty()) {
            if (!seen.add("no:" + employeeNo)) {
                errors.add(new ImportError(row, "工号", "文件内工号重复"));
            }
            if (teachers.existsByEmployeeNo(employeeNo)) {
                errors.add(new ImportError(row, "工号", "工号已存在"));
            }
        }
    }

    private void normalizeRoom(int row, Map<String, String> v, Set<String> seen, List<ImportError> errors) {
        String name = v.get("教室名称");
        if (!name.isEmpty() && !seen.add("room:" + name)) {
            errors.add(new ImportError(row, "教室名称", "文件内教室名称重复"));
        }
        if (!name.isEmpty() && rooms.findByName(name).isPresent()) {
            errors.add(new ImportError(row, "教室名称", "教室名称已存在"));
        }
        String capacity = v.get("容量");
        if (!capacity.isEmpty()) {
            try {
                int parsed = Integer.parseInt(capacity);
                if (parsed <= 0) {
                    errors.add(new ImportError(row, "容量", "容量必须为正整数"));
                }
            } catch (NumberFormatException ex) {
                errors.add(new ImportError(row, "容量", "容量必须是整数"));
            }
        }
        String type = v.get("类型");
        if (!type.isEmpty()) {
            String normalized = normalizeRoomType(type);
            if (normalized == null) {
                errors.add(new ImportError(row, "类型", "未知教室类型: " + type));
            } else {
                v.put("类型", normalized);
            }
        }
    }

    private void normalizeCourse(int row, Map<String, String> v, Set<String> seen, List<ImportError> errors) {
        String name = v.get("课程名称");
        if (!name.isEmpty() && !seen.add("course:" + name)) {
            errors.add(new ImportError(row, "课程名称", "文件内课程名称重复"));
        }
        if (!name.isEmpty() && courses.findByName(name).isPresent()) {
            errors.add(new ImportError(row, "课程名称", "课程名称已存在"));
        }
        v.put("是否允许连堂", String.valueOf(parseBoolean(row, "是否允许连堂", v.get("是否允许连堂"), errors)));
        v.put("是否必须室外", String.valueOf(parseBoolean(row, "是否必须室外", v.get("是否必须室外"), errors)));
        String roomType = v.get("所需教室类型");
        if (!roomType.isEmpty()) {
            String normalized = normalizeRoomType(roomType);
            if (normalized == null) {
                errors.add(new ImportError(row, "所需教室类型", "未知教室类型: " + roomType));
            } else {
                v.put("所需教室类型", normalized);
            }
        }
    }

    private void normalizeGrade(
            int row, Map<String, String> v, Long semesterId, Set<String> seen, List<ImportError> errors) {
        String name = v.get("年级名称");
        if (!name.isEmpty() && !seen.add("grade:" + name)) {
            errors.add(new ImportError(row, "年级名称", "文件内年级重复"));
        }
        if (!name.isEmpty() && grades.existsBySemesterIdAndName(semesterId, name)) {
            errors.add(new ImportError(row, "年级名称", "该学期已存在此年级"));
        }
        String sortOrder = v.get("排序");
        if (!sortOrder.isEmpty()) {
            try {
                Integer.parseInt(sortOrder);
            } catch (NumberFormatException ex) {
                errors.add(new ImportError(row, "排序", "排序必须是整数"));
            }
        }
    }

    private void normalizeClass(
            int row, Map<String, String> v, Long semesterId, Set<String> seen, List<ImportError> errors) {
        String gradeName = v.get("年级名称");
        String name = v.get("班级名称");
        if (!gradeName.isEmpty() && grades.findBySemesterIdAndName(semesterId, gradeName).isEmpty()) {
            errors.add(new ImportError(row, "年级名称", "该学期不存在此年级"));
        }
        if (!name.isEmpty() && !seen.add("class:" + name)) {
            errors.add(new ImportError(row, "班级名称", "文件内班级名称重复"));
        }
        if (!name.isEmpty() && adminClasses.existsBySemesterIdAndName(semesterId, name)) {
            errors.add(new ImportError(row, "班级名称", "该学期已存在此班级"));
        }
        String headTeacher = v.get("班主任姓名");
        if (!headTeacher.isEmpty() && teachers.findByName(headTeacher).isEmpty()) {
            errors.add(new ImportError(row, "班主任姓名", "教师不存在: " + headTeacher));
        }
        String studentCount = v.get("学生人数");
        if (!studentCount.isEmpty()) {
            try {
                if (Integer.parseInt(studentCount) <= 0) {
                    errors.add(new ImportError(row, "学生人数", "学生人数必须为正整数"));
                }
            } catch (NumberFormatException ex) {
                errors.add(new ImportError(row, "学生人数", "学生人数必须是整数"));
            }
        }
    }

    private void insert(String entity, Map<String, String> row, Long semesterId) {
        switch (entity) {
            case "teachers" -> foundation.createTeacher(new SaveTeacherRequest(
                    row.get("姓名"), row.get("工号"), row.get("手机号"), row.get("邮箱"), row.get("学科"), true));
            case "rooms" -> foundation.createRoom(new SaveRoomRequest(
                    row.get("教室名称"),
                    intOrNull(row.get("容量")),
                    row.get("类型").isEmpty() ? "NORMAL" : row.get("类型"),
                    row.get("楼栋")));
            case "courses" -> foundation.createCourse(new SaveCourseRequest(
                    row.get("课程名称"),
                    row.get("学科"),
                    Boolean.parseBoolean(row.get("是否允许连堂")),
                    Boolean.parseBoolean(row.get("是否必须室外")),
                    row.get("所需教室类型").isEmpty() ? null : row.get("所需教室类型"),
                    row.get("颜色")));
            case "grades" -> foundation.createGrade(
                    semesterId, new SaveGradeRequest(row.get("年级名称"), intOrNull(row.get("排序"))));
            case "classes" -> {
                Long gradeId = grades.findBySemesterIdAndName(semesterId, row.get("年级名称"))
                        .orElseThrow(() -> ApiException.badRequest("年级不存在: " + row.get("年级名称")))
                        .getId();
                Long headTeacherId = row.get("班主任姓名").isEmpty()
                        ? null
                        : teachers.findByName(row.get("班主任姓名")).stream()
                                .findFirst()
                                .map(Teacher::getId)
                                .orElse(null);
                foundation.createClass(
                        semesterId,
                        new SaveClassRequest(
                                gradeId,
                                row.get("班级名称"),
                                headTeacherId,
                                intOrNull(row.get("学生人数"))));
            }
            default -> throw ApiException.badRequest("不支持导入的实体: " + entity);
        }
    }

    private Boolean parseBoolean(int row, String field, String value, List<ImportError> errors) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return switch (value.trim().toLowerCase()) {
            case "是", "true", "1", "y", "yes" -> true;
            case "否", "false", "0", "n", "no" -> false;
            default -> {
                errors.add(new ImportError(row, field, "只能填写 是/否"));
                yield false;
            }
        };
    }

    private String normalizeRoomType(String value) {
        return switch (value.trim().toUpperCase()) {
            case "NORMAL", "普通教室" -> "NORMAL";
            case "LAB", "实验室" -> "LAB";
            case "PLAYGROUND", "操场", "室外" -> "PLAYGROUND";
            case "MULTIMEDIA", "多媒体" -> "MULTIMEDIA";
            case "OTHER", "其他" -> "OTHER";
            default -> null;
        };
    }

    private Integer intOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Integer.parseInt(value.trim());
    }
}
