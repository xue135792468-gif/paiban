package com.paiban.scheduling.service;

import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.foundation.domain.Room;
import com.paiban.foundation.domain.RoomRepository;
import com.paiban.foundation.domain.Semester;
import com.paiban.foundation.domain.SemesterRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.foundation.web.FoundationDtos.CourseDto;
import com.paiban.foundation.web.FoundationDtos.TeacherDto;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.solver.Lesson;
import com.paiban.scheduling.solver.PeriodSlot;
import com.paiban.scheduling.solver.RoomRequirement;
import com.paiban.scheduling.solver.RoomSlot;
import com.paiban.scheduling.solver.TimetableSolution;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the immutable solver problem from persisted semester data. */
@Service
public class TimetableProblemBuilder {

    private final PeriodRepository periodRepository;
    private final RoomRepository roomRepository;
    private final AssignmentRepository assignmentRepository;
    private final SemesterRepository semesterRepository;
    private final SemesterBaselineQuery baselines;
    private final TimetableDraftRepository drafts;
    private final DraftLessonRepository draftLessons;
    private final FoundationService foundation;

    public TimetableProblemBuilder(
            PeriodRepository periodRepository,
            RoomRepository roomRepository,
            AssignmentRepository assignmentRepository,
            SemesterRepository semesterRepository,
            SemesterBaselineQuery baselines,
            TimetableDraftRepository drafts,
            DraftLessonRepository draftLessons,
            FoundationService foundation) {
        this.periodRepository = periodRepository;
        this.roomRepository = roomRepository;
        this.assignmentRepository = assignmentRepository;
        this.semesterRepository = semesterRepository;
        this.baselines = baselines;
        this.drafts = drafts;
        this.draftLessons = draftLessons;
        this.foundation = foundation;
    }

    @Transactional(readOnly = true)
    public TimetableSolution build(Long semesterId) {
        List<Period> periods = periodRepository.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId);
        Map<Integer, List<Period>> byDay = new HashMap<>();
        periods.forEach(period -> byDay.computeIfAbsent(period.getDayOfWeek(), key -> new ArrayList<>()).add(period));

        Map<Integer, Integer> firstPeriod = new HashMap<>();
        Map<Integer, Integer> lastPeriod = new HashMap<>();
        Map<Integer, Integer> firstAfternoon = new HashMap<>();
        byDay.forEach((day, dayPeriods) -> {
            firstPeriod.put(day, dayPeriods.stream().mapToInt(Period::getPeriodNo).min().orElse(0));
            lastPeriod.put(day, dayPeriods.stream().mapToInt(Period::getPeriodNo).max().orElse(0));
            dayPeriods.stream()
                    .filter(period -> period.getSection() == PeriodSection.AFTERNOON)
                    .mapToInt(Period::getPeriodNo)
                    .min()
                    .ifPresent(value -> firstAfternoon.put(day, value));
        });

        List<PeriodSlot> periodSlots = periods.stream()
                .map(period -> new PeriodSlot(
                        period.getId(),
                        period.getDayOfWeek(),
                        period.getPeriodNo(),
                        period.getSection(),
                        period.getPeriodNo() == firstPeriod.get(period.getDayOfWeek()),
                        period.getPeriodNo() == lastPeriod.get(period.getDayOfWeek()),
                        firstAfternoon.getOrDefault(period.getDayOfWeek(), -1) == period.getPeriodNo()))
                .sorted(Comparator.comparingInt(PeriodSlot::getDayOfWeek)
                        .thenComparingInt(PeriodSlot::getPeriodNo))
                .toList();

        List<RoomSlot> roomSlots = roomRepository.findAllByOrderByNameAsc().stream()
                .map(room -> new RoomSlot(room.getId(), room.getName(), room.getType(), room.getCapacity()))
                .toList();

