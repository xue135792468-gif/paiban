package com.paiban.foundation.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Grade;
import com.paiban.foundation.domain.GradeRepository;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.domain.TeachingClass;
import com.paiban.foundation.domain.TeachingClassRepository;
import com.paiban.foundation.web.FoundationDtos.AuditLogDto;
import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.foundation.web.FoundationDtos.CourseDto;
import com.paiban.foundation.web.FoundationDtos.GradeDto;
import com.paiban.foundation.web.FoundationDtos.RoomDto;
import com.paiban.foundation.web.FoundationDtos.SaveClassRequest;
import com.paiban.foundation.web.FoundationDtos.SaveCourseRequest;
import com.paiban.foundation.web.FoundationDtos.SaveGradeRequest;
import com.paiban.foundation.web.FoundationDtos.SaveRoomRequest;
import com.paiban.foundation.web.FoundationDtos.SaveTeacherRequest;
import com.paiban.foundation.web.FoundationDtos.TeacherDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FoundationService {

    private final TeacherRepository teachers;
    private final RoomRepository rooms;
    private final CourseRepository courses;
    private final GradeRepository grades;
    private final AdminClassRepository classes;
    private final TeachingClassRepository teachingClasses;
    private final AuditLogRepository auditLogs;
    private final SemesterService semesters;
    private final com.paiban.scheduling.domain.AssignmentRepository assignments;
    private final com.paiban.scheduling.domain.DraftLessonRepository draftLessons;

    public FoundationService(
            TeacherRepository teachers,
            RoomRepository rooms,
            CourseRepository courses,
            GradeRepository grades,
            AdminClassRepository classes,
            TeachingClassRepository teachingClasses,
            AuditLogRepository auditLogs,
            SemesterService semesters,
            com.paiban.scheduling.domain.AssignmentRepository assignments,
            com.paiban.scheduling.domain.DraftLessonRepository draftLessons) {
        this.teachers = teachers;
        this.rooms = rooms;
        this.courses = courses;
        this.grades = grades;
        this.classes = classes;
        this.teachingClasses = teachingClasses;
        this.auditLogs = auditLogs;
        this.semesters = semesters;
        this.assignments = assignments;
        this.draftLessons = draftLessons;
    }

    // ---------- teachers ----------

    @Transactional(readOnly = true)
    public List<TeacherDto> listTeachers() {
        return teachers.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
    }

    @Transactional
    public TeacherDto createTeacher(SaveTeacherRequest request) {
        Teacher teacher = new Teacher();
        apply(teacher, request);
        teacher.setCreatedAt(Instant.now());
        return toDto(teachers.save(teacher));
    }

    @Transactional
    public TeacherDto updateTeacher(Long id, SaveTeacherRequest request) {
        Teacher teacher = teachers.findById(id).orElseThrow(() -> ApiException.notFound("教师不存在: " + id));
        apply(teacher, request);
        return toDto(teacher);
    }

    @Transactional
    public void deleteTeacher(Long id) {
        Teacher teacher = teachers.findById(id).orElseThrow(() -> ApiException.notFound("教师不存在: " + id));
        if (assignments.countByTeacherId(id) > 0) {
            throw ApiException.conflict("该教师仍有授课任务，请先删除或调整授课任务");
        }
        classes.findAll().stream()
                .filter(adminClass -> id.equals(adminClass.getHeadTeacherId()))
                .forEach(adminClass -> adminClass.setHeadTeacherId(null));
        teachers.delete(teacher);
    }

    private void apply(Teacher teacher, SaveTeacherRequest request) {
        if (request.employeeNo() != null && !request.employeeNo().isBlank()) {
            teachers.findByEmployeeNo(request.employeeNo())
                    .filter(existing -> !existing.getId().equals(teacher.getId()))
                    .ifPresent(existing -> {
                        throw ApiException.conflict("工号已存在: " + request.employeeNo());
                    });
        }
        teacher.setName(request.name());
        teacher.setEmployeeNo(blankToNull(request.employeeNo()));
        teacher.setPhone(blankToNull(request.phone()));
        teacher.setEmail(blankToNull(request.email()));
        teacher.setSubject(blankToNull(request.subject()));
        // Binding ids are optional on update: an omitted value leaves the existing binding
        // intact (the teacher edit dialog does not always carry them), a blank clears it.
        if (request.dingtalkUserId() != null) {
            teacher.setDingtalkUserId(blankToNull(request.dingtalkUserId()));
        }
        if (request.feishuUserId() != null) {
            teacher.setFeishuUserId(blankToNull(request.feishuUserId()));
        }
        if (request.feishuOpenId() != null) {
            teacher.setFeishuOpenId(blankToNull(request.feishuOpenId()));
        }
        if (request.active() != null) {
            teacher.setActive(request.active());
        }
    }

    // ---------- rooms ----------

    @Transactional(readOnly = true)
    public List<RoomDto> listRooms() {
        return rooms.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
    }

    @Transactional
    public RoomDto createRoom(SaveRoomRequest request) {
        if (rooms.findByName(request.name()).isPresent()) {
            throw ApiException.conflict("教室名称已存在: " + request.name());
        }
        Room room = new Room();
        apply(room, request);
        room.setCreatedAt(Instant.now());
        return toDto(rooms.save(room));
    }

    @Transactional
    public RoomDto updateRoom(Long id, SaveRoomRequest request) {
        Room room = rooms.findById(id).orElseThrow(() -> ApiException.notFound("教室不存在: " + id));
        rooms.findByName(request.name())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw ApiException.conflict("教室名称已存在: " + request.name());
                });
        apply(room, request);
        return toDto(room);
    }

    @Transactional
    public void deleteRoom(Long id) {
        rooms.delete(rooms.findById(id).orElseThrow(() -> ApiException.notFound("教室不存在: " + id)));
    }

    private void apply(Room room, SaveRoomRequest request) {
        room.setName(request.name());
        if (request.capacity() != null) {
            room.setCapacity(request.capacity());
        }
        room.setType(request.type() == null || request.type().isBlank() ? "NORMAL" : request.type());
        room.setBuilding(blankToNull(request.building()));
    }

    // ---------- courses ----------

    @Transactional(readOnly = true)
    public List<CourseDto> listCourses() {
        return courses.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
    }

    @Transactional
    public CourseDto createCourse(SaveCourseRequest request) {
        if (courses.findByName(request.name()).isPresent()) {
            throw ApiException.conflict("课程名称已存在: " + request.name());
        }
        Course course = new Course();
        apply(course, request);
        course.setCreatedAt(Instant.now());
        return toDto(courses.save(course));
    }

    @Transactional
    public CourseDto updateCourse(Long id, SaveCourseRequest request) {
        Course course = courses.findById(id).orElseThrow(() -> ApiException.notFound("课程不存在: " + id));
        courses.findByName(request.name())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw ApiException.conflict("课程名称已存在: " + request.name());
                });
        apply(course, request);
        return toDto(course);
    }

    @Transactional
    public void deleteCourse(Long id) {
        courses.delete(courses.findById(id).orElseThrow(() -> ApiException.notFound("课程不存在: " + id)));
    }

    private void apply(Course course, SaveCourseRequest request) {
        course.setName(request.name());
        course.setSubject(blankToNull(request.subject()));
        if (request.allowConsecutive() != null) {
            course.setAllowConsecutive(request.allowConsecutive());
        }
        if (request.requireOutdoor() != null) {
            course.setRequireOutdoor(request.requireOutdoor());
        }
        course.setRequiredRoomType(blankToNull(request.requiredRoomType()));
        course.setColor(blankToNull(request.color()));
    }

    // ---------- grades ----------

    @Transactional(readOnly = true)
    public List<GradeDto> listGrades(Long semesterId) {
        Long resolved = resolveSemester(semesterId);
        return grades.findBySemesterIdOrderBySortOrderAscNameAsc(resolved).stream().map(this::toDto).toList();
    }

    @Transactional
    public GradeDto createGrade(Long semesterId, SaveGradeRequest request) {
        Long resolved = resolveSemester(semesterId);
        if (grades.existsBySemesterIdAndName(resolved, request.name())) {
            throw ApiException.conflict("年级已存在: " + request.name());
        }
        Grade grade = new Grade();
        grade.setSemesterId(resolved);
        grade.setName(request.name());
        grade.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        return toDto(grades.save(grade));
    }

    @Transactional
    public GradeDto updateGrade(Long id, SaveGradeRequest request) {
        Grade grade = grades.findById(id).orElseThrow(() -> ApiException.notFound("年级不存在: " + id));
        grades.findBySemesterIdAndName(grade.getSemesterId(), request.name())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw ApiException.conflict("年级已存在: " + request.name());
                });
        grade.setName(request.name());
        if (request.sortOrder() != null) {
            grade.setSortOrder(request.sortOrder());
        }
        return toDto(grade);
    }

    @Transactional
    public void deleteGrade(Long id) {
        Grade grade = grades.findById(id).orElseThrow(() -> ApiException.notFound("年级不存在: " + id));
        List<AdminClass> affected = classes.findBySemesterIdOrderByGradeIdAscNameAsc(grade.getSemesterId()).stream()
                .filter(adminClass -> grade.getId().equals(adminClass.getGradeId()))
                .toList();
        affected.forEach(adminClass -> teachingClasses.deleteByAdminClassId(adminClass.getId()));
        classes.deleteAll(affected);
        grades.delete(grade);
    }

    // ---------- classes ----------

    @Transactional(readOnly = true)
    public List<ClassDto> listClasses(Long semesterId) {
        Long resolved = resolveSemester(semesterId);
        Map<Long, String> gradeNames = grades.findBySemesterIdOrderBySortOrderAscNameAsc(resolved).stream()
                .collect(Collectors.toMap(Grade::getId, Grade::getName));
        Map<Long, String> teacherNames = teachers.findAll().stream()
                .collect(Collectors.toMap(Teacher::getId, Teacher::getName));
        Map<Long, Long> teachingIds = teachingClasses.findBySemesterId(resolved).stream()
                .collect(Collectors.toMap(TeachingClass::getAdminClassId, TeachingClass::getId));
        return classes.findBySemesterIdOrderByGradeIdAscNameAsc(resolved).stream()
                .map(adminClass -> toDto(
                        adminClass,
                        gradeNames.get(adminClass.getGradeId()),
                        adminClass.getHeadTeacherId() == null ? null : teacherNames.get(adminClass.getHeadTeacherId()),
                        teachingIds.get(adminClass.getId())))
                .toList();
    }

    @Transactional
    public ClassDto createClass(Long semesterId, SaveClassRequest request) {
        Long resolved = resolveSemester(semesterId);
        Grade grade = grades.findById(request.gradeId())
                .orElseThrow(() -> ApiException.notFound("年级不存在: " + request.gradeId()));
        if (!grade.getSemesterId().equals(resolved)) {
            throw ApiException.badRequest("年级不属于当前学期");
        }
        if (classes.existsBySemesterIdAndName(resolved, request.name())) {
            throw ApiException.conflict("班级已存在: " + request.name());
        }
        validateHeadTeacher(request.headTeacherId());
        AdminClass adminClass = new AdminClass();
        adminClass.setSemesterId(resolved);
        adminClass.setGradeId(request.gradeId());
        adminClass.setName(request.name());
        adminClass.setHeadTeacherId(request.headTeacherId());
        if (request.studentCount() != null) {
            adminClass.setStudentCount(request.studentCount());
        }
        AdminClass saved = classes.save(adminClass);
        TeachingClass teachingClass = new TeachingClass();
        teachingClass.setSemesterId(resolved);
        teachingClass.setAdminClassId(saved.getId());
        teachingClass.setName(saved.getName());
        TeachingClass savedTeaching = teachingClasses.save(teachingClass);
        return toDto(saved, grade.getName(), headTeacherName(saved.getHeadTeacherId()), savedTeaching.getId());
    }

    @Transactional
    public ClassDto updateClass(Long id, SaveClassRequest request) {
        AdminClass adminClass = classes.findById(id).orElseThrow(() -> ApiException.notFound("班级不存在: " + id));
        Grade grade = grades.findById(request.gradeId())
                .orElseThrow(() -> ApiException.notFound("年级不存在: " + request.gradeId()));
        if (!grade.getSemesterId().equals(adminClass.getSemesterId())) {
            throw ApiException.badRequest("年级不属于该班级所在学期");
        }
        classes.findBySemesterIdAndName(adminClass.getSemesterId(), request.name())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw ApiException.conflict("班级已存在: " + request.name());
                });
        validateHeadTeacher(request.headTeacherId());
        adminClass.setGradeId(request.gradeId());
        adminClass.setName(request.name());
        adminClass.setHeadTeacherId(request.headTeacherId());
        if (request.studentCount() != null) {
            adminClass.setStudentCount(request.studentCount());
        }
        teachingClasses.findByAdminClassId(id).ifPresent(teachingClass -> teachingClass.setName(request.name()));
        Long teachingId = teachingClasses.findByAdminClassId(id).map(TeachingClass::getId).orElse(null);
        return toDto(adminClass, grade.getName(), headTeacherName(adminClass.getHeadTeacherId()), teachingId);
    }

    @Transactional
    public void deleteClass(Long id) {
        AdminClass adminClass = classes.findById(id).orElseThrow(() -> ApiException.notFound("班级不存在: " + id));
        if (draftLessons.countByAdminClassId(id) > 0) {
            throw ApiException.conflict("该班级存在课表草稿数据，请先删除相关草稿");
        }
        teachingClasses.deleteByAdminClassId(id);
        classes.delete(adminClass);
    }

    @Transactional(readOnly = true)
    public List<AuditLogDto> auditLogs() {
        return auditLogs.findTop100ByOrderByCreatedAtDesc().stream()
                .map(log -> new AuditLogDto(
                        log.getId(), log.getActor(), log.getAction(), log.getEntityType(),
                        log.getDetail(), log.getSuccessRows(), log.getFailedRows(), log.getCreatedAt()))
                .toList();
    }

    public void audit(String actor, String action, String entityType, String detail, int success, int failed) {
        auditLogs.save(new AuditLog(actor, action, entityType, detail, success, failed));
    }

    // ---------- helpers ----------

    public Long resolveSemester(Long semesterId) {
        return semesterId != null ? semesterId : semesters.requireCurrent().getId();
    }

    public Teacher requireTeacher(Long id) {
        return teachers.findById(id).orElseThrow(() -> ApiException.notFound("教师不存在: " + id));
    }

    @Transactional(readOnly = true)
    public AdminClass requireAdminClass(Long id) {
        return classes.findById(id).orElseThrow(() -> ApiException.notFound("班级不存在: " + id));
    }

    @Transactional(readOnly = true)
    public Course requireCourse(Long id) {
        return courses.findById(id).orElseThrow(() -> ApiException.notFound("课程不存在: " + id));
    }

    @Transactional(readOnly = true)
    public Grade requireGradeById(Long id) {
        return grades.findById(id).orElseThrow(() -> ApiException.notFound("年级不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<AdminClass> listClassEntities(Long semesterId) {
        return classes.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId);
    }

    @Transactional(readOnly = true)
    public Map<Long, ClassDto> classesById(Long semesterId) {
        return listClasses(semesterId).stream()
                .collect(Collectors.toMap(ClassDto::id, Function.identity()));
    }

    @Transactional(readOnly = true)
    public Map<Long, CourseDto> coursesById() {
        return listCourses().stream().collect(Collectors.toMap(CourseDto::id, Function.identity()));
    }

    @Transactional(readOnly = true)
    public Map<Long, TeacherDto> teachersById() {
        return listTeachers().stream().collect(Collectors.toMap(TeacherDto::id, Function.identity()));
    }

    @Transactional(readOnly = true)
    public Map<Long, GradeDto> gradesById(Long semesterId) {
        return listGrades(semesterId).stream().collect(Collectors.toMap(GradeDto::id, Function.identity()));
    }

    public Grade requireGrade(Long semesterId, String name) {
        return grades.findBySemesterIdAndName(semesterId, name)
                .orElseThrow(() -> ApiException.badRequest("年级不存在: " + name));
    }

    private void validateHeadTeacher(Long headTeacherId) {
        if (headTeacherId != null && !teachers.existsById(headTeacherId)) {
            throw ApiException.notFound("班主任不存在: " + headTeacherId);
        }
    }

    private String headTeacherName(Long id) {
        return id == null ? null : teachers.findById(id).map(Teacher::getName).orElse(null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private TeacherDto toDto(Teacher teacher) {
        return new TeacherDto(
                teacher.getId(), teacher.getName(), teacher.getEmployeeNo(), teacher.getPhone(),
                teacher.getEmail(), teacher.getSubject(), teacher.isActive(),
                teacher.getDingtalkUserId(), teacher.getFeishuUserId(), teacher.getFeishuOpenId(),
                bindingStatus(teacher));
    }

    private static String bindingStatus(Teacher teacher) {
        boolean dingtalk = teacher.getDingtalkUserId() != null;
        boolean feishu = teacher.getFeishuUserId() != null || teacher.getFeishuOpenId() != null;
        if (dingtalk && feishu) {
            return "both";
        }
        return dingtalk ? "dingtalk" : feishu ? "feishu" : "none";
    }

    private RoomDto toDto(Room room) {
        return new RoomDto(room.getId(), room.getName(), room.getCapacity(), room.getType(), room.getBuilding());
    }

    private CourseDto toDto(Course course) {
        return new CourseDto(
                course.getId(), course.getName(), course.getSubject(), course.isAllowConsecutive(),
                course.isRequireOutdoor(), course.getRequiredRoomType(), course.getColor());
    }

    private GradeDto toDto(Grade grade) {
        return new GradeDto(grade.getId(), grade.getName(), grade.getSortOrder());
    }

    private ClassDto toDto(AdminClass adminClass, String gradeName, String headTeacherName, Long teachingClassId) {
        return new ClassDto(
                adminClass.getId(), adminClass.getName(), adminClass.getGradeId(), gradeName,
                adminClass.getHeadTeacherId(), headTeacherName, adminClass.getStudentCount(), teachingClassId);
    }

    public Map<Long, String> teacherNames() {
        return teachers.findAll().stream().collect(Collectors.toMap(Teacher::getId, Teacher::getName));
    }

    public Function<Long, String> teacherNameResolver() {
        Map<Long, String> names = teacherNames();
        return id -> id == null ? null : names.get(id);
    }
}
