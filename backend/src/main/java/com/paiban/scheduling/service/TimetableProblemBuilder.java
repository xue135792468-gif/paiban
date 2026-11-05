package com.paiban.scheduling.service;

import com.paiban.common.web.ApiException;
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
import com.paiban.scheduling.service.SourceDraftSnapshot.SourceLesson;
import com.paiban.scheduling.solver.ConstraintSettings;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the immutable solver problem from persisted semester data. */
@Service
public class TimetableProblemBuilder {

    private final PeriodRepository periodRepository;
    private final RoomRepository roomRepository;
    private final AssignmentRepository assignmentRepository;
    private final SemesterRepository semesterRepository;
    private final WarmStartSeedService warmStart;
    private final FoundationService foundation;
    private final SemesterBaselineQuery baselines;

    private static final String MISSING_OTHER_GRADE_SCHEDULE =
            "其他年级还没有已排课表，无法保持不动；请选择全部年级，或先导入/发布其他年级";

    public TimetableProblemBuilder(
            PeriodRepository periodRepository,
            RoomRepository roomRepository,
            AssignmentRepository assignmentRepository,
            SemesterRepository semesterRepository,
            WarmStartSeedService warmStart,
            FoundationService foundation,
            SemesterBaselineQuery baselines) {
        this.periodRepository = periodRepository;
        this.roomRepository = roomRepository;
        this.assignmentRepository = assignmentRepository;
        this.semesterRepository = semesterRepository;
        this.warmStart = warmStart;
        this.foundation = foundation;
        this.baselines = baselines;
    }

    /** The solver problem plus where its warm start came from: BASELINE, DRAFT, NONE or SOURCE_DRAFT. */
    public record BuiltProblem(TimetableSolution solution, String warmStartSource) {}

    /** The semester-wide facts both builders share, read once per problem. */
    private record Context(
            List<PeriodSlot> periodSlots,
            List<RoomSlot> roomSlots,
            Map<Long, RoomSlot> roomById,
            Map<Long, ClassDto> classes,
            Map<Long, CourseDto> courses,
            Map<Long, TeacherDto> teachers,
            Map<Long, RoomSlot> fixedRooms,
            Map<Long, PeriodSlot> periodById) {}

    /**
     * Builds the problem with the solver's own defaults. Kept for the standalone
     * benchmark, which measures solve time against a fixed problem and never reads the
     * active semester configuration; production always passes the real settings through
     * {@link #buildWithWarmStart(Long, ConstraintSettings)}.
     */
    @Transactional(readOnly = true)
    public TimetableSolution build(Long semesterId) {
        return buildWithWarmStart(semesterId, ConstraintSettings.defaults()).solution();
    }

    @Transactional(readOnly = true)
    public BuiltProblem buildWithWarmStart(Long semesterId, ConstraintSettings settings) {
        return buildWithWarmStart(semesterId, settings, null);
    }

