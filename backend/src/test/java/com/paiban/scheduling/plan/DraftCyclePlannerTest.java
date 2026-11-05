package com.paiban.scheduling.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.policy.ExchangeRankingPreset;
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
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.DraftLesson;
import com.paiban.scheduling.domain.DraftLessonRepository;
import com.paiban.scheduling.domain.TimetableDraft;
import com.paiban.scheduling.domain.TimetableDraftRepository;
import com.paiban.scheduling.plan.DraftCyclePlanner.Candidate;
import com.paiban.scheduling.plan.DraftCyclePlanner.Move;
import com.paiban.scheduling.plan.DraftCyclePlanner.Options;
import com.paiban.scheduling.plan.DraftCyclePlanner.Result;
import com.paiban.scheduling.service.ConstraintService;
import com.paiban.scheduling.service.DraftValidator;
import com.paiban.scheduling.service.DraftValidator.PlanLesson;
import com.paiban.scheduling.service.SemesterBaselineQuery;
import com.paiban.scheduling.solver.ConstraintSettings;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The cycle search is judged on counterexamples, not on "it usually works":
 * a three-lesson cycle must be found when the direct swap is blocked, necessary
 * pruning must never hide it, locks must stop it, and a tiny budget must be
 * reported as "not finished" instead of "no solution".
 */
class DraftCyclePlannerTest {

    private static final long DRAFT_ID = 1L;
    private static final long SEMESTER_ID = 10L;

    private static final long P1 = 101L;
    private static final long P2 = 102L;
    private static final long P3 = 103L;
    private static final long P4 = 104L;
    private static final long P5 = 105L;

    private static final long CLASS_1 = 201L;
    private static final long CLASS_2 = 202L;

    private static final long CHINESE = 301L;
    private static final long MATH = 302L;
    private static final long ENGLISH = 303L;
    private static final long PHYSICS = 304L;

    private static final long TEACHER_A = 401L;
    private static final long TEACHER_B = 402L;
    private static final long TEACHER_C = 403L;

    private static final long ROOM_1 = 501L;
    private static final long ROOM_2 = 502L;
    private static final long ROOM_3 = 503L;
    private static final long ROOM_4 = 504L;
    private static final long ROOM_5 = 505L;

    private static final long SOURCE = 601L;
    private static final long TARGET = 602L;
    private static final long INTERMEDIATE = 603L;

    private DraftValidator validator;
    private DraftLessonRepository draftLessons;
    private ConstraintService constraints;
    private TimetableDraft draft;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TimetableDraftRepository drafts = mock(TimetableDraftRepository.class);
        draftLessons = mock(DraftLessonRepository.class);
        PeriodRepository periods = mock(PeriodRepository.class);
        RoomRepository rooms = mock(RoomRepository.class);
        CourseRepository courses = mock(CourseRepository.class);
        TeacherRepository teachers = mock(TeacherRepository.class);
        AdminClassRepository adminClasses = mock(AdminClassRepository.class);
        constraints = mock(ConstraintService.class);
        SemesterBaselineQuery baselines = mock(SemesterBaselineQuery.class);
        when(constraints.settings(SEMESTER_ID)).thenReturn(ConstraintSettings.defaults());

        draft = new TimetableDraft();
        draft.setId(DRAFT_ID);
        draft.setSemesterId(SEMESTER_ID);
        when(drafts.findById(DRAFT_ID)).thenReturn(Optional.of(draft));
        when(drafts.findByIdAndDeletedAtIsNull(DRAFT_ID)).thenReturn(Optional.of(draft));

        when(periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(SEMESTER_ID)).thenReturn(List.of(
                period(P1, 1, 1), period(P2, 1, 2), period(P3, 1, 3),
                period(P4, 1, 4), period(P5, 2, 1)));
        when(rooms.findAll()).thenReturn(List.of(
                room(ROOM_1, "教室1"), room(ROOM_2, "教室2"), room(ROOM_3, "教室3"),
                room(ROOM_4, "教室4"), room(ROOM_5, "教室5")));
        when(courses.findAll()).thenReturn(List.of(
                course(CHINESE, "语文"), course(MATH, "数学"),
                course(ENGLISH, "英语"), course(PHYSICS, "物理")));
        when(teachers.findAll()).thenReturn(List.of(
                teacher(TEACHER_A, "张老师"), teacher(TEACHER_B, "李老师"),
                teacher(TEACHER_C, "王老师")));
        when(adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(SEMESTER_ID)).thenReturn(List.of(
                adminClass(CLASS_1, "高一1班"), adminClass(CLASS_2, "高一2班")));

