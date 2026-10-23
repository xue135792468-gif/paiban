package com.paiban.ai.imports;

import com.paiban.ai.imports.ImportDtos.CreateItem;
import com.paiban.ai.imports.ImportDtos.LookupDto;
import com.paiban.ai.imports.ImportDtos.MappingDto;
import com.paiban.ai.imports.ImportDtos.MappingItem;
import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Grade;
import com.paiban.foundation.domain.GradeRepository;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.foundation.web.SemesterDtos.SemesterDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * System entities an import can map a name onto, plus the lookup the mapping step
 * offers. Classes are scoped to a semester; courses, teachers and rooms are global.
 */
@Service
public class ImportMappingService {

    private static final int LOOKUP_LIMIT = 20;

    private final SemesterService semesters;
    private final AdminClassRepository adminClasses;
    private final GradeRepository grades;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;

    public ImportMappingService(
            SemesterService semesters,
            AdminClassRepository adminClasses,
            GradeRepository grades,
            CourseRepository courses,
            TeacherRepository teachers,
            RoomRepository rooms) {
        this.semesters = semesters;
        this.adminClasses = adminClasses;
        this.grades = grades;
        this.courses = courses;
        this.teachers = teachers;
        this.rooms = rooms;
    }

    /** Candidates of one type, filtered by name substring, sorted by name, capped at 20. */
    public List<LookupDto> lookup(String type, String query) {
        String q = query == null ? "" : query.trim();
        return switch (lookupType(type)) {
            case "class" -> lookupClasses(q);
            case "course" -> limit(courses.findAllByOrderByNameAsc(), Course::getName,
                    course -> new LookupDto(course.getId(), course.getName(), course.getSubject()), q);
            case "teacher" -> limit(teachers.findAllByOrderByNameAsc(), Teacher::getName,
                    teacher -> new LookupDto(teacher.getId(), teacher.getName(), teacher.getSubject()), q);
            case "room" -> limit(rooms.findAllByOrderByNameAsc(), Room::getName,
                    room -> new LookupDto(room.getId(), room.getName(), room.getBuilding()), q);
            default -> throw ApiException.badRequest("不支持的查询类型: " + type);
        };
    }

