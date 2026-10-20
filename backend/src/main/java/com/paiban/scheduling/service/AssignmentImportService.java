package com.paiban.scheduling.service;

import com.paiban.common.excel.ExcelTableReader;
import com.paiban.common.excel.ExcelTableReader.TableRow;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.foundation.web.FoundationDtos.CourseDto;
import com.paiban.foundation.web.FoundationDtos.TeacherDto;
import com.paiban.foundation.web.ImportDtos.ImportError;
import com.paiban.foundation.web.ImportDtos.ImportReport;
import com.paiban.scheduling.web.AssignmentDtos.SaveAssignmentRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Excel import for assignments: 班级名称 / 课程名称 / 教师姓名 / 每周节数. */
@Service
public class AssignmentImportService {

    private static final List<String> HEADERS =
            List.of("班级名称", "课程名称", "教师姓名", "每周节数");
    private static final List<String> REQUIRED = List.of("班级名称", "课程名称", "教师姓名", "每周节数");

    private final FoundationService foundation;
    private final AssignmentService assignments;
    private final com.paiban.scheduling.domain.AssignmentRepository assignmentRepository;

    public AssignmentImportService(
            FoundationService foundation,
            AssignmentService assignments,
            com.paiban.scheduling.domain.AssignmentRepository assignmentRepository) {
        this.foundation = foundation;
        this.assignments = assignments;
        this.assignmentRepository = assignmentRepository;
    }

    public List<String> headers() {
        return HEADERS;
    }

    public ImportReport preview(MultipartFile file, Long semesterId) {
        return handle(file, semesterId, false, "system");
    }

    @Transactional
    public ImportReport commit(MultipartFile file, Long semesterId, String actor) {
        return handle(file, semesterId, true, actor);
    }