        Map<Long, ClassDto> classes = foundation.classesById(semesterId);
        Map<Long, CourseDto> courses = foundation.coursesById();
        Map<Long, TeacherDto> teachers = foundation.teachersById();
        Map<Long, RoomSlot> fixedRooms = fixedRooms(semesterId, classes, roomSlots);
        Map<Long, PeriodSlot> periodById = new HashMap<>();
        periodSlots.forEach(slot -> periodById.put(slot.getId(), slot));
        List<Assignment> assignments = assignmentRepository.findBySemesterId(semesterId);
        Map<AssignmentKey, Deque<PeriodSlot>> warmPeriods =
                warmPeriods(semesterId, periodById, assignments);

        List<Lesson> lessons = new ArrayList<>();
        long lessonId = 1;
        for (Assignment assignment : assignments) {
            ClassDto adminClass = classes.get(assignment.getAdminClassId());
            CourseDto course = courses.get(assignment.getCourseId());
            TeacherDto teacher = teachers.get(assignment.getTeacherId());
            int studentCount = adminClass == null ? 45 : adminClass.studentCount();
            String requiredRoomType = course == null ? null : course.requiredRoomType();
            boolean specialRoom = RoomRequirement.isSpecial(requiredRoomType);
            RoomSlot fixedRoom = specialRoom ? null : fixedRooms.get(assignment.getAdminClassId());
            Deque<PeriodSlot> baselinePeriods = warmPeriods.get(assignmentKey(assignment));
            for (int index = 0; index < assignment.getWeeklyLessons(); index++) {
                Lesson lesson = new Lesson(
                        lessonId++,
                        assignment.getId(),
                        assignment.getCourseId(),
                        course == null ? null : course.name(),
                        assignment.getTeacherId(),
                        assignment.getAdminClassId(),
                        adminClass == null ? null : adminClass.headTeacherId(),
                        teacher == null ? null : teacher.subject(),
                        requiredRoomType,
                        course != null && course.allowConsecutive(),
                        studentCount,
                        index);
                lesson.setFixedRoom(fixedRoom);
                if (fixedRoom != null) {
                    lesson.setRoom(fixedRoom);
                }
                if (baselinePeriods != null && !baselinePeriods.isEmpty()) {
                    lesson.setPeriod(baselinePeriods.poll());
                }
                lessons.add(lesson);
            }
        }
        return new TimetableSolution(lessons, periodSlots, roomSlots);
    }

    /**
     * The published baseline, when it exists, is already complete and conflict-free, so
     * it is a far better starting point than an empty grid. Timefold's construction phase
     * only fills uninitialized lessons; the ones seeded here keep their baseline period and
     * local search starts from a publishable solution. Assignments that were edited after
     * publishing (or have no baseline entry) keep their unassigned periods and are filled
     * by the construction phase as before.
     *
     * <p>A new semester has no baseline yet, but the imported workbook draft is already a
     * complete schedule. If the baseline is empty, the oldest complete draft (normally the
     * import) seeds the same way.
     */
    private Map<AssignmentKey, Deque<PeriodSlot>> warmPeriods(
            Long semesterId, Map<Long, PeriodSlot> periodById, List<Assignment> assignments) {
        Map<AssignmentKey, Deque<PeriodSlot>> baseline = new HashMap<>();
        baselines.publishedLessons(semesterId).stream()
                .sorted(Comparator.comparing(SemesterBaselineQuery.PublishedLesson::periodId))
                .forEach(lesson -> addPeriodIfKnown(
                        baseline, lesson.adminClassId(), lesson.courseId(), lesson.teacherId(),
                        lesson.periodId(), periodById));
        if (!baseline.isEmpty()) {
            return baseline;
        }

        Map<AssignmentKey, Integer> required = new HashMap<>();
        assignments.forEach(assignment -> required.merge(
                assignmentKey(assignment), assignment.getWeeklyLessons(), Integer::sum));

        List<TimetableDraft> existing = drafts.findBySemesterIdOrderByCreatedAtDesc(semesterId);
        for (int i = existing.size() - 1; i >= 0; i--) {
            TimetableDraft draft = existing.get(i);
            List<DraftLesson> rows = draftLessons.findByDraftIdOrderByIdAsc(draft.getId());
            if (rows.isEmpty() || rows.stream().anyMatch(row -> row.getPeriodId() == null)) {
                continue;
            }
            Map<AssignmentKey, Deque<PeriodSlot>> byAssignment = new HashMap<>();
            boolean usable = true;
            for (DraftLesson row : rows) {
                if (!addPeriodIfKnown(byAssignment, row.getAdminClassId(), row.getCourseId(),
                        row.getTeacherId(), row.getPeriodId(), periodById)) {
                    usable = false;
                    break;
                }
            }
            if (usable && isComplete(byAssignment, required)) {
                return byAssignment;
            }
        }
        return Map.of();
    }

    /** A draft is complete when every assignment has all of its weekly lessons seeded. */
    private static boolean isComplete(
            Map<AssignmentKey, Deque<PeriodSlot>> byAssignment,
            Map<AssignmentKey, Integer> required) {
        return required.entrySet().stream().allMatch(entry ->
                byAssignment.getOrDefault(entry.getKey(), new ArrayDeque<>()).size()
                        >= entry.getValue());
    }

    /**
     * Adds one period to the assignment's queue; false when the period is unknown, i.e.
     * the row points at a period id that is not part of this semester.
     */
    private static boolean addPeriodIfKnown(
            Map<AssignmentKey, Deque<PeriodSlot>> byAssignment, Long classId, Long courseId,
            Long teacherId, Long periodId, Map<Long, PeriodSlot> periodById) {
        PeriodSlot slot = periodId == null ? null : periodById.get(periodId);
        if (slot == null) {
            return false;
        }
        byAssignment.computeIfAbsent(
                assignmentKey(classId, courseId, teacherId), key -> new ArrayDeque<>()).add(slot);
        return true;
    }

    private static AssignmentKey assignmentKey(Assignment assignment) {
        return assignmentKey(
                assignment.getAdminClassId(), assignment.getCourseId(), assignment.getTeacherId());
    }

    private static AssignmentKey assignmentKey(Long classId, Long courseId, Long teacherId) {
        return new AssignmentKey(classId, courseId, teacherId);
    }

    /** The identity a lesson row shares with its teaching assignment. */
    private record AssignmentKey(Long classId, Long courseId, Long teacherId) {}

    /**
     * Resolves each class's own ordinary classroom with the same deterministic name the
     * room-fill action uses ({@code 学期-年级班名-固定教室}). The solver then keeps the
     * lesson in that room instead of scattering a class over every interchangeable room.
     * Bad data (missing, too small, shared room) yields no pin, so the room stays free
     * rather than making the whole problem infeasible.
     */
    private Map<Long, RoomSlot> fixedRooms(
            Long semesterId, Map<Long, ClassDto> classes, List<RoomSlot> roomSlots) {
        Semester semester = semesterRepository.findById(semesterId).orElse(null);
        if (semester == null) {
            return Map.of();
        }
        Map<Long, RoomSlot> roomById = new HashMap<>();
        roomSlots.forEach(room -> roomById.put(room.getId(), room));

        Map<Long, RoomFillPlanner.ExistingRoom> planned = RoomFillPlanner.fixedRooms(
                semester.getName(),
                classes.values().stream()
                        .map(adminClass -> new RoomFillPlanner.ClassInfo(
                                adminClass.id(), adminClass.name(), adminClass.gradeName(),
                                adminClass.studentCount()))
                        .toList(),
                roomSlots.stream()
                        .map(room -> new RoomFillPlanner.ExistingRoom(
                                room.getId(), room.getName(), room.getType(), room.getCapacity()))
                        .toList());

        Map<Long, RoomSlot> fixed = new HashMap<>();
        planned.forEach((classId, room) -> {
            RoomSlot slot = roomById.get(room.id());
            if (slot != null) {
                fixed.put(classId, slot);
            }
        });
        return fixed;
    }
}