        validator = new DraftValidator(
                drafts, draftLessons, periods, rooms, courses, teachers, adminClasses,
                constraints, baselines);
    }

    @Test
    void legalDirectSwapIsReturnedWithoutSearching() {
        useLessons(
                lesson(SOURCE, CHINESE, TEACHER_A, CLASS_1, P1, ROOM_1),
                lesson(TARGET, MATH, TEACHER_B, CLASS_1, P2, ROOM_2));

        Result result = plan(SOURCE, P2, 3, 5_000);

        assertThat(result.budgetExhausted()).isFalse();
        assertThat(result.candidates()).hasSize(1);
        Candidate candidate = result.candidates().get(0);
        assertThat(candidate.cycleLength()).isEqualTo(2);
        assertThat(candidate.moves()).containsExactlyInAnyOrder(
                new Move(SOURCE, P1, P2), new Move(TARGET, P2, P1));
    }

    @Test
    void threeLessonCycleIsFoundWhenTheDirectSwapIsBlocked() {
        useLessons(
                lesson(SOURCE, CHINESE, TEACHER_A, CLASS_1, P1, ROOM_1),
                lesson(TARGET, MATH, TEACHER_B, CLASS_1, P2, ROOM_2),
                lesson(INTERMEDIATE, ENGLISH, TEACHER_C, CLASS_1, P3, ROOM_3),
                // Other class occupies the source slot with TARGET's teacher: T cannot go home.
                lesson(900L, PHYSICS, TEACHER_B, CLASS_2, P1, ROOM_4));

        Result result = plan(SOURCE, P2, 3, 5_000);

        assertThat(result.budgetExhausted()).isFalse();
        assertThat(result.candidates()).isNotEmpty();
        Candidate candidate = result.candidates().get(0);
        assertThat(candidate.cycleLength()).isEqualTo(3);
        assertThat(candidate.moves()).containsExactlyInAnyOrder(
                new Move(SOURCE, P1, P2),
                new Move(TARGET, P2, P3),
                new Move(INTERMEDIATE, P3, P1));
    }

    @Test
    void repeatedSearchOnTheSameSnapshotIsStableAndDuplicateFree() {
        useThreeLessonCycle();

        Result first = plan(SOURCE, P2, 3, 5_000);
        Result second = plan(SOURCE, P2, 3, 5_000);

        assertThat(keys(first)).isEqualTo(keys(second)).isNotEmpty();
        assertThat(first.candidates()).doesNotHaveDuplicates();
    }

    @Test
    void maxMovedLessonsCapsTheCycleLengthButStillAllowsTheDirectSwap() {
        useThreeLessonCycle();

        Result limited = plan(SOURCE, P2, 2, 5_000);
        assertThat(limited.budgetExhausted()).isFalse();
        assertThat(limited.candidates()).isEmpty();

        useLessons(
                lesson(SOURCE, CHINESE, TEACHER_A, CLASS_1, P1, ROOM_1),
                lesson(TARGET, MATH, TEACHER_B, CLASS_1, P2, ROOM_2));
        Result direct = plan(SOURCE, P2, 2, 5_000);
        assertThat(direct.candidates()).hasSize(1);
        assertThat(direct.candidates().get(0).cycleLength()).isEqualTo(2);
    }

    @Test
    void lockedIntermediateLessonMakesTheCycleImpossible() {
        useLessons(
                lesson(SOURCE, CHINESE, TEACHER_A, CLASS_1, P1, ROOM_1),
                lesson(TARGET, MATH, TEACHER_B, CLASS_1, P2, ROOM_2),
                lockedLesson(INTERMEDIATE, ENGLISH, TEACHER_C, CLASS_1, P3, ROOM_3),
                lesson(900L, PHYSICS, TEACHER_B, CLASS_2, P1, ROOM_4));

        Result result = plan(SOURCE, P2, 3, 5_000);

        assertThat(result.budgetExhausted()).isFalse();
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void otherClassTeacherOnTheOnlyEscapeSlotPrunesThePlanAndTheVerifierRejectsForgeries() {
        useLessons(
                lesson(SOURCE, CHINESE, TEACHER_A, CLASS_1, P1, ROOM_1),
                lesson(TARGET, MATH, TEACHER_B, CLASS_1, P2, ROOM_2),
                lesson(INTERMEDIATE, ENGLISH, TEACHER_C, CLASS_1, P3, ROOM_3),
                lesson(900L, PHYSICS, TEACHER_B, CLASS_2, P1, ROOM_4),
                // Another class already sits on P3 with TARGET's teacher: T cannot escape there.
                lesson(901L, PHYSICS, TEACHER_B, CLASS_2, P3, ROOM_5));

        Result result = plan(SOURCE, P2, 3, 5_000);
        assertThat(result.candidates()).isEmpty();

        // The independent whole-table verifier must reject a forged "plan" that ignores
        // the same teacher conflict, even though the search pruning is not involved.
        DraftValidator.ChangeAssessment forged = validator.assess(
                validator.planningSnapshot(DRAFT_ID),
                List.of(
                        new DraftValidator.LessonChange(SOURCE, P2, ROOM_1, TEACHER_A),
                        new DraftValidator.LessonChange(TARGET, P3, ROOM_2, TEACHER_B),
                        new DraftValidator.LessonChange(INTERMEDIATE, P1, ROOM_3, TEACHER_C)));
        assertThat(forged.added()).isNotEmpty();
    }

    @Test
    void tinyNodeBudgetReportsExhaustionInsteadOfNoSolution() {
        useThreeLessonCycle();

        Result result = plan(SOURCE, P2, 3, 5_000, 1);

        assertThat(result.budgetExhausted()).isTrue();
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void distinctTeacherCountIgnoresHowManyLessonsOneTeacherMoves() {
        // Direct swap between two lessons of the same teacher: one teacher, two lessons.
        useLessons(
                lesson(SOURCE, CHINESE, TEACHER_A, CLASS_1, P1, ROOM_1),
                lesson(TARGET, MATH, TEACHER_A, CLASS_1, P2, ROOM_2));

        Result result = plan(SOURCE, P2, 3, 5_000);

        assertThat(result.candidates()).hasSize(1);
        Candidate candidate = result.candidates().get(0);
        assertThat(candidate.cycleLength()).isEqualTo(2);
        assertThat(candidate.impactTeachers()).isEqualTo(1);

        useThreeLessonCycle();
        Candidate cycle = plan(SOURCE, P2, 3, 5_000).candidates().get(0);
        assertThat(cycle.cycleLength()).isEqualTo(3);
        assertThat(cycle.impactTeachers()).isEqualTo(3);
    }

    @Test
    void twoSameClassLessonsThatBothMoveAreNotTreatedAsAStayingOccupant() {
        PlanLesson first = planLesson(1L, CLASS_1, P3);
        PlanLesson second = planLesson(2L, CLASS_1, P3);

        // Both occupants leave: the slot is vacated, so this must not prune it.
        assertThat(DraftCyclePlanner.hasStayingSameClass(
                List.of(first, second), CLASS_1, Set.of(1L, 2L))).isFalse();
        // One occupant stays: landing there would leave a class conflict.
        assertThat(DraftCyclePlanner.hasStayingSameClass(
                List.of(first, second), CLASS_1, Set.of(1L))).isTrue();
        // A different class never blocks this class's own landing.
        assertThat(DraftCyclePlanner.hasStayingSameClass(
                List.of(planLesson(3L, CLASS_2, P3)), CLASS_1, Set.of())).isFalse();
    }

    @Test
    void aSoftRoomConflictOnTheOnlyEscapeSlotIsNotPrunedAway() {
        useHardTeacherSoftRoomSettings();
        // The direct swap adds a hard teacher conflict at P1, so it is refused. The only
        // cycle needs T to land on P3 where another class already uses its room; that is
        // a soft room conflict, which the final assessment scores but does not block. A
        // hardness-blind pruning rule would drop the only legal plan.
        useLessons(
                lesson(SOURCE, CHINESE, TEACHER_A, CLASS_1, P1, ROOM_1),
                lesson(TARGET, MATH, TEACHER_B, CLASS_1, P2, ROOM_2),
                lesson(INTERMEDIATE, ENGLISH, TEACHER_C, CLASS_1, P3, ROOM_3),
                lesson(900L, PHYSICS, TEACHER_B, CLASS_2, P1, ROOM_4),
                lesson(910L, PHYSICS, TEACHER_C, CLASS_2, P2, ROOM_2),
                lesson(911L, PHYSICS, TEACHER_A, CLASS_2, P3, ROOM_2));

        Result result = plan(SOURCE, P2, 3, 5_000);

        assertThat(result.budgetExhausted()).isFalse();
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates().get(0).moves()).containsExactlyInAnyOrder(
                new Move(SOURCE, P1, P2),
                new Move(TARGET, P2, P3),
                new Move(INTERMEDIATE, P3, P1));
    }

    // ------------------------------------------------------------------ fixtures

    private void useHardTeacherSoftRoomSettings() {
        when(constraints.settings(SEMESTER_ID)).thenReturn(ConstraintSettings.from(
                List.of(conflictConfig(ConstraintType.TEACHER_CONFLICT, true),
                        conflictConfig(ConstraintType.ROOM_CONFLICT, false)),
                mapper));
    }

    private static ConstraintConfig conflictConfig(ConstraintType type, boolean hard) {
        ConstraintConfig config = new ConstraintConfig();
        config.setType(type.name());
        config.setHard(hard);
        config.setWeight(40);
        config.setEnabled(true);
        config.setParamsJson("{}");
        return config;
    }

    private static PlanLesson planLesson(long id, long classId, Long periodId) {
        return new PlanLesson(id, id, 1, classId, CHINESE, TEACHER_A, periodId, ROOM_1, 45, false);
    }

    private void useThreeLessonCycle() {
        useLessons(
                lesson(SOURCE, CHINESE, TEACHER_A, CLASS_1, P1, ROOM_1),
                lesson(TARGET, MATH, TEACHER_B, CLASS_1, P2, ROOM_2),
                lesson(INTERMEDIATE, ENGLISH, TEACHER_C, CLASS_1, P3, ROOM_3),
                lesson(900L, PHYSICS, TEACHER_B, CLASS_2, P1, ROOM_4));
    }

    private Result plan(long sourceLessonId, long targetPeriodId, int maxMovedLessons,
                        int timeBudgetMs) {
        return plan(sourceLessonId, targetPeriodId, maxMovedLessons, timeBudgetMs, 0);
    }

    private Result plan(long sourceLessonId, long targetPeriodId, int maxMovedLessons,
                        int timeBudgetMs, int nodeBudget) {
        Options options = new Options(
                maxMovedLessons, ExchangeRankingPreset.MIN_IMPACT, nodeBudget, timeBudgetMs);
        return DraftCyclePlanner.plan(
                validator.planningSnapshot(DRAFT_ID), sourceLessonId, targetPeriodId,
                options, validator::assess);
    }

    private static List<String> keys(Result result) {
        return result.candidates().stream().map(Candidate::stableKey).toList();
    }

    private void useLessons(DraftLesson... lessons) {
        when(draftLessons.findByDraftIdOrderByIdAsc(DRAFT_ID)).thenReturn(List.of(lessons));
    }

    private static DraftLesson lesson(long id, long courseId, long teacherId, long classId,
                                      Long periodId, Long roomId) {
        DraftLesson lesson = new DraftLesson();
        lesson.setId(id);
        lesson.setDraftId(DRAFT_ID);
        lesson.setAssignmentId(id);
        lesson.setLessonIndex(1);
        lesson.setCourseId(courseId);
        lesson.setTeacherId(teacherId);
        lesson.setAdminClassId(classId);
        lesson.setStudentCount(45);
        lesson.setPeriodId(periodId);
        lesson.setRoomId(roomId);
        return lesson;
    }

    private static DraftLesson lockedLesson(long id, long courseId, long teacherId, long classId,
                                            Long periodId, Long roomId) {
        DraftLesson lesson = lesson(id, courseId, teacherId, classId, periodId, roomId);
        lesson.setLocked(true);
        return lesson;
    }

    private static Period period(long id, int dayOfWeek, int periodNo) {
        Period period = new Period();
        period.setId(id);
        period.setSemesterId(SEMESTER_ID);
        period.setDayOfWeek(dayOfWeek);
        period.setPeriodNo(periodNo);
        period.setLabel("第" + periodNo + "节");
        period.setSection(PeriodSection.MORNING);
        return period;
    }

    private static Room room(long id, String name) {
        Room room = new Room();
        room.setId(id);
        room.setName(name);
        room.setCapacity(50);
        room.setType("NORMAL");
        return room;
    }

    private static Course course(long id, String name) {
        Course course = new Course();
        course.setId(id);
        course.setName(name);
        course.setSubject(name);
        return course;
    }

    private static Teacher teacher(long id, String name) {
        Teacher teacher = new Teacher();
        teacher.setId(id);
        teacher.setName(name);
        teacher.setSubject(name);
        return teacher;
    }

    private static AdminClass adminClass(long id, String name) {
        AdminClass adminClass = new AdminClass();
        adminClass.setId(id);
        adminClass.setSemesterId(SEMESTER_ID);
        adminClass.setGradeId(1L);
        adminClass.setName(name);
        adminClass.setStudentCount(45);
        return adminClass;
    }
}
