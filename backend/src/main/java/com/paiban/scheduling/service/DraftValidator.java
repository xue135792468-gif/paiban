package com.paiban.scheduling.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.web.SchedulingDtos.LessonOverride;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Millisecond conflict checking for manual adjustments. Returns human readable
 * reasons ("张老师此时在 3 班有数学课") instead of a bare score.
 */
@Service
public class DraftValidator {

    public record Conflict(String type, String message, Long lessonId) {}

    public record ValidationResult(boolean valid, int hardConflicts, List<Conflict> conflicts) {}

    private static final String[] DAY_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final PeriodRepository periods;
    private final RoomRepository rooms;
    private final CourseRepository courses;
    private final TeacherRepository teachers;
    private final AdminClassRepository adminClasses;
    private final ConstraintService constraints;
    private final ObjectMapper objectMapper;

    public DraftValidator(
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons,
            PeriodRepository periods,
            RoomRepository rooms,
            CourseRepository courses,
            TeacherRepository teachers,
            AdminClassRepository adminClasses,
            ConstraintService constraints,
            ObjectMapper objectMapper) {
        this.drafts = drafts;
        this.draftLessons = draftLessons;
        this.periods = periods;
        this.rooms = rooms;
        this.courses = courses;
        this.teachers = teachers;
        this.adminClasses = adminClasses;
        this.constraints = constraints;
        this.objectMapper = objectMapper;
    }

    /**
     * Single-lesson special case of {@link #validateMoves}. Period and room are
     * assigned unconditionally, including nulls, so the existing endpoint keeps its
     * exact behaviour.
     */
    @Transactional(readOnly = true)
    public ValidationResult validateMove(Long draftId, Long lessonId, Long periodId, Long roomId) {
        Snapshot snapshot = snapshot(draftId);
        L moved = snapshot.lessons.stream()
                .filter(lesson -> lesson.id.equals(lessonId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + lessonId));
        moved.periodId = periodId;
        moved.roomId = roomId;
        return resultOf(checkAffected(snapshot, Set.of(moved.id)));
    }

    @Transactional(readOnly = true)
    public ValidationResult validateMoves(Long draftId, List<LessonOverride> overrides) {
        Snapshot snapshot = snapshot(draftId);
        Set<Long> affected = applyOverrides(snapshot, overrides == null ? List.of() : overrides);
        return resultOf(checkAffected(snapshot, affected));
    }

    /**
     * A change that assigns every mutable attribute unconditionally, nulls included.
     * Unlike {@link LessonOverride}, an explicit null clears the field, which is what
     * an exact swap (including a room being handed over as null) needs.
     */
    public record LessonChange(Long lessonId, Long periodId, Long roomId, Long teacherId) {}

    @Transactional(readOnly = true)
    public ValidationResult validateChanges(Long draftId, List<LessonChange> changes) {
        Snapshot snapshot = snapshot(draftId);
        Map<Long, L> byId = new HashMap<>();
        snapshot.lessons.forEach(lesson -> byId.put(lesson.id, lesson));
        Set<Long> affected = new LinkedHashSet<>();
        for (LessonChange change : changes == null ? List.<LessonChange>of() : changes) {
            L lesson = byId.get(change.lessonId());
            if (lesson == null) {
                throw ApiException.notFound("课节不存在: " + change.lessonId());
            }
            lesson.periodId = change.periodId();
            lesson.roomId = change.roomId();
            lesson.teacherId = change.teacherId();
            affected.add(lesson.id);
        }
        return resultOf(checkAffected(snapshot, affected));
    }

    @Transactional(readOnly = true)
    public ValidationResult validateDraft(Long draftId) {
        return resultOf(checkAffected(snapshot(draftId), null));
    }

    /**
     * Evaluates independent override scenarios against one snapshot. Each scenario
     * starts from a fresh copy of the lessons, so it never sees another scenario's
     * changes. The snapshot itself (periods, rooms, courses, teachers, classes and
     * constraint settings) is built once; this is what the drag preview and the swap
     * candidate list need, where one request asks "what if" for many targets.
     */
    @Transactional(readOnly = true)
    public List<ValidationResult> validateScenarios(Long draftId, List<List<LessonOverride>> scenarios) {
        Snapshot base = snapshot(draftId);
        List<ValidationResult> results = new ArrayList<>(scenarios.size());
        for (List<LessonOverride> scenario : scenarios) {
            Snapshot copy = base.withFreshLessons();
            results.add(resultOf(checkScenariosAffected(copy, applyOverrides(copy, scenario))));
        }
        return results;
    }

