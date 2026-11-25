package com.paiban.scheduling.service;

import com.paiban.common.event.SchoolDataWriteLock;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.TeachingClass;
import com.paiban.foundation.domain.TeachingClassRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.foundation.web.FoundationDtos.CourseDto;
import com.paiban.foundation.web.FoundationDtos.TeacherDto;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.web.AssignmentDtos.AssignmentDto;
import com.paiban.scheduling.web.AssignmentDtos.SaveAssignmentRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentService {

    private final AssignmentRepository assignments;
    private final TeachingClassRepository teachingClasses;
    private final FoundationService foundation;
    private final UserService users;
    private final SchoolDataWriteLock schoolDataLock;

    static final String DELETED_ASSIGNMENT_MESSAGE =
            "该班级的这门课程已有授课任务在回收站，请到「设置 → 最近删除」恢复，或等待清理后再建。";

    public AssignmentService(
            AssignmentRepository assignments,
            TeachingClassRepository teachingClasses,
            FoundationService foundation,
            UserService users, SchoolDataWriteLock schoolDataLock) {
        this.schoolDataLock = schoolDataLock;
        this.assignments = assignments;
        this.teachingClasses = teachingClasses;
        this.foundation = foundation;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<AssignmentDto> list(Long semesterId, Long classId, Long teacherId, Long courseId) {
        Long resolved = foundation.resolveSemester(semesterId);
        List<Assignment> result = assignments.findBySemesterIdForList(resolved);
        if (classId != null) {
            result = result.stream().filter(a -> a.getAdminClassId().equals(classId)).toList();
        }
        if (teacherId != null) {
            result = result.stream().filter(a -> a.getTeacherId().equals(teacherId)).toList();
        }
        if (courseId != null) {
            result = result.stream().filter(a -> a.getCourseId().equals(courseId)).toList();
        }
        return toDtos(result, resolved);
    }

    @Transactional(readOnly = true)
    public List<AssignmentDto> listScoped(
            String username,
            boolean canManageAll,
            Long semesterId,
            Long classId,
            Long teacherId,
            Long courseId) {
        Long effectiveTeacherId = teacherId;
        if (!canManageAll) {
            AppUser user = users.getByUsername(username);
            if (user.getTeacherId() == null) {
                return List.of();
            }
            effectiveTeacherId = user.getTeacherId();
        }
        return list(semesterId, classId, effectiveTeacherId, courseId);
    }

    @Transactional(readOnly = true)
    public List<AssignmentDto> mine(String username) {
        return mine(username, null);
    }

    @Transactional(readOnly = true)
    public List<AssignmentDto> mine(String username, Long semesterId) {
        AppUser user = users.getByUsername(username);
        if (user.getTeacherId() == null) {
            return List.of();
        }
        Long resolved = foundation.resolveSemester(semesterId);
        return toDtos(assignments.findBySemesterIdAndTeacherId(resolved, user.getTeacherId()), resolved);
    }

    @Transactional
    public AssignmentDto create(SaveAssignmentRequest request) {
        return create(foundation.resolveSemester(null), request);
    }

    @Transactional
    public AssignmentDto create(Long semesterId, SaveAssignmentRequest request) {
        schoolDataLock.acquire();
        AdminClass adminClass = foundation.requireAdminClass(request.classId());
        if (!adminClass.getSemesterId().equals(semesterId)) {
            throw ApiException.badRequest("班级不属于当前学期");
        }
        CourseDto course = foundation.coursesById().get(request.courseId());
        if (course == null) {
            throw ApiException.notFound("课程不存在: " + request.courseId());
        }
        TeacherDto teacher = foundation.teachersById().get(request.teacherId());
        if (teacher == null) {
            throw ApiException.notFound("教师不存在: " + request.teacherId());
        }
        TeachingClass teachingClass = teachingClasses.findByAdminClassId(adminClass.getId())
                .orElseThrow(() -> ApiException.badRequest("班级缺少对应的教学班"));
        assignments.findBySemesterIdAndTeachingClassIdAndCourseId(
                        semesterId, teachingClass.getId(), request.courseId())
                .ifPresent(this::rejectDuplicate);

        Integer weeklyLessons = request.weeklyLessons() != null ? request.weeklyLessons() : course.weeklyHours();
        if (weeklyLessons == null) {
            throw ApiException.badRequest("请填写每周节数，或先设置该课程的默认周课时数");
        }
        Assignment assignment = new Assignment();
        assignment.setSemesterId(semesterId);
        assignment.setAdminClassId(adminClass.getId());
        assignment.setTeachingClassId(teachingClass.getId());
        assignment.setCourseId(request.courseId());
        assignment.setTeacherId(request.teacherId());
        assignment.setWeeklyLessons(weeklyLessons);
        assignment.setCreatedAt(Instant.now());
        return toDtos(List.of(assignments.save(assignment)), semesterId).get(0);
    }

    @Transactional
    public AssignmentDto update(Long id, SaveAssignmentRequest request) {
        schoolDataLock.acquire();
        Assignment assignment = assignments.findById(id)
                .orElseThrow(() -> ApiException.notFound("授课任务不存在: " + id));
        if (assignment.getDeletedAt() != null) {
            throw ApiException.conflict(DELETED_ASSIGNMENT_MESSAGE);
        }
        if (request.weeklyLessons() == null) {
            throw ApiException.badRequest("每周节数不能为空");
        }
        AdminClass adminClass = foundation.requireAdminClass(request.classId());
        if (!adminClass.getSemesterId().equals(assignment.getSemesterId())) {
            throw ApiException.badRequest("班级不属于该授课任务所在学期");
        }
        if (foundation.teachersById().get(request.teacherId()) == null) {
            throw ApiException.notFound("教师不存在: " + request.teacherId());
        }
        if (!foundation.coursesById().containsKey(request.courseId())) {
            throw ApiException.notFound("课程不存在: " + request.courseId());
        }
        TeachingClass teachingClass = teachingClasses.findByAdminClassId(adminClass.getId())
                .orElseThrow(() -> ApiException.badRequest("班级缺少对应的教学班"));
        assignments.findBySemesterIdAndTeachingClassIdAndCourseId(
                        assignment.getSemesterId(), teachingClass.getId(), request.courseId())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(this::rejectDuplicate);
        assignment.setAdminClassId(adminClass.getId());
        assignment.setTeachingClassId(teachingClass.getId());
        assignment.setCourseId(request.courseId());
        assignment.setTeacherId(request.teacherId());
        assignment.setWeeklyLessons(request.weeklyLessons());
        return toDtos(List.of(assignment), assignment.getSemesterId()).get(0);
    }

    private void rejectDuplicate(Assignment existing) {
        throw ApiException.conflict(existing.getDeletedAt() != null
                ? DELETED_ASSIGNMENT_MESSAGE : "该班级的这门课程已存在授课任务");
    }

    public List<AssignmentDto> toDtos(List<Assignment> list, Long semesterId) {
        Map<Long, ClassDto> classMap = foundation.classesById(semesterId);
        Map<Long, CourseDto> courseMap = foundation.coursesById();
        Map<Long, TeacherDto> teacherMap = foundation.teachersById();
        return list.stream()
                .map(assignment -> {
                    ClassDto adminClass = classMap.get(assignment.getAdminClassId());
                    CourseDto course = courseMap.get(assignment.getCourseId());
                    TeacherDto teacher = teacherMap.get(assignment.getTeacherId());
                    return new AssignmentDto(
                            assignment.getId(),
                            assignment.getAdminClassId(),
                            adminClass == null ? null : adminClass.name(),
                            adminClass == null ? null : adminClass.gradeId(),
                            adminClass == null ? null : adminClass.gradeName(),
                            assignment.getCourseId(),
                            course == null ? null : course.name(),
                            assignment.getTeacherId(),
                            teacher == null ? null : teacher.name(),
                            assignment.getWeeklyLessons(),
                            assignment.getTeachingClassId(),
                            assignment.isActive());
                })
                .toList();
    }
}
