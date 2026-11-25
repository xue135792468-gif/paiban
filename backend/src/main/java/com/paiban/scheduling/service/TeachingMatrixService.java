package com.paiban.scheduling.service;

import com.paiban.common.event.SchoolDataWriteLock;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Grade;
import com.paiban.foundation.domain.GradeRepository;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.domain.TeachingClass;
import com.paiban.foundation.domain.TeachingClassRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.service.SemesterService;
import com.paiban.foundation.web.FoundationDtos.SaveClassRequest;
import com.paiban.foundation.web.FoundationDtos.SaveCourseRequest;
import com.paiban.foundation.web.FoundationDtos.SaveTeacherRequest;
import com.paiban.foundation.web.ImportDtos.ImportError;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.service.TeachingMatrixPlanner.Catalogue;
import com.paiban.scheduling.service.TeachingMatrixPlanner.Plan;
import com.paiban.scheduling.web.TeachingMatrixDtos.Action;
import com.paiban.scheduling.web.TeachingMatrixDtos.Options;
import com.paiban.scheduling.web.TeachingMatrixDtos.Report;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TeachingMatrixService {

    private static final String AUDIT_TYPE = "teaching-matrix";

    private final FoundationService foundation;
    private final SemesterService semesters;
    private final GradeRepository grades;
    private final AdminClassRepository classes;
    private final TeacherRepository teachers;
    private final TeachingClassRepository teachingClasses;
    private final CourseRepository courses;
    private final AssignmentRepository assignments;
    private final SchoolDataWriteLock schoolDataLock;
    private final TransactionTemplate transaction;

    public TeachingMatrixService(FoundationService foundation, SemesterService semesters, GradeRepository grades,
            AdminClassRepository classes, TeacherRepository teachers, TeachingClassRepository teachingClasses,
            CourseRepository courses,
            AssignmentRepository assignments, SchoolDataWriteLock schoolDataLock,
            PlatformTransactionManager transactionManager) {
        this.foundation = foundation;
        this.semesters = semesters;
        this.grades = grades;
        this.classes = classes;
        this.teachingClasses = teachingClasses;
        this.teachers = teachers;
        this.courses = courses;
        this.assignments = assignments;
        this.schoolDataLock = schoolDataLock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public Report preview(Long semesterId, Long gradeId, MultipartFile file, Options options) {
        return plan(semesterId, gradeId, read(file), options).report();
    }

    public Report commit(Long semesterId, Long gradeId, MultipartFile file, Options options, String actor) {
        var input = read(file);
        try {
            return transaction.execute(status -> {
                schoolDataLock.acquire();
                Plan plan = plan(semesterId, gradeId, input, options);
                if (plan.report().canCommit()) {
                    apply(semesterId, gradeId, plan);
                }
                audit(actor, semesterId, gradeId, plan.report());
                return plan.report();
            });
        } catch (ApiException | DataIntegrityViolationException ex) {
            String message = ex instanceof ApiException ? ex.getMessage() : "基础数据刚刚发生变更，请重新预演";
            Report rejected = TeachingMatrixPlanner.rejected(List.of(new ImportError(0, "提交", message))).report();
            transaction.executeWithoutResult(status -> audit(actor, semesterId, gradeId, rejected));
            return rejected;
        }
    }

    private TeachingMatrixWorkbook.Input read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return TeachingMatrixWorkbook.failure("请上传 Excel 文件");
        }
        try (var input = file.getInputStream()) {
            return TeachingMatrixWorkbook.read(input);
        } catch (IOException | RuntimeException ex) {
            return TeachingMatrixWorkbook.failure("无法解析 Excel 文件，请检查文件是否为有效工作簿");
        }
    }

    private Plan plan(Long semesterId, Long gradeId, TeachingMatrixWorkbook.Input input, Options options) {
        if (!input.errors().isEmpty()) {
            return TeachingMatrixPlanner.rejected(input.errors());
        }
        Grade grade = grades.findById(gradeId).orElse(null);
        if (grade == null || grade.getDeletedAt() != null || !grade.isActive()
                || !grade.getSemesterId().equals(semesterId)) {
            return TeachingMatrixPlanner.rejected(List.of(new ImportError(0, "年级", "请选择目标学期内的有效年级")));
        }
        Catalogue catalogue = new Catalogue(grade, classes.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId),
                teachers.findAllByOrderByNameAsc(), courses.findAllByOrderByNameAsc(),
                assignments.findBySemesterIdAndRecycleSlotIsNotNull(semesterId), semesters.listPeriods(semesterId).size());
        return new TeachingMatrixPlanner(catalogue, options).plan(input);
    }

    private void apply(Long semesterId, Long gradeId, Plan plan) {
        Map<String, Long> teacherIds = new HashMap<>();
        for (var teacher : plan.teachers()) {
            Long id = teacher.existing() == null
                    ? foundation.createTeacher(new SaveTeacherRequest(teacher.name(), null, null, null, null, true, null)).id()
                    : teacher.existing().getId();
            teacherIds.put(teacher.name(), id);
        }
        Map<String, Long> courseIds = new HashMap<>();
        for (var course : plan.courses()) {
            Course existing = course.existing();
            Long id;
            if (existing == null) {
                var request = new SaveCourseRequest(course.name(), null, false, null,
                        course.saveAsDefault() ? course.override() : null);
                id = foundation.createCourse(request).id();
            } else {
                if (course.saveAsDefault()) {
                    existing.setWeeklyHours(course.override());
                }
                id = existing.getId();
            }
            courseIds.put(course.name(), id);
        }

        Map<Long, Long> existingTeachingIds = teachingClasses.findBySemesterId(semesterId).stream()
                .collect(Collectors.toMap(TeachingClass::getAdminClassId, TeachingClass::getId));
        Map<String, ClassIds> classIds = new HashMap<>();
        for (var row : plan.rows()) {
            classIds.put(row.name(), applyClass(semesterId, gradeId, row, teacherIds, existingTeachingIds));
        }
        for (var row : plan.rows()) {
            for (var cell : row.cells()) {
                if (cell.action() != Action.UNCHANGED) {
                    applyAssignment(semesterId, classIds.get(row.name()), courseIds, teacherIds, cell);
                }
            }
        }
    }

    private ClassIds applyClass(Long semesterId, Long gradeId, TeachingMatrixPlanner.RowPlan row,
            Map<String, Long> teacherIds, Map<Long, Long> existingTeachingIds) {
        if (row.action() == Action.UNCHANGED) {
            Long classId = row.existing().getId();
            return new ClassIds(classId, existingTeachingIds.get(classId));
        }
        var teacher = row.headTeacher();
        Long headTeacherId = teacher == null ? null : (teacher.existing() != null
                ? teacher.existing().getId() : teacherIds.get(teacher.name()));
        var request = new SaveClassRequest(gradeId, row.name(), headTeacherId, row.students());
        var saved = row.existing() == null ? foundation.createClass(semesterId, request)
                : foundation.updateClass(row.existing().getId(), request);
        return new ClassIds(saved.id(), saved.teachingClassId());
    }

    private record ClassIds(Long adminClassId, Long teachingClassId) {}

    private void applyAssignment(Long semesterId, ClassIds classIds, Map<String, Long> courseIds,
            Map<String, Long> teacherIds, TeachingMatrixPlanner.CellPlan cell) {
        Assignment assignment = cell.existing();
        if (assignment == null) {
            if (classIds.teachingClassId() == null) {
                throw ApiException.badRequest("班级缺少对应的教学班");
            }
            assignment = new Assignment();
            assignment.setSemesterId(semesterId);
            assignment.setAdminClassId(classIds.adminClassId());
            assignment.setTeachingClassId(classIds.teachingClassId());
            assignment.setCourseId(courseIds.get(cell.course().name()));
            assignment.setCreatedAt(Instant.now());
        }
        assignment.setTeacherId(teacherIds.get(cell.teacher().name()));
        assignment.setWeeklyLessons(cell.hours());
        if (cell.existing() == null) {
            assignments.save(assignment);
        }
    }

    private void audit(String actor, Long semesterId, Long gradeId, Report report) {
        String context = "学期 " + semesterId + "，年级 " + gradeId;
        if (report.canCommit()) {
            foundation.audit(actor, "IMPORT", AUDIT_TYPE, context + "，任课表已导入",
                    report.rows().size(), 0);
        } else {
            int errors = report.errors().size() + (int) report.courses().stream().filter(course -> course.missingHours()).count();
            foundation.audit(actor, "IMPORT", AUDIT_TYPE, context + "，校验未通过，未导入任何业务数据",
                    0, errors);
        }
    }

    @Transactional(readOnly = true)
    public byte[] export(Long semesterId, Long gradeId, boolean template) {
        semesters.require(semesterId);
        boolean validGrade = foundation.listGrades(semesterId).stream()
                .anyMatch(grade -> grade.id().equals(gradeId) && grade.active());
        if (!validGrade) {
            throw ApiException.badRequest("请选择目标学期内的有效年级");
        }
        var classList = foundation.listClasses(semesterId).stream()
                .filter(adminClass -> adminClass.gradeId().equals(gradeId)).toList();
        Set<Long> selectedClasses = new HashSet<>();
        classList.forEach(adminClass -> selectedClasses.add(adminClass.id()));
        Map<Long, Map<Long, String>> cells = new HashMap<>();
        Set<Long> assignedCourses = new HashSet<>();
        var teachersById = foundation.teachersById();
        for (Assignment assignment : assignments.findBySemesterIdForList(semesterId)) {
            if (selectedClasses.contains(assignment.getAdminClassId())) {
                var teacher = teachersById.get(assignment.getTeacherId());
                if (teacher != null) {
                    cells.computeIfAbsent(assignment.getAdminClassId(), ignored -> new HashMap<>())
                            .put(assignment.getCourseId(), teacher.name());
                }
                assignedCourses.add(assignment.getCourseId());
            }
        }
        var courseList = foundation.listCourses().stream()
                .filter(course -> course.active() || assignedCourses.contains(course.id())).toList();
        List<String> headers = new ArrayList<>(TeachingMatrixWorkbook.CLASS_HEADERS);
        courseList.forEach(course -> headers.add(course.name()));
        List<List<String>> rows = new ArrayList<>();
        for (var adminClass : classList) {
            List<String> row = new ArrayList<>();
            row.add(adminClass.name());
            row.add(String.valueOf(adminClass.studentCount()));
            row.add(adminClass.headTeacherName());
            Map<Long, String> byCourse = cells.getOrDefault(adminClass.id(), Map.of());
            for (var course : courseList) {
                row.add(template ? null : byCourse.get(course.id()));
            }
            rows.add(row);
        }
        try {
            return TeachingMatrixWorkbook.write(headers, rows);
        } catch (IOException ex) {
            throw new IllegalStateException("生成任课表失败", ex);
        }
    }
}
