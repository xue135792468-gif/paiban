package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.ScoreManager;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.solver.ConstraintSettingsHolder;
import com.paiban.scheduling.solver.Lesson;
import com.paiban.scheduling.solver.PaibanConstraintProvider;
import com.paiban.scheduling.solver.PeriodSlot;
import com.paiban.scheduling.solver.TimetableSolution;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Engine-level tests: each preset constraint is scored through the public ScoreManager. */
class EngineConstraintTest {

    private static final PeriodSlot MON1 = new PeriodSlot(1L, 1, 1, true, false);
    private static final PeriodSlot MON2 = new PeriodSlot(2L, 1, 2, false, false);
    private static final PeriodSlot MON3 = new PeriodSlot(5L, 1, 3, false, false);
    private static final PeriodSlot MON4 = new PeriodSlot(7L, 1, 4, false, false);
    private static final PeriodSlot MON5 = new PeriodSlot(3L, 1, 5, false, false);
    private static final PeriodSlot EVEN1 = new PeriodSlot(4L, 1, 9, false, true);
    private static final PeriodSlot TUE1 = new PeriodSlot(8L, 2, 1, true, false);
    private static final PeriodSlot TUE2 = new PeriodSlot(9L, 2, 2, false, false);
    private static final PeriodSlot TUE3 = new PeriodSlot(10L, 2, 3, false, false);

    @AfterEach
    void resetSettings() {
        ConstraintSettingsHolder.reset();
    }

    private ScoreManager<TimetableSolution, HardSoftScore> scoreManager() {
        SolverConfig config = new SolverConfig()
                .withSolutionClass(TimetableSolution.class)
                .withEntityClasses(Lesson.class)
                .withConstraintProviderClass(PaibanConstraintProvider.class);
        return ScoreManager.create(SolverFactory.create(config));
    }

    private Lesson lesson(long id, long teacherId, long classId, long courseId, PeriodSlot period) {
        return lesson(id, teacherId, classId, courseId, period, null, false);
    }

    private Lesson lesson(long id, long teacherId, long classId, long courseId, PeriodSlot period,
                          Long headTeacherId, boolean allowConsecutive) {
        Lesson lesson = new Lesson(id, id, courseId, "课程" + courseId, teacherId, classId,
                headTeacherId, null, allowConsecutive, 40, 0);
        lesson.setPeriod(period);
        return lesson;
    }