    /**
     * The same rules as {@link #checkAffected}, but the pair scan only visits pairs
     * that involve an affected lesson. {@code checkAffected} scans every pair because
     * it also serves whole-draft validation; scenario evaluation repeats that scan for
     * every target, so the quadratic part must not be paid per scenario.
     */
    private List<Conflict> checkScenariosAffected(Snapshot s, Set<Long> affectedLessonIds) {
        List<Conflict> out = new ArrayList<>();
        List<L> lessons = s.lessons;
        for (L lesson : lessons) {
            if (affectedLessonIds.contains(lesson.id)) {
                checkLesson(lesson, s, out);
            }
        }
        for (int i = 0; i < lessons.size(); i++) {
            L a = lessons.get(i);
            if (!affectedLessonIds.contains(a.id)) {
                continue;
            }
            for (int j = i + 1; j < lessons.size(); j++) {
                checkPair(a, lessons.get(j), s, out);
            }
            for (int j = 0; j < i; j++) {
                L b = lessons.get(j);
                if (!affectedLessonIds.contains(b.id)) {
                    checkPair(a, b, s, out);
                }
            }
        }
        if (groupRulesEnabled(s.settings)) {
            checkGroups(s, out, affectedLessonIds);
        }
        return out;
    }

    /**
     * Group rules only report when they are enabled and hard, so a draft without any
     * of them needs no per-day counts. This keeps scenario evaluation cheap for the
     * default configuration, which only has the pairwise rules.
     */
    private boolean groupRulesEnabled(ConstraintSettings settings) {
        return List.of(
                        ConstraintType.TEACHER_MAX_PER_DAY,
                        ConstraintType.CLASS_MAX_PER_DAY,
                        ConstraintType.COURSE_MAX_PER_DAY,
                        ConstraintType.EVENING_MAX,
                        ConstraintType.SPORT_NOT_FIRST_AFTERNOON)
                .stream()
                .anyMatch(type -> settings.enabled(type) && settings.hard(type));
    }

    /** Applies every override to the in-memory snapshot and returns the affected lesson ids. */
    private Set<Long> applyOverrides(Snapshot snapshot, List<LessonOverride> overrides) {
        Map<Long, L> byId = new HashMap<>();
        snapshot.lessons.forEach(lesson -> byId.put(lesson.id, lesson));
        Set<Long> affected = new LinkedHashSet<>();
        for (LessonOverride override : overrides) {
            L lesson = byId.get(override.lessonId());
            if (lesson == null) {
                throw ApiException.notFound("课节不存在: " + override.lessonId());
            }
            if (override.periodId() != null) {
                lesson.periodId = override.periodId();
            }
            if (override.roomId() != null) {
                lesson.roomId = override.roomId();
            }
            if (override.teacherId() != null) {
                lesson.teacherId = override.teacherId();
            }
            affected.add(lesson.id);
        }
        return affected;
    }

    /**
     * Shared rule engine. A null set means the whole draft; an empty set means no
     * lesson changed, so nothing can conflict. Only pairs touching an affected lesson
     * are checked, and a pair is checked once with the affected lesson as the subject.
     */
    private List<Conflict> checkAffected(Snapshot s, Set<Long> affectedLessonIds) {
        boolean wholeDraft = affectedLessonIds == null;
        List<Conflict> out = new ArrayList<>();
        for (L lesson : s.lessons) {
            if (wholeDraft || affectedLessonIds.contains(lesson.id)) {
                checkLesson(lesson, s, out);
            }
        }
        for (int i = 0; i < s.lessons.size(); i++) {
            for (int j = i + 1; j < s.lessons.size(); j++) {
                L a = s.lessons.get(i);
                L b = s.lessons.get(j);
                boolean aAffected = wholeDraft || affectedLessonIds.contains(a.id);
                boolean bAffected = wholeDraft || affectedLessonIds.contains(b.id);
                if (!aAffected && !bAffected) {
                    continue;
                }
                if (aAffected) {
                    checkPair(a, b, s, out);
                } else {
                    checkPair(b, a, s, out);
                }
            }
        }
        checkGroups(s, out, wholeDraft ? null : affectedLessonIds);
        return out;
    }