    private ImportReport handle(MultipartFile file, Long semesterId, boolean commit, String actor) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请上传 Excel 文件");
        }
        Long resolved = foundation.resolveSemester(semesterId);
        ExcelTableReader.Table table;
        try {
            table = ExcelTableReader.read(file.getInputStream());
        } catch (IOException | RuntimeException ex) {
            throw ApiException.badRequest("无法解析 Excel 文件: " + ex.getMessage());
        }
        List<String> missing = REQUIRED.stream().filter(h -> !table.headers().contains(h)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("缺少必需的列: " + String.join("、", missing));
        }

        Map<String, ClassDto> classesByName = foundation.listClasses(resolved).stream()
                .collect(Collectors.toMap(ClassDto::name, Function.identity(), (a, b) -> a));
        Map<String, CourseDto> coursesByName = foundation.listCourses().stream()
                .collect(Collectors.toMap(CourseDto::name, Function.identity(), (a, b) -> a));
        Map<String, List<TeacherDto>> teachersByName = foundation.listTeachers().stream()
                .collect(Collectors.groupingBy(TeacherDto::name));

        List<ImportError> errors = new ArrayList<>();
        List<Map<String, String>> validRows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> extra = table.headers().stream().filter(h -> !HEADERS.contains(h)).toList();
        if (!extra.isEmpty()) {
            warnings.add("以下列不会被导入，已忽略: " + String.join("、", extra));
        }

        Map<String, Integer> seen = new HashMap<>();
        for (TableRow row : table.rows()) {
            List<ImportError> rowErrors = new ArrayList<>();
            String className = value(row, "班级名称");
            String courseName = value(row, "课程名称");
            String teacherName = value(row, "教师姓名");
            String weekly = value(row, "每周节数");

            ClassDto adminClass = classesByName.get(className);
            if (className.isEmpty()) {
                rowErrors.add(new ImportError(row.rowNumber(), "班级名称", "不能为空"));
            } else if (adminClass == null) {
                rowErrors.add(new ImportError(row.rowNumber(), "班级名称", "当前学期不存在此班级"));
            }
            CourseDto course = coursesByName.get(courseName);
            if (courseName.isEmpty()) {
                rowErrors.add(new ImportError(row.rowNumber(), "课程名称", "不能为空"));
            } else if (course == null) {
                rowErrors.add(new ImportError(row.rowNumber(), "课程名称", "课程不存在"));
            }
            List<TeacherDto> sameNameTeachers =
                    teachersByName.getOrDefault(teacherName, List.of());
            if (teacherName.isEmpty()) {
                rowErrors.add(new ImportError(row.rowNumber(), "教师姓名", "不能为空"));
            } else if (sameNameTeachers.isEmpty()) {
                rowErrors.add(new ImportError(row.rowNumber(), "教师姓名", "教师不存在"));
            } else if (sameNameTeachers.size() > 1) {
                rowErrors.add(new ImportError(row.rowNumber(), "教师姓名", "存在多名同名教师，请先区分"));
            }

            int weeklyLessons = 0;
            if (weekly.isEmpty()) {
                rowErrors.add(new ImportError(row.rowNumber(), "每周节数", "不能为空"));
            } else {
                try {
                    weeklyLessons = Integer.parseInt(weekly);
                    if (weeklyLessons < 1 || weeklyLessons > 20) {
                        rowErrors.add(new ImportError(row.rowNumber(), "每周节数", "必须在 1-20 之间"));
                    }
                } catch (NumberFormatException ex) {
                    rowErrors.add(new ImportError(row.rowNumber(), "每周节数", "必须是整数"));
                }
            }

            if (adminClass != null && course != null) {
                String key = adminClass.id() + "-" + course.id();
                if (seen.merge(key, 1, Integer::sum) > 1) {
                    rowErrors.add(new ImportError(row.rowNumber(), "班级名称", "文件内同一班级同一课程重复"));
                }
                if (adminClass.teachingClassId() != null
                        && assignmentRepository.findBySemesterIdAndTeachingClassIdAndCourseId(
                                resolved, adminClass.teachingClassId(), course.id()).isPresent()) {
                    rowErrors.add(new ImportError(row.rowNumber(), "课程名称", "该班级的这门课程已存在授课任务"));
                }
            }

            if (rowErrors.isEmpty()) {
                Map<String, String> normalized = new LinkedHashMap<>();
                normalized.put("班级名称", className);
                normalized.put("课程名称", courseName);
                normalized.put("教师姓名", teacherName);
                normalized.put("每周节数", String.valueOf(weeklyLessons));
                normalized.put("classId", String.valueOf(adminClass.id()));
                normalized.put("courseId", String.valueOf(course.id()));
                normalized.put("teacherId", String.valueOf(sameNameTeachers.get(0).id()));
                validRows.add(normalized);
            } else {
                errors.addAll(rowErrors);
            }
        }

        int imported = 0;
        if (commit) {
            for (Map<String, String> row : validRows) {
                assignments.create(new SaveAssignmentRequest(
                        Long.parseLong(row.get("classId")),
                        Long.parseLong(row.get("courseId")),
                        Long.parseLong(row.get("teacherId")),
                        Integer.parseInt(row.get("每周节数"))));
                imported++;
            }
            foundation.audit(
                    actor,
                    "IMPORT",
                    "assignments",
                    "文件: " + file.getOriginalFilename() + ", 成功 " + imported + " 行",
                    imported,
                    table.rows().size() - imported);
        }

        List<Map<String, String>> previewRows = commit ? List.of() : validRows.stream()
                .map(row -> {
                    Map<String, String> copy = new LinkedHashMap<>(row);
                    copy.keySet().removeIf(key -> key.endsWith("Id"));
                    return copy;
                })
                .toList();

        return new ImportReport(
                "assignments",
                "授课任务",
                table.rows().size(),
                validRows.size(),
                errors.size(),
                imported,
                errors,
                warnings,
                previewRows);
    }

    private String value(TableRow row, String header) {
        return row.values().getOrDefault(header, "").trim();
    }
}