    @Test
    void teacherConflictIsAHardViolation() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson a = lesson(1, 10, 100, 1000, MON1);
        Lesson b = lesson(2, 10, 200, 1001, MON1);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isEqualTo(-1);
    }

    @Test
    void noConflictScoresZeroHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson a = lesson(1, 10, 100, 1000, MON1);
        Lesson b = lesson(2, 11, 200, 1001, MON2);
        TimetableSolution solution = new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
    }

    @Test
    void disabledConstraintDoesNotScore() {
        com.paiban.scheduling.domain.ConstraintConfig disabled =
                config(ConstraintType.CLASS_CONFLICT, true, 100, Map.of());
        disabled.setEnabled(false);
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(disabled), new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson a = lesson(1, 10, 100, 1000, MON1);
        Lesson b = lesson(2, 11, 100, 1001, MON1);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
    }

    @Test
    void softConstraintProducesSoftPenalty() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_MAX_PER_DAY, false, 60, Map.of("maxPerDay", 1)));
        Lesson a = lesson(1, 10, 100, 1000, MON1);
        Lesson b = lesson(2, 10, 200, 1001, MON2);
        TimetableSolution solution = new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void classConflictIsHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson a = lesson(1, 10, 100, 1000, MON1);
        Lesson b = lesson(2, 11, 100, 1001, MON1);
        TimetableSolution solution = new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }





    @Test
    void anUnassignedLessonCostsMoreThanASingleConflict() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson hole = new Lesson(1L, 1L, 1000L, "数学", 10L, 100L, null, null, false, 40, 0);
        TimetableSolution holeSolution =
                new TimetableSolution(List.of(hole), List.of(MON1, MON2, MON5));
        Lesson first = lesson(2, 10, 100, 1000, MON1);
        Lesson second = lesson(3, 11, 100, 1001, MON1);
        TimetableSolution conflictSolution =
                new TimetableSolution(List.of(first, second), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(holeSolution);
        scoreManager().updateScore(conflictSolution);

        assertThat(holeSolution.getScore().hardScore())
                .isLessThan(conflictSolution.getScore().hardScore());
    }


    @Test
    void unassignedLessonIsPenalized() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson lesson = new Lesson(1L, 1L, 1000L, "数学", 10L, 100L, null, null, false, 40, 0);
        TimetableSolution solution = new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void teacherUnavailableSlotIsHard() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_UNAVAILABLE, true, 100,
                Map.of("teacherId", 10, "dayOfWeek", 1, "periodNo", 1)));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void sameCourseOncePerDayIsHard() {
        ConstraintSettingsHolder.set(settings(ConstraintType.SAME_COURSE_ONCE_PER_DAY, true, 100, Map.of()));
        Lesson a = lesson(1, 10, 100, 1000, MON1);
        Lesson b = lesson(2, 10, 100, 1000, MON2);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON2));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }


    @Test
    void teacherDayOffIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_DAY_OFF, true, 100,
                Map.of("teacherId", 10, "dayOfWeek", 1)));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void classMaxPerDayIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.CLASS_MAX_PER_DAY, false, 50, Map.of("maxPerDay", 1)));
        Lesson a = lesson(1, 10, 100, 1000, MON1);
        Lesson b = lesson(2, 11, 100, 1001, MON2);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON2));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void courseMaxPerDayIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.COURSE_MAX_PER_DAY, false, 50, Map.of("maxPerDay", 2)));
        Lesson a = lesson(1, 10, 100, 1000, MON1);
        Lesson b = lesson(2, 11, 100, 1000, MON2);
        Lesson c = lesson(3, 12, 100, 1000, MON3);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b, c), List.of(MON1, MON2, MON3));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void consecutiveLessonsNotMetIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.CONSECUTIVE_LESSONS, false, 60,
                Map.of("courseId", 1000, "minConsecutive", 2)));
        Lesson a = lesson(1, 10, 100, 1000, MON1, null, true);
        Lesson b = lesson(2, 11, 100, 1000, MON5, null, true);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void courseSpreadIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.COURSE_SPREAD, false, 50, Map.of()));
        Lesson a = lesson(1, 10, 100, 1000, MON1);
        Lesson b = lesson(2, 11, 100, 1000, MON2);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON2));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void mainSubjectAfterMorningIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.MAIN_SUBJECT_MORNING, false, 55,
                Map.of("courseId", 1000, "beforePeriodNo", 4)));
        Lesson lesson = lesson(1, 10, 100, 1000, MON5);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void teacherPreferredSlotIsRewarded() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_PREFERRED_SLOT, false, 40,
                Map.of("teacherId", 10, "dayOfWeek", 1, "periodNo", 1)));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isGreaterThan(0);
    }

    @Test
    void teacherMaxConsecutiveIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_MAX_CONSECUTIVE, false, 45,
                Map.of("maxConsecutive", 2)));
        Lesson a = lesson(1, 10, 100, 1000, MON1);
        Lesson b = lesson(2, 10, 200, 1001, MON2);
        Lesson c = lesson(3, 10, 300, 1002, MON3);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b, c), List.of(MON1, MON2, MON3));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void teacherNoFirstPeriodIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_NO_FIRST_PERIOD, false, 35,
                Map.of("teacherId", 10)));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void classGapIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.CLASS_GAP_MINIMIZED, false, 30, Map.of()));
        Lesson a = lesson(1, 10, 100, 1000, MON1);
        Lesson b = lesson(2, 11, 100, 1001, MON5);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), mondayOneToFive());

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    /**
     * 票03：空堂软评分按「真实存在的作息里、首末课程之间未占的每一格」计分。
     * 连续 1..5 节时，课在 1 与 3 只空出第 2 节（权重 40 → -40）；课在 1 与 5
     * 空出第 2、3、4 节（-120）。旧实现按节次编号跨度推算出空位却只扣一次，
     * 因此第二个断言在修复前必然失败。
     */
    @Test
    void classGapSoftScoreCountsEveryRealEmptyPeriod() {
        ConstraintSettingsHolder.set(settings(ConstraintType.CLASS_GAP_MINIMIZED, false, 40, Map.of()));
        Lesson early = lesson(1, 10, 100, 1000, MON1);
        Lesson mid = lesson(2, 11, 100, 1001, MON3);
        TimetableSolution oneGap =
                new TimetableSolution(List.of(early, mid), mondayOneToFive());

        scoreManager().updateScore(oneGap);

        assertThat(oneGap.getScore().hardScore()).isZero();
        assertThat(oneGap.getScore().softScore()).isEqualTo(-40);

        Lesson first = lesson(3, 12, 100, 1002, MON1);
        Lesson last = lesson(4, 13, 100, 1003, MON5);
        TimetableSolution threeGaps =
                new TimetableSolution(List.of(first, last), mondayOneToFive());

        scoreManager().updateScore(threeGaps);

        assertThat(threeGaps.getScore().hardScore()).isZero();
        assertThat(threeGaps.getScore().softScore()).isEqualTo(-120);
    }

    /**
     * 票03：作息里不存在的节次编号不能被编造成空堂。周一只有第 1、3 节时，
     * 首末之间没有任何真实课节，两侧都不该扣分。
     */
    @Test
    void classGapSoftScoreIgnoresPeriodNumbersTheSchoolDoesNotUse() {
        ConstraintSettingsHolder.set(settings(ConstraintType.CLASS_GAP_MINIMIZED, false, 40, Map.of()));
        Lesson first = lesson(1, 10, 100, 1000, MON1);
        Lesson third = lesson(2, 11, 100, 1001, MON3);
        TimetableSolution solution = new TimetableSolution(
                List.of(first, third), List.of(MON1, MON3));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isZero();
    }

    /** 票03：空堂数按班级/星期分别累计，跨班跨天逐格相加（4 格 × 40 = -160）。 */
    @Test
    void classGapSoftScoreSumsAcrossClassesAndDays() {
        ConstraintSettingsHolder.set(settings(ConstraintType.CLASS_GAP_MINIMIZED, false, 40, Map.of()));
        List<PeriodSlot> periods = List.of(
                MON1, MON2, MON3, MON4, MON5, TUE1, TUE2, TUE3);
        List<Lesson> lessons = List.of(
                lesson(1, 10, 100, 1000, MON1),
                lesson(2, 11, 100, 1001, MON3),
                lesson(3, 12, 200, 1002, MON1),
                lesson(4, 13, 200, 1003, MON3),
                lesson(5, 14, 200, 1004, MON5),
                lesson(6, 10, 100, 1000, TUE1),
                lesson(7, 11, 100, 1001, TUE3));
        TimetableSolution solution =
                new TimetableSolution(lessons, periods);

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isEqualTo(-160);
    }

    /** 票03：连续排满的当天没有空堂。 */
    @Test
    void classGapSoftScoreIsZeroForAPackedDay() {
        ConstraintSettingsHolder.set(settings(ConstraintType.CLASS_GAP_MINIMIZED, false, 40, Map.of()));
        TimetableSolution solution = new TimetableSolution(
                List.of(
                        lesson(1, 10, 100, 1000, MON1),
                        lesson(2, 11, 100, 1001, MON2),
                        lesson(3, 12, 100, 1002, MON3)),
                mondayOneToFive());

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isZero();
    }

    /** 票03：硬配置只在真实存在空堂时产生硬违规，未启用则完全不作用。 */
    @Test
    void classGapHardControlBlocksOnlyWhenARealGapExists() {
        ConstraintSettingsHolder.set(settings(ConstraintType.CLASS_GAP_MINIMIZED, true, 40, Map.of()));
        TimetableSolution withGap = new TimetableSolution(
                List.of(
                        lesson(1, 10, 100, 1000, MON1),
                        lesson(2, 11, 100, 1001, MON3)),
                mondayOneToFive());

        scoreManager().updateScore(withGap);

        assertThat(withGap.getScore().hardScore()).isLessThan(0);

        TimetableSolution noRealGap = new TimetableSolution(
                List.of(
                        lesson(1, 10, 100, 1000, MON1),
                        lesson(2, 11, 100, 1001, MON3)),
                List.of(MON1, MON3));

        scoreManager().updateScore(noRealGap);

        assertThat(noRealGap.getScore().hardScore()).isZero();
    }

    /** 票03：类型关闭时空堂既不阻断也不扣分。 */
    @Test
    void classGapSwitchOffScoresNothing() {
        com.paiban.scheduling.domain.ConstraintConfig disabled =
                config(ConstraintType.CLASS_GAP_MINIMIZED, true, 40, Map.of());
        disabled.setEnabled(false);
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(disabled), new com.fasterxml.jackson.databind.ObjectMapper()));
        TimetableSolution solution = new TimetableSolution(
                List.of(
                        lesson(1, 10, 100, 1000, MON1),
                        lesson(2, 11, 100, 1001, MON3)),
                mondayOneToFive());

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isZero();
    }

    /** 周一完整 1..5 节的作息事实，供空堂用例复用。 */
    private static List<PeriodSlot> mondayOneToFive() {
        return List.of(MON1, MON2, MON3, MON4, MON5);
    }

    @Test
    void sportLastPeriodIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.PE_LAST_PERIOD_AVOID, false, 35, Map.of()));
        Lesson sport = new Lesson(1L, 1L, 1000L, "体育", 10L, 100L, null, "体育", false, 40, 0);
        sport.setPeriod(EVEN1);
        TimetableSolution solution = new TimetableSolution(List.of(sport), List.of(MON1, EVEN1));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void multipleUnavailableEntriesAllScoreHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_UNAVAILABLE, true, 100, Map.of())),
                List.of(entry(ConstraintType.TEACHER_UNAVAILABLE,
                                Map.of("teacherId", 10, "dayOfWeek", 1, "periodNo", 1)),
                        entry(ConstraintType.TEACHER_UNAVAILABLE,
                                Map.of("teacherId", 10, "dayOfWeek", 1, "periodNo", 2))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson first = lesson(1, 10, 100, 1000, MON1);
        Lesson second = lesson(2, 10, 200, 1001, MON2);
        TimetableSolution solution = new TimetableSolution(
                List.of(first, second), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isEqualTo(-2);
    }

    @Test
    void anUnavailableEntryForAnotherTeacherDoesNotScore() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_UNAVAILABLE, true, 100, Map.of())),
                List.of(entry(ConstraintType.TEACHER_UNAVAILABLE,
                        Map.of("teacherId", 11, "dayOfWeek", 1, "periodNo", 1))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
    }

    @Test
    void anAllCoursesEntryOverlappingASpecificOneScoresOnce() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.SAME_COURSE_ONCE_PER_DAY, true, 100, Map.of())),
                List.of(entry(ConstraintType.SAME_COURSE_ONCE_PER_DAY, Map.of()),
                        entry(ConstraintType.SAME_COURSE_ONCE_PER_DAY, Map.of("courseId", 1000))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson first = lesson(1, 10, 100, 1000, MON1);
        Lesson second = lesson(2, 10, 100, 1000, MON2);
        TimetableSolution solution =
                new TimetableSolution(List.of(first, second), List.of(MON1, MON2));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isEqualTo(-1);
    }

    @Test
    void preferredSlotEntriesAreAnOrSetInTheEngine() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_PREFERRED_SLOT, false, 40, Map.of())),
                List.of(entry(ConstraintType.TEACHER_PREFERRED_SLOT,
                                Map.of("teacherId", 10, "dayOfWeek", 1, "periodNo", 1)),
                        entry(ConstraintType.TEACHER_PREFERRED_SLOT,
                                Map.of("teacherId", 10, "dayOfWeek", 1, "periodNo", 2))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        // Monday period 2 is the second entry: matching it is enough, the teacher does
        // not have to sit in every preferred slot at once.
        Lesson lesson = lesson(1, 10, 100, 1000, MON2);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isEqualTo(40);
    }

    /**
     * The engine resolves the limit per object, like every other evaluation path: the
     * global default covers the teachers it lists no exception for, and one teacher's
     * exception replaces it for that teacher only. Teacher 10 has an exception of 1 and
     * two Monday lessons (one violation), teacher 11 keeps the global 4 and is fine.
     */
    @Test
    void thresholdExceptionAppliesPerObjectInTheEngine() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_MAX_PER_DAY, false, 60, Map.of())),
                List.of(entry(ConstraintType.TEACHER_MAX_PER_DAY, Map.of("maxPerDay", 4)),
                        entry(ConstraintType.TEACHER_MAX_PER_DAY,
                                Map.of("teacherId", 10, "maxPerDay", 1))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson first = lesson(1, 10, 100, 1000, MON1);
        Lesson second = lesson(2, 10, 200, 1001, MON2);
        Lesson third = lesson(3, 11, 300, 1002, MON1);
        Lesson fourth = lesson(4, 11, 400, 1003, MON2);
        TimetableSolution solution = new TimetableSolution(
                List.of(first, second, third, fourth), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isEqualTo(-60);
    }

    /** An object nobody configured an exception for and no global default exists for is unlimited. */
    @Test
    void thresholdWithoutGlobalOrExceptionDoesNotLimitTheEngine() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_MAX_PER_DAY, false, 60, Map.of())),
                List.of(entry(ConstraintType.TEACHER_MAX_PER_DAY,
                        Map.of("teacherId", 99, "maxPerDay", 1))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson first = lesson(1, 10, 100, 1000, MON1);
        Lesson second = lesson(2, 10, 200, 1001, MON2);
        TimetableSolution solution = new TimetableSolution(
                List.of(first, second), List.of(MON1, MON2, MON5));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().softScore()).isZero();
    }

    @Test
    void customConstraintPluginsAreDiscovered() {
        assertThat(com.paiban.scheduling.solver.plugin.PluginConstraintLoader.load())
                .anyMatch(plugin -> plugin.name().equals("test-plugin"));
    }

    /**
     * Builds the snapshot the way the service does: for an entry-backed type the
     * parameters live in one entry row, so the engine reads exactly what a saved
     * configuration would produce (ticket 10/03 moved the catalogue to that shape).
     */
    private ConstraintSettings settings(
            ConstraintType type, boolean hard, int weight, Map<String, Object> params) {
        if (type.isEntryBacked()) {
            return ConstraintSettings.from(List.of(config(type, hard, weight, Map.of())),
                    List.of(entry(type, params)), new com.fasterxml.jackson.databind.ObjectMapper());
        }
        return ConstraintSettings.from(List.of(config(type, hard, weight, params)),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private com.paiban.scheduling.domain.ConstraintEntry entry(
            ConstraintType type, Map<String, Object> params) {        com.paiban.scheduling.domain.ConstraintEntry entry =
                new com.paiban.scheduling.domain.ConstraintEntry();
        entry.setType(type.name());
        entry.setCreatedAt(java.time.Instant.EPOCH);
        try {
            entry.setParamsJson(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return entry;
    }

    private com.paiban.scheduling.domain.ConstraintConfig config(
            ConstraintType type, boolean hard, int weight, Map<String, Object> params) {
        com.paiban.scheduling.domain.ConstraintConfig config = new com.paiban.scheduling.domain.ConstraintConfig();
        config.setType(type.name());
        config.setHard(hard);
        config.setWeight(weight);
        config.setEnabled(true);
        try {
            config.setParamsJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return config;
    }
}