    private ValidationResult resultOf(List<Conflict> conflicts) {
        return new ValidationResult(conflicts.isEmpty(), conflicts.size(), conflicts);
    }

    private void checkLesson(L lesson, Snapshot s, List<Conflict> out) {
        if (lesson.periodId == null) {
            return;
        }
        Period period = s.periods.get(lesson.periodId);
        if (period == null) {
            out.add(new Conflict("PERIOD", "所选课节不存在", lesson.id));
            return;
        }
        Course course = s.courses.get(lesson.courseId);
        Room room = lesson.roomId == null ? null : s.rooms.get(lesson.roomId);
        String courseName = course == null ? "课程" : course.getName();

        if (s.settings.enabled(ConstraintType.ROOM_CAPACITY_FIT)
                && room != null && room.getCapacity() < lesson.studentCount) {
            out.add(new Conflict("ROOM_CAPACITY",
                    "教室「" + room.getName() + "」容量 " + room.getCapacity()
                            + " 小于班级人数 " + lesson.studentCount,
                    lesson.id));
        }

        String requiredRoomType = course == null ? null : course.getRequiredRoomType();
        ConstraintType roomTypeConstraint = ConstraintType.COURSE_REQUIRED_ROOM_TYPE;
        if (s.settings.enabled(roomTypeConstraint)) {
            Long configuredCourse = s.settings.longParam(roomTypeConstraint, "courseId");
            if (Objects.equals(configuredCourse, lesson.courseId)) {
                requiredRoomType = s.settings.textParam(roomTypeConstraint, "roomType");
            }
        }
        if (requiredRoomType != null && !requiredRoomType.isBlank()
                && (room == null || !requiredRoomType.equals(room.getType()))) {
            out.add(new Conflict("ROOM_TYPE",
                    courseName + " 需要安排在「" + roomTypeLabel(requiredRoomType) + "」类型的教室",
                    lesson.id));
        }

        ConstraintType unavailable = ConstraintType.TEACHER_UNAVAILABLE;
        if (s.settings.enabled(unavailable)) {
            Long teacherId = s.settings.longParam(unavailable, "teacherId");
            Integer day = s.settings.intParam(unavailable, "dayOfWeek");
            Integer periodNo = s.settings.intParam(unavailable, "periodNo");
            if (Objects.equals(teacherId, lesson.teacherId)
                    && Objects.equals(day, period.getDayOfWeek())
                    && Objects.equals(periodNo, period.getPeriodNo())) {
                out.add(new Conflict("TEACHER_UNAVAILABLE",
                        teacherName(s, lesson.teacherId) + " 在 " + periodLabel(period) + " 不排课",
                        lesson.id));
            }
        }

        ConstraintType meeting = ConstraintType.TEACHER_MEETING_BLOCKED;
        if (s.settings.enabled(meeting)) {
            String subject = s.settings.textParam(meeting, "subject");
            Integer day = s.settings.intParam(meeting, "dayOfWeek");
            Integer periodNo = s.settings.intParam(meeting, "periodNo");
            Teacher teacher = s.teachers.get(lesson.teacherId);
            boolean subjectMatches = subject == null || subject.isBlank()
                    || (teacher != null && subject.equalsIgnoreCase(teacher.getSubject()));
            if (subjectMatches
                    && Objects.equals(day, period.getDayOfWeek())
                    && Objects.equals(periodNo, period.getPeriodNo())) {
                out.add(new Conflict("TEACHER_MEETING",
                        teacherName(s, lesson.teacherId) + " 在 " + periodLabel(period) + " 参加教研活动",
                        lesson.id));
            }
        }

        ConstraintType notEvening = ConstraintType.COURSE_NOT_IN_EVENING;
        if (s.settings.enabled(notEvening)
                && s.settings.matchesCourse(notEvening, lesson.courseId)
                && period.getSection() == PeriodSection.EVENING) {
            out.add(new Conflict("COURSE_NOT_IN_EVENING",
                    courseName + " 不能安排在晚自习", lesson.id));
        }
    }