    /**
     * Builds the default warm-start problem. When {@code gradeIds} is non-null only the
     * selected grades stay movable; every other grade's lessons are added as fixed facts
     * with their published period pinned by {@code lockedPeriodId}. Keeping those lessons
     * in the same problem is what makes shared-teacher daily/consecutive totals count
     * across grades instead of per grade.
     */
    @Transactional(readOnly = true)
    public BuiltProblem buildWithWarmStart(
            Long semesterId, ConstraintSettings settings, Set<Long> gradeIds) {
        Context context = context(semesterId);
        List<Assignment> assignments = assignmentRepository.findBySemesterIdAndDeletedAtIsNull(semesterId);
        WarmStartSeedService.WarmStartSeed seed =
                warmStart.seed(semesterId, context.periodById(), assignments);
        Map<AssignmentKey, Deque<PeriodSlot>> warmPeriods = seed.periods();
        Map<Long, Deque<PeriodSlot>> fixedPeriods =
                fixedPeriodsFromBaseline(semesterId, assignments, context, gradeIds);

        List<Lesson> lessons = new ArrayList<>();
        long lessonId = 1;
        for (Assignment assignment : assignments) {
            ClassDto adminClass = context.classes().get(assignment.getAdminClassId());
            CourseDto course = context.courses().get(assignment.getCourseId());
            TeacherDto teacher = context.teachers().get(assignment.getTeacherId());
            int studentCount = adminClass == null ? 45 : adminClass.studentCount();
            String requiredRoomType = course == null ? null : course.requiredRoomType();
            boolean specialRoom = RoomRequirement.isSpecial(requiredRoomType);
            RoomSlot fixedRoom = specialRoom
                    ? null
                    : context.fixedRooms().get(assignment.getAdminClassId());
            if (!inScope(assignment.getAdminClassId(), context, gradeIds)) {
                Deque<PeriodSlot> fixed = fixedPeriods.get(assignment.getId());
                if (fixed == null || fixed.size() < assignment.getWeeklyLessons()) {
                    throw ApiException.conflict(MISSING_OTHER_GRADE_SCHEDULE);
                }
                for (int index = 0; index < assignment.getWeeklyLessons(); index++) {
                    PeriodSlot slot = fixed.poll();
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
                    // A fixed fact, not a user lock: the period may not move, but the new
                    // draft does not inherit a "locked" marker the user never set.
                    lesson.setPeriod(slot);
                    lesson.setLockedPeriodId(slot.getId());
                    lessons.add(lesson);
                }
                continue;
            }
            Deque<PeriodSlot> baselinePeriods = warmPeriods.get(AssignmentKey.of(assignment));
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
                    PeriodSlot proposed = baselinePeriods.poll();
                    if (!settings.classUnavailable(assignment.getAdminClassId(),
                            proposed.getDayOfWeek(), proposed.getPeriodNo())) {
                        lesson.setPeriod(proposed);
                    }
                }
                lessons.add(lesson);
            }
        }
        return new BuiltProblem(
                new TimetableSolution(lessons, context.periodSlots(), context.roomSlots()),
                seed.source());
    }

    /**
     * Builds the problem from an explicit, frozen source draft instead of the default
     * warm-start policy. Every assignment's concrete lessons are matched by
     * {@code (assignmentId, lessonIndex)}; the source draft's current teacher is kept
     * (manual cross-class exchanges survive), and locked lessons are pinned with a hard
     * constraint rather than only being used as an initial value.
     */
    @Transactional(readOnly = true)
    public BuiltProblem buildFromSource(
            Long semesterId, ConstraintSettings settings, SourceDraftSnapshot snapshot) {
        return buildFromSource(semesterId, settings, snapshot, null);
    }

    /**
     * Builds from the frozen source draft. Selected grades keep the source teacher and
     * locks; unselected grades are added as fixed facts using the source period, so the
     * new draft still contains the whole school and shared-teacher totals stay correct.
     */
    @Transactional(readOnly = true)
    public BuiltProblem buildFromSource(
            Long semesterId, ConstraintSettings settings, SourceDraftSnapshot snapshot,
            Set<Long> gradeIds) {
        Context context = context(semesterId);
        List<Assignment> assignments = assignmentRepository.findBySemesterIdAndDeletedAtIsNull(semesterId);
        Map<String, SourceLesson> rows = new HashMap<>();
        snapshot.lessons().forEach(row -> rows.put(lessonKey(row.assignmentId(), row.lessonIndex()), row));

        List<Lesson> lessons = new ArrayList<>();
        long lessonId = 1;
        for (Assignment assignment : assignments) {
            ClassDto adminClass = context.classes().get(assignment.getAdminClassId());
            CourseDto course = context.courses().get(assignment.getCourseId());
            String requiredRoomType = course == null ? null : course.requiredRoomType();
            boolean specialRoom = RoomRequirement.isSpecial(requiredRoomType);
            RoomSlot fixedRoom = specialRoom
                    ? null
                    : context.fixedRooms().get(assignment.getAdminClassId());
            boolean scoped = inScope(assignment.getAdminClassId(), context, gradeIds);
            for (int index = 0; index < assignment.getWeeklyLessons(); index++) {
                SourceLesson row = rows.get(lessonKey(assignment.getId(), index));
                if (row == null) {
                    // capture() already checked this; keep a hard stop instead of silently
                    // dropping a lesson and losing its lock.
                    throw new IllegalStateException(
                            "来源草稿缺少授课任务的课节: " + assignment.getId() + "/" + index);
                }
                PeriodSlot slot = row.periodId() == null
                        ? null
                        : context.periodById().get(row.periodId());
                if (!scoped) {
                    if (slot == null) {
                        throw ApiException.conflict(MISSING_OTHER_GRADE_SCHEDULE);
                    }
                    TeacherDto teacher = context.teachers().get(assignment.getTeacherId());
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
                            row.studentCount(),
                            index);
                    lesson.setFixedRoom(fixedRoom);
                    if (fixedRoom != null) {
                        lesson.setRoom(fixedRoom);
                    } else if (row.roomId() != null) {
                        RoomSlot sourceRoom = context.roomById().get(row.roomId());
                        if (sourceRoom != null) {
                            lesson.setRoom(sourceRoom);
                        }
                    }
                    lesson.setPeriod(slot);
                    lesson.setLockedPeriodId(slot.getId());
                    lesson.setLocked(row.locked());
                    lessons.add(lesson);
                    continue;
                }
                TeacherDto teacher = context.teachers().get(row.teacherId());
                Lesson lesson = new Lesson(
                        lessonId++,
                        assignment.getId(),
                        assignment.getCourseId(),
                        course == null ? null : course.name(),
                        row.teacherId(),
                        assignment.getAdminClassId(),
                        adminClass == null ? null : adminClass.headTeacherId(),
                        teacher == null ? null : teacher.subject(),
                        requiredRoomType,
                        course != null && course.allowConsecutive(),
                        row.studentCount(),
                        index);
                lesson.setFixedRoom(fixedRoom);
                if (fixedRoom != null) {
                    lesson.setRoom(fixedRoom);
                } else if (row.roomId() != null) {
                    RoomSlot sourceRoom = context.roomById().get(row.roomId());
                    if (sourceRoom != null) {
                        lesson.setRoom(sourceRoom);
                    }
                }
                if (row.locked()) {
                    if (slot == null) {
                        throw new IllegalStateException(
                                "锁定课节引用的作息时段不存在: " + row.periodId());
                    }
                    // A lock is unconditional: keep the slot even when a current rule
                    // marks it unavailable. The validation afterwards reports the
                    // conflict instead of the solver silently moving the locked lesson.
                    lesson.setLocked(true);
                    lesson.setLockedPeriodId(row.periodId());
                    lesson.setPeriod(slot);
                } else if (slot != null && !settings.classUnavailable(
                        assignment.getAdminClassId(), slot.getDayOfWeek(), slot.getPeriodNo())) {
                    lesson.setPeriod(slot);
                }
                lessons.add(lesson);
            }
        }
        return new BuiltProblem(
                new TimetableSolution(lessons, context.periodSlots(), context.roomSlots()),
                "SOURCE_DRAFT");
    }

    /**
     * Start-time precondition for a grade-scoped job: every assignment outside the scope
     * must already have a period in the source snapshot or the published baseline. Without
     * one there is no truthful "keep it unchanged" value, so the request is refused before
     * a job row is created instead of silently dropping those lessons from the problem.
     */
    @Transactional(readOnly = true)
    public void requireScopedSource(
            Long semesterId, Set<Long> gradeIds, SourceDraftSnapshot snapshot) {
        if (gradeIds == null || gradeIds.isEmpty()) {
            return;
        }
        Context context = context(semesterId);
        List<Assignment> assignments = assignmentRepository.findBySemesterIdAndDeletedAtIsNull(semesterId);
        if (snapshot != null) {
            // The captured source covers every assignment, but an unlocked lesson may
            // still have no period (an incomplete import). Such a lesson has nothing to
            // keep, so refuse now instead of failing the async run later.
            Map<String, SourceLesson> rows = new HashMap<>();
            snapshot.lessons().forEach(row ->
                    rows.put(lessonKey(row.assignmentId(), row.lessonIndex()), row));
            for (Assignment assignment : assignments) {
                if (inScope(assignment.getAdminClassId(), context, gradeIds)) {
                    continue;
                }
                for (int index = 0; index < assignment.getWeeklyLessons(); index++) {
                    SourceLesson row = rows.get(lessonKey(assignment.getId(), index));
                    if (row == null || row.periodId() == null
                            || !context.periodById().containsKey(row.periodId())) {
                        throw ApiException.conflict(MISSING_OTHER_GRADE_SCHEDULE);
                    }
                }
            }
            return;
        }
        fixedPeriodsFromBaseline(semesterId, assignments, context, gradeIds);
    }

    /** True when this class's grade is inside the requested scope; null scope means all. */
    private static boolean inScope(Long adminClassId, Context context, Set<Long> gradeIds) {
        if (gradeIds == null) {
            return true;
        }
        ClassDto adminClass = context.classes().get(adminClassId);
        return adminClass != null && gradeIds.contains(adminClass.gradeId());
    }

    /**
     * One period queue per unselected assignment, taken from the published baseline in
     * period-id order. Missing coverage is a 409: a grade-scoped solve must never quietly
     * drop another grade's lessons, because that would hide a shared teacher's load.
     */
    private Map<Long, Deque<PeriodSlot>> fixedPeriodsFromBaseline(
            Long semesterId, List<Assignment> assignments, Context context, Set<Long> gradeIds) {
        if (gradeIds == null) {
            return Map.of();
        }
        Map<AssignmentKey, Deque<PeriodSlot>> byKey = new LinkedHashMap<>();
        baselines.publishedLessons(semesterId).stream()
                .sorted(Comparator.comparing(SemesterBaselineQuery.PublishedLesson::periodId))
                .forEach(row -> {
                    PeriodSlot slot = context.periodById().get(row.periodId());
                    if (slot == null) {
                        return;
                    }
                    byKey.computeIfAbsent(
                                    new AssignmentKey(
                                            row.adminClassId(), row.courseId(), row.teacherId()),
                                    key -> new ArrayDeque<>())
                            .add(slot);
                });
        Map<Long, Deque<PeriodSlot>> fixed = new LinkedHashMap<>();
        for (Assignment assignment : assignments) {
            if (inScope(assignment.getAdminClassId(), context, gradeIds)) {
                continue;
            }
            Deque<PeriodSlot> periods = byKey.get(AssignmentKey.of(assignment));
            if (periods == null || periods.size() < assignment.getWeeklyLessons()) {
                throw ApiException.conflict(MISSING_OTHER_GRADE_SCHEDULE);
            }
            fixed.put(assignment.getId(), periods);
        }
        return fixed;
    }

    private static String lessonKey(Long assignmentId, int lessonIndex) {
        return assignmentId + "#" + lessonIndex;
    }

    private Context context(Long semesterId) {
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
        Map<Long, RoomSlot> roomById = new HashMap<>();
        roomSlots.forEach(room -> roomById.put(room.getId(), room));

        Map<Long, ClassDto> classes = foundation.classesById(semesterId);
        Map<Long, CourseDto> courses = foundation.coursesById();
        Map<Long, TeacherDto> teachers = foundation.teachersById();
        Map<Long, RoomSlot> fixedRooms = fixedRooms(semesterId, classes, roomSlots);
        Map<Long, PeriodSlot> periodById = new HashMap<>();
        periodSlots.forEach(slot -> periodById.put(slot.getId(), slot));
        return new Context(periodSlots, roomSlots, roomById, classes, courses, teachers,
                fixedRooms, periodById);
    }

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