    private String lookupType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "class", "course", "teacher", "room" -> normalized;
            default -> throw ApiException.badRequest("不支持的查询类型: " + type);
        };
    }

    /** Classes always belong to the current semester; there is no other preview scope. */
    private List<LookupDto> lookupClasses(String query) {
        Long sid = semesters.current().map(SemesterDto::id).orElse(null);
        if (sid == null) {
            return List.of();
        }
        Map<Long, String> gradeNames = new LinkedHashMap<>();
        for (Grade grade : grades.findBySemesterIdOrderBySortOrderAscNameAsc(sid)) {
            gradeNames.put(grade.getId(), grade.getName());
        }
        return limit(adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(sid), AdminClass::getName,
                adminClass -> new LookupDto(
                        adminClass.getId(), adminClass.getName(), gradeNames.get(adminClass.getGradeId())),
                query);
    }

    private <T> List<LookupDto> limit(
            List<T> entities, Function<T, String> name, Function<T, LookupDto> toDto, String query) {
        String needle = normalizeName(query);
        return entities.stream()
                .filter(entity -> needle.isEmpty() || normalizeName(name.apply(entity)).contains(needle))
                .sorted(Comparator.comparing(name))
                .limit(LOOKUP_LIMIT)
                .map(toDto)
                .toList();
    }

    /**
     * Rewrites the source values in {@code rows} to the canonical names of the chosen
     * (or explicitly created) entities. Returns the mappings to record on the session
     * for traceability. Every mapping and creation is validated before the first write,
     * so a bad item cannot leave half-created entities behind. Creation is opt-in:
     * nothing is created unless it is listed.
     */
    @Transactional
    public List<MappingDto> apply(
            List<RowDto> rows, List<MappingItem> items, List<CreateItem> creates) {
        Long currentSemesterId = semesters.current().map(SemesterDto::id).orElse(null);
        List<CreatePlan> createPlans = new ArrayList<>();
        for (CreateItem create : creates) {
            createPlans.add(validateCreate(create, currentSemesterId));
        }
        List<MappingPlan> mappingPlans = new ArrayList<>();
        for (MappingItem item : items) {
            mappingPlans.add(validateMapping(item, currentSemesterId));
        }

        List<Rewrite> rewrites = new ArrayList<>();
        List<MappingDto> applied = new ArrayList<>();
        Map<String, Long> resolvedGrades = new LinkedHashMap<>();
        for (CreatePlan plan : createPlans) {
            String canonical = createEntity(plan, resolvedGrades);
            rewrites.add(new Rewrite(plan.field(), plan.source(), canonical));
            applied.add(new MappingDto(
                    plan.source(), canonical, "用户创建（" + labelOf(plan.field()) + "）"));
        }
        for (MappingPlan plan : mappingPlans) {
            rewrites.add(new Rewrite(plan.field(), plan.source(), plan.canonical()));
            applied.add(new MappingDto(
                    plan.source(), plan.canonical(), "用户映射（" + labelOf(plan.field()) + "）"));
        }
        rewrite(rows, rewrites);
        return applied;
    }

    private CreatePlan validateCreate(CreateItem item, Long currentSemesterId) {
        String field = item.field() == null ? "" : item.field().trim();
        MappingKind kind = kindOf(field);
        String source = required(item.source(), "原值不能为空");
        String name = item.name() == null || item.name().isBlank() ? source : item.name().trim();
        String gradeName = item.gradeName() == null ? "" : item.gradeName().trim();
        if (kind == MappingKind.CLASS) {
            if (currentSemesterId == null) {
                throw ApiException.badRequest("尚未设置当前学期，无法创建班级");
            }
            Long targetGradeId;
            if (item.gradeId() != null) {
                // Both were sent: the id wins over the name.
                Grade grade = grades.findById(item.gradeId())
                        .orElseThrow(() -> ApiException.badRequest("找不到年级: " + item.gradeId()));
                if (!currentSemesterId.equals(grade.getSemesterId())) {
                    throw ApiException.badRequest("年级不属于当前学期: " + grade.getName());
                }
                targetGradeId = grade.getId();
            } else if (gradeName.isEmpty()) {
                throw ApiException.badRequest("创建班级必须指定年级（gradeId 或 gradeName）");
            } else {
                // A grade that does not exist yet cannot own classes, so only an existing
                // semester grade can already have a class with this name.
                targetGradeId = existingGradeId(currentSemesterId, gradeName);
            }
            if (targetGradeId != null) {
                rejectNameOwnedByAnotherGrade(currentSemesterId, name, targetGradeId);
            }
        }
        return new CreatePlan(
                field, source, name, item.gradeId(), gradeName, item.studentCount(), kind, currentSemesterId);
    }

    private MappingPlan validateMapping(MappingItem item, Long currentSemesterId) {
        String field = item.field() == null ? "" : item.field().trim();
        MappingKind kind = kindOf(field);
        String source = required(item.source(), "原值不能为空");
        return new MappingPlan(field, source, canonicalName(kind, item.targetId(), currentSemesterId));
    }

    private String createEntity(CreatePlan plan, Map<String, Long> resolvedGrades) {
        return switch (plan.kind()) {
            case CLASS -> createClass(plan, resolvedGrades);
            case COURSE -> createCourse(plan.name());
            case TEACHER -> createTeacher(plan.name());
            case ROOM -> createRoom(plan.name());
        };
    }

    private String labelOf(String field) {
        return ImportTarget.TIMETABLE.labelOf(field);
    }

    private String createClass(CreatePlan plan, Map<String, Long> resolvedGrades) {
        Long semesterId = plan.currentSemesterId();
        Long gradeId = resolveGradeId(plan.gradeId(), plan.gradeName(), semesterId, resolvedGrades);
        // Backstop for conflicts the pre-write check cannot see: two creates in the same
        // batch may bring a name and a grade together only at write time.
        rejectNameOwnedByAnotherGrade(semesterId, plan.name(), gradeId);
        for (AdminClass existing : adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId)) {
            if (normalizeName(existing.getName()).equals(normalizeName(plan.name()))
                    && existing.getGradeId().equals(gradeId)) {
                return existing.getName();
            }
        }
        AdminClass adminClass = new AdminClass();
        adminClass.setSemesterId(semesterId);
        adminClass.setGradeId(gradeId);
        adminClass.setName(plan.name());
        adminClass.setStudentCount(plan.studentCount() == null ? 0 : plan.studentCount());
        return adminClasses.save(adminClass).getName();
    }

    /**
     * Names are unique per semester (uk_class_semester_name): the same name under another
     * grade is a user error, not a second class. Checked before the first write so a bad
     * batch cannot leave created grades behind.
     */
    private void rejectNameOwnedByAnotherGrade(Long semesterId, String name, Long gradeId) {
        for (AdminClass existing : adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId)) {
            if (normalizeName(existing.getName()).equals(normalizeName(name))
                    && !existing.getGradeId().equals(gradeId)) {
                throw ApiException.badRequest("班级名称已存在于其他年级: " + existing.getName());
            }
        }
    }

    /** The semester's existing grade whose normalized name matches, or null when missing. */
    private Long existingGradeId(Long semesterId, String gradeName) {
        String key = normalizeName(gradeName);
        for (Grade existing : grades.findBySemesterIdOrderBySortOrderAscNameAsc(semesterId)) {
            if (normalizeName(existing.getName()).equals(key)) {
                return existing.getId();
            }
        }
        return null;
    }

    /**
     * An explicit id wins. Otherwise the name is matched against the semester's grades
     * (normalized) and created when missing; {@code resolvedGrades} keeps one id per
     * name so a batch that repeats the same new grade creates it once.
     */
    private Long resolveGradeId(
            Long gradeId, String gradeName, Long semesterId, Map<String, Long> resolvedGrades) {
        if (gradeId != null) {
            return gradeId;
        }
        String key = normalizeName(gradeName);
        Long known = resolvedGrades.get(key);
        if (known != null) {
            return known;
        }
        Long existing = existingGradeId(semesterId, gradeName);
        if (existing != null) {
            resolvedGrades.put(key, existing);
            return existing;
        }
        Grade grade = new Grade();
        grade.setSemesterId(semesterId);
        grade.setName(gradeName);
        grade.setSortOrder(nextSortOrder(semesterId));
        Long created = grades.save(grade).getId();
        resolvedGrades.put(key, created);
        return created;
    }

    private int nextSortOrder(Long semesterId) {
        return grades.findBySemesterIdOrderBySortOrderAscNameAsc(semesterId).stream()
                .mapToInt(Grade::getSortOrder)
                .max()
                .orElse(0) + 1;
    }

    private String createCourse(String name) {
        for (Course existing : courses.findAllByOrderByNameAsc()) {
            if (normalizeName(existing.getName()).equals(normalizeName(name))) {
                return existing.getName();
            }
        }
        Course course = new Course();
        course.setName(name);
        return courses.save(course).getName();
    }

    private String createTeacher(String name) {
        for (Teacher existing : teachers.findAllByOrderByNameAsc()) {
            if (normalizeName(existing.getName()).equals(normalizeName(name))) {
                return existing.getName();
            }
        }
        Teacher teacher = new Teacher();
        teacher.setName(name);
        teacher.setActive(true);
        return teachers.save(teacher).getName();
    }

    private String createRoom(String name) {
        for (Room existing : rooms.findAllByOrderByNameAsc()) {
            if (normalizeName(existing.getName()).equals(normalizeName(name))) {
                return existing.getName();
            }
        }
        Room room = new Room();
        room.setName(name);
        return rooms.save(room).getName();
    }

    private MappingKind kindOf(String field) {
        return switch (field) {
            case "className" -> MappingKind.CLASS;
            case "courseName" -> MappingKind.COURSE;
            case "teacherName" -> MappingKind.TEACHER;
            case "roomName" -> MappingKind.ROOM;
            default -> throw ApiException.badRequest("不支持映射的字段: " + field);
        };
    }

    private String canonicalName(MappingKind kind, Long targetId, Long currentSemesterId) {
        if (targetId == null) {
            throw ApiException.badRequest("目标 ID 不能为空");
        }
        return switch (kind) {
            case CLASS -> {
                AdminClass adminClass = adminClasses.findById(targetId)
                        .orElseThrow(() -> ApiException.badRequest("找不到班级: " + targetId));
                if (currentSemesterId != null && !currentSemesterId.equals(adminClass.getSemesterId())) {
                    throw ApiException.badRequest("班级不属于当前学期: " + adminClass.getName());
                }
                yield adminClass.getName();
            }
            case COURSE -> courses.findById(targetId)
                    .orElseThrow(() -> ApiException.badRequest("找不到课程: " + targetId))
                    .getName();
            case TEACHER -> teachers.findById(targetId)
                    .orElseThrow(() -> ApiException.badRequest("找不到教师: " + targetId))
                    .getName();
            case ROOM -> rooms.findById(targetId)
                    .orElseThrow(() -> ApiException.badRequest("找不到教室: " + targetId))
                    .getName();
        };
    }

    private String required(String value, String message) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw ApiException.badRequest(message);
        }
        return trimmed;
    }

    private void rewrite(List<RowDto> rows, List<Rewrite> rewrites) {
        for (RowDto row : rows) {
            if (row == null || row.values() == null) {
                continue;
            }
            for (Rewrite rewrite : rewrites) {
                String current = row.values().get(rewrite.field());
                if (current != null && normalizeName(current).equals(normalizeName(rewrite.source()))) {
                    row.values().put(rewrite.field(), rewrite.target());
                }
            }
        }
    }

    private enum MappingKind {
        CLASS, COURSE, TEACHER, ROOM
    }

    private record Rewrite(String field, String source, String target) {}

    private record CreatePlan(
            String field, String source, String name, Long gradeId, String gradeName,
            Integer studentCount, MappingKind kind, Long currentSemesterId) {}

    private record MappingPlan(String field, String source, String canonical) {}

    static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }
}