    private void checkPair(L a, L b, Snapshot s, List<Conflict> out) {
        if (a.periodId == null || b.periodId == null) {
            return;
        }
        Period periodA = s.periods.get(a.periodId);
        Period periodB = s.periods.get(b.periodId);
        if (periodA == null || periodB == null) {
            return;
        }
        if (a.periodId.equals(b.periodId)) {
        if (s.settings.enabled(ConstraintType.TEACHER_CONFLICT)
                && Objects.equals(a.teacherId, b.teacherId)) {
                out.add(new Conflict("TEACHER_CONFLICT",
                        teacherName(s, a.teacherId) + " 此时在 " + className(s, b.classId)
                                + " 有 " + courseName(s, b.courseId) + "课（" + periodLabel(periodA) + "）",
                        a.id));
            }
            if (s.settings.enabled(ConstraintType.CLASS_CONFLICT)
                    && Objects.equals(a.classId, b.classId)) {
                out.add(new Conflict("CLASS_CONFLICT",
                        className(s, a.classId) + " 在 " + periodLabel(periodA)
                                + " 已排 " + courseName(s, b.courseId),
                        a.id));
            }
            if (s.settings.enabled(ConstraintType.ROOM_CONFLICT)
                    && a.roomId != null && Objects.equals(a.roomId, b.roomId)) {
                out.add(new Conflict("ROOM_CONFLICT",
                        "教室「" + roomName(s, a.roomId) + "」在 " + periodLabel(periodA)
                                + " 已被 " + className(s, b.classId) + " 占用",
                        a.id));
            }
        }

        ConstraintType oncePerDay = ConstraintType.SAME_COURSE_ONCE_PER_DAY;
        if (s.settings.enabled(oncePerDay)
                && Objects.equals(a.classId, b.classId)
                && Objects.equals(a.courseId, b.courseId)
                && s.settings.matchesCourse(oncePerDay, a.courseId)
                && periodA.getDayOfWeek() == periodB.getDayOfWeek()
                && periodA.getPeriodNo() != periodB.getPeriodNo()) {
            out.add(new Conflict("SAME_COURSE_PER_DAY",
                    className(s, a.classId) + " 的 " + courseName(s, a.courseId)
                            + " 一天最多排一节（" + periodLabel(periodA) + " / " + periodLabel(periodB) + "）",
                    a.id));
        }
    }

