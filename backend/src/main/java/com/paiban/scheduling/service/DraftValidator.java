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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    @Transactional(readOnly = true)
    public ValidationResult validateMove(Long draftId, Long lessonId, Long periodId, Long roomId) {
        Snapshot snapshot = snapshot(draftId);
        L moved = snapshot.lessons.stream()
                .filter(lesson -> lesson.id.equals(lessonId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("课节不存在: " + lessonId));
        moved.periodId = periodId;
        moved.roomId = roomId;

        List<Conflict> conflicts = new ArrayList<>();
        checkLesson(moved, snapshot, conflicts);
        for (L other : snapshot.lessons) {
            if (!other.id.equals(moved.id)) {
                checkPair(moved, other, snapshot, conflicts);
            }
        }
        return new ValidationResult(conflicts.isEmpty(), conflicts.size(), conflicts);
    }

    @Transactional(readOnly = true)
    public ValidationResult validateDraft(Long draftId) {
        Snapshot snapshot = snapshot(draftId);
        List<Conflict> conflicts = new ArrayList<>();
        for (L lesson : snapshot.lessons) {
            checkLesson(lesson, snapshot, conflicts);
        }
        for (int i = 0; i < snapshot.lessons.size(); i++) {
            for (int j = i + 1; j < snapshot.lessons.size(); j++) {
                checkPair(snapshot.lessons.get(i), snapshot.lessons.get(j), snapshot, conflicts);
            }
        }
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

        if (room != null && room.getCapacity() < lesson.studentCount) {
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
        if (a.periodId == null || b.periodId == null || !a.periodId.equals(b.periodId)) {
            return;
        }
        Period period = s.periods.get(a.periodId);
        if (period == null) {
            return;
        }
        if (Objects.equals(a.teacherId, b.teacherId)) {
            out.add(new Conflict("TEACHER_CONFLICT",
                    teacherName(s, a.teacherId) + " 此时在 " + className(s, b.classId)
                            + " 有 " + courseName(s, b.courseId) + "课（" + periodLabel(period) + "）",
                    a.id));
        }
        if (Objects.equals(a.classId, b.classId)) {
            out.add(new Conflict("CLASS_CONFLICT",
                    className(s, a.classId) + " 在 " + periodLabel(period)
                            + " 已排 " + courseName(s, b.courseId),
                    a.id));
        }
        if (a.roomId != null && Objects.equals(a.roomId, b.roomId)) {
            out.add(new Conflict("ROOM_CONFLICT",
                    "教室「" + roomName(s, a.roomId) + "」在 " + periodLabel(period)
                            + " 已被 " + className(s, b.classId) + " 占用",
                    a.id));
        }

        ConstraintType oncePerDay = ConstraintType.SAME_COURSE_ONCE_PER_DAY;
        if (s.settings.enabled(oncePerDay)
                && Objects.equals(a.classId, b.classId)
                && Objects.equals(a.courseId, b.courseId)
                && s.settings.matchesCourse(oncePerDay, a.courseId)
                && period.getDayOfWeek() == s.periods.get(b.periodId).getDayOfWeek()
                && period.getPeriodNo() != s.periods.get(b.periodId).getPeriodNo()) {
            out.add(new Conflict("SAME_COURSE_PER_DAY",
                    className(s, a.classId) + " 的 " + courseName(s, a.courseId)
                            + " 一天最多排一节",
                    a.id));
        }
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
        ConstraintSettings settings = active.isEmpty()
                ? ConstraintSettings.defaults()
                : ConstraintSettings.from(active, objectMapper);
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
                            Map<Long, AdminClass> classes, ConstraintSettings settings) {}

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
    }
}