    /** Day-level and slot-level rules that need counts rather than pairs. */
    private void checkGroups(Snapshot s, List<Conflict> out, Set<Long> affectedLessonIds) {
        Map<String, Integer> teacherPerDay = new HashMap<>();
        Map<String, Integer> classPerDay = new HashMap<>();
        Map<String, Integer> coursePerDay = new HashMap<>();
        Map<String, Integer> eveningPerClass = new HashMap<>();
        Set<String> relevantKeys = new HashSet<>();
        for (L lesson : s.lessons) {
            if (lesson.periodId == null) {
                continue;
            }
            Period period = s.periods.get(lesson.periodId);
            if (period == null) {
                continue;
            }
            String teacherKey = lesson.teacherId + "-" + period.getDayOfWeek();
            String classKey = lesson.classId + "-" + period.getDayOfWeek();
            String courseKey = lesson.classId + "-" + lesson.courseId + "-" + period.getDayOfWeek();
            teacherPerDay.merge(teacherKey, 1, Integer::sum);
            classPerDay.merge(classKey, 1, Integer::sum);
            coursePerDay.merge(courseKey, 1, Integer::sum);
            if (period.getSection() == PeriodSection.EVENING) {
                eveningPerClass.merge(classKey, 1, Integer::sum);
            }
            if (affectedLessonIds != null && affectedLessonIds.contains(lesson.id)) {
                relevantKeys.add(teacherKey);
                relevantKeys.add(classKey);
                relevantKeys.add(courseKey);
            }
        }
        boolean filter = affectedLessonIds != null;

        ConstraintType teacherMax = ConstraintType.TEACHER_MAX_PER_DAY;
        if (s.settings.enabled(teacherMax) && s.settings.hard(teacherMax)) {
            int max = s.settings.intParam(teacherMax, "maxPerDay") == null
                    ? 4 : s.settings.intParam(teacherMax, "maxPerDay");
            teacherPerDay.forEach((key, count) -> {
                if (count > max && (!filter || relevantKeys.contains(key))) {
                    Long teacherId = Long.parseLong(key.substring(0, key.indexOf('-')));
                    out.add(new Conflict("TEACHER_MAX_PER_DAY",
                            teacherName(s, teacherId) + " 单日排了 " + count + " 节，超过上限 " + max, null));
                }
            });
        }
        ConstraintType classMax = ConstraintType.CLASS_MAX_PER_DAY;
        if (s.settings.enabled(classMax) && s.settings.hard(classMax)) {
            int max = s.settings.intParam(classMax, "maxPerDay") == null
                    ? 8 : s.settings.intParam(classMax, "maxPerDay");
            classPerDay.forEach((key, count) -> {
                if (count > max && (!filter || relevantKeys.contains(key))) {
                    Long classId = Long.parseLong(key.substring(0, key.indexOf('-')));
                    out.add(new Conflict("CLASS_MAX_PER_DAY",
                            className(s, classId) + " 单日排了 " + count + " 节，超过上限 " + max, null));
                }
            });
        }
        ConstraintType courseMax = ConstraintType.COURSE_MAX_PER_DAY;
        if (s.settings.enabled(courseMax) && s.settings.hard(courseMax)) {
            int max = s.settings.intParam(courseMax, "maxPerDay") == null
                    ? 2 : s.settings.intParam(courseMax, "maxPerDay");
            coursePerDay.forEach((key, count) -> {
                if (count > max && (!filter || relevantKeys.contains(key))) {
                    String[] parts = key.split("-");
                    Long classId = Long.parseLong(parts[0]);
                    Long courseId = Long.parseLong(parts[1]);
                    out.add(new Conflict("COURSE_MAX_PER_DAY",
                            className(s, classId) + " 的 " + courseName(s, courseId)
                                    + " 单日排了 " + count + " 节，超过上限 " + max, null));
                }
            });
        }
        ConstraintType eveningMax = ConstraintType.EVENING_MAX;
        if (s.settings.enabled(eveningMax) && s.settings.hard(eveningMax)) {
            int max = s.settings.intParam(eveningMax, "maxPerDay") == null
                    ? 2 : s.settings.intParam(eveningMax, "maxPerDay");
            eveningPerClass.forEach((key, count) -> {
                if (count > max && (!filter || relevantKeys.contains(key))) {
                    Long classId = Long.parseLong(key.substring(0, key.indexOf('-')));
                    out.add(new Conflict("EVENING_MAX",
                            className(s, classId) + " 晚自习排了 " + count + " 节，超过上限 " + max, null));
                }
            });
        }
        ConstraintType sport = ConstraintType.SPORT_NOT_FIRST_AFTERNOON;
        if (s.settings.enabled(sport) && s.settings.hard(sport)) {
            for (L lesson : s.lessons) {
                if (lesson.periodId == null) {
                    continue;
                }
                if (filter && !affectedLessonIds.contains(lesson.id)) {
                    continue;
                }
                Period period = s.periods.get(lesson.periodId);
                Course course = s.courses.get(lesson.courseId);
                Long configured = s.settings.longParam(sport, "courseId");
                boolean isSport = configured != null
                        ? Objects.equals(configured, lesson.courseId)
                        : course != null && course.getName() != null && course.getName().contains("体育");
                if (isSport && period != null && period.getSection() == PeriodSection.AFTERNOON
                        && isFirstAfternoon(period, s)) {
                    out.add(new Conflict("SPORT_NOT_FIRST_AFTERNOON",
                            courseName(s, lesson.courseId) + " 不应排在下午第一节", lesson.id));
                }
            }
        }
    }

    private boolean isFirstAfternoon(Period period, Snapshot s) {
        return s.periods.values().stream()
                .filter(other -> other.getDayOfWeek() == period.getDayOfWeek()
                        && other.getSection() == PeriodSection.AFTERNOON)
                .mapToInt(Period::getPeriodNo)
                .min()
                .orElse(-1) == period.getPeriodNo();
    }

    private Snapshot snapshot(Long draftId) {
        TimetableDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> ApiException.notFound("课表草稿不存在: " + draftId));
        List<DraftLesson> lessons = draftLessons.findByDraftIdOrderByIdAsc(draftId);
        Map<Long, Period> periodMap = new HashMap<>();
        periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(draft.getSemesterId())
                .forEach(period -> periodMap.put(period.getId(), period));
        Map<Long, Room> roomMap = new HashMap<>();
        rooms.findAll().forEach(room -> roomMap.put(room.getId(), room));
        Map<Long, Course> courseMap = new HashMap<>();
        courses.findAll().forEach(course -> courseMap.put(course.getId(), course));
        Map<Long, Teacher> teacherMap = new HashMap<>();
        teachers.findAll().forEach(teacher -> teacherMap.put(teacher.getId(), teacher));
        Map<Long, AdminClass> classMap = new HashMap<>();
        adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(draft.getSemesterId())
                .forEach(adminClass -> classMap.put(adminClass.getId(), adminClass));

        List<com.paiban.scheduling.domain.ConstraintConfig> active =
                constraints.active(draft.getSemesterId());
        ConstraintSettings settings = constraints.hasConfigs(draft.getSemesterId())
                ? ConstraintSettings.from(active, objectMapper)
                : ConstraintSettings.defaults();
        List<L> copies = lessons.stream().map(L::of).toList();
        return new Snapshot(copies, periodMap, roomMap, courseMap, teacherMap, classMap, settings);
    }

    private String teacherName(Snapshot s, Long id) {
        Teacher teacher = id == null ? null : s.teachers.get(id);
        return teacher == null ? "该教师" : teacher.getName();
    }

    private String className(Snapshot s, Long id) {
        AdminClass adminClass = id == null ? null : s.classes.get(id);
        return adminClass == null ? "该班级" : adminClass.getName();
    }

    private String courseName(Snapshot s, Long id) {
        Course course = id == null ? null : s.courses.get(id);
        return course == null ? "该课程" : course.getName();
    }

    private String roomName(Snapshot s, Long id) {
        Room room = id == null ? null : s.rooms.get(id);
        return room == null ? "该教室" : room.getName();
    }

    private String periodLabel(Period period) {
        String day = period.getDayOfWeek() >= 1 && period.getDayOfWeek() <= 7
                ? DAY_NAMES[period.getDayOfWeek()]
                : "周" + period.getDayOfWeek();
        return day + "第" + period.getPeriodNo() + "节";
    }

    private String roomTypeLabel(String type) {
        return switch (type) {
            case "NORMAL" -> "普通教室";
            case "LAB" -> "实验室";
            case "PLAYGROUND" -> "操场";
            case "MULTIMEDIA" -> "多媒体";
            default -> "其他";
        };
    }

    private record Snapshot(List<L> lessons, Map<Long, Period> periods, Map<Long, Room> rooms,
                            Map<Long, Course> courses, Map<Long, Teacher> teachers,
                            Map<Long, AdminClass> classes, ConstraintSettings settings) {

        /** Same reference data, independent lesson copies, for scenario evaluation. */
        Snapshot withFreshLessons() {
            return new Snapshot(lessons.stream().map(L::copy).toList(),
                    periods, rooms, courses, teachers, classes, settings);
        }
    }

    private static final class L {
        private Long id;
        private Long assignmentId;
        private Long courseId;
        private Long teacherId;
        private Long classId;
        private Long periodId;
        private Long roomId;
        private int studentCount;

        static L of(DraftLesson lesson) {
            L copy = new L();
            copy.id = lesson.getId();
            copy.assignmentId = lesson.getAssignmentId();
            copy.courseId = lesson.getCourseId();
            copy.teacherId = lesson.getTeacherId();
            copy.classId = lesson.getAdminClassId();
            copy.periodId = lesson.getPeriodId();
            copy.roomId = lesson.getRoomId();
            copy.studentCount = lesson.getStudentCount();
            return copy;
        }

        L copy() {
            L copy = new L();
            copy.id = id;
            copy.assignmentId = assignmentId;
            copy.courseId = courseId;
            copy.teacherId = teacherId;
            copy.classId = classId;
            copy.periodId = periodId;
            copy.roomId = roomId;
            copy.studentCount = studentCount;
            return copy;
        }
    }
}
