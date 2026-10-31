package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.ScoreManager;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.paiban.foundation.domain.PeriodSection;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.solver.ConstraintSettingsHolder;
import com.paiban.scheduling.solver.Lesson;
import com.paiban.scheduling.solver.PaibanConstraintProvider;
import com.paiban.scheduling.solver.PeriodSlot;
import com.paiban.scheduling.solver.RoomSlot;
import com.paiban.scheduling.solver.TimetableSolution;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Engine-level tests: each preset constraint is scored through the public ScoreManager. */
class EngineConstraintTest {

    private static final PeriodSlot MON1 = new PeriodSlot(1L, 1, 1, PeriodSection.MORNING, true, false, false);
    private static final PeriodSlot MON2 = new PeriodSlot(2L, 1, 2, PeriodSection.MORNING, false, false, false);
    private static final PeriodSlot MON3 = new PeriodSlot(5L, 1, 3, PeriodSection.MORNING, false, false, false);
    private static final PeriodSlot MON5 = new PeriodSlot(3L, 1, 5, PeriodSection.AFTERNOON, false, false, true);
    private static final PeriodSlot EVEN1 = new PeriodSlot(4L, 1, 9, PeriodSection.EVENING, false, true, false);
    private static final RoomSlot ROOM = new RoomSlot(1L, "301", "NORMAL", 50);
    private static final RoomSlot ROOM_B = new RoomSlot(3L, "303", "NORMAL", 50);
    private static final RoomSlot SMALL_ROOM = new RoomSlot(2L, "302", "NORMAL", 20);

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

    private Lesson lesson(long id, long teacherId, long classId, long courseId, PeriodSlot period, RoomSlot room) {
        return lesson(id, teacherId, classId, courseId, period, room, null, false);
    }

    private Lesson lesson(long id, long teacherId, long classId, long courseId, PeriodSlot period, RoomSlot room,
                          Long headTeacherId, boolean allowConsecutive) {
        Lesson lesson = new Lesson(id, id, courseId, "课程" + courseId, teacherId, classId,
                headTeacherId, null, null, allowConsecutive, 40, 0);
        lesson.setPeriod(period);
        lesson.setRoom(room);
        return lesson;
    }

    @Test
    void teacherConflictIsAHardViolation() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 10, 200, 1001, MON1, ROOM_B);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5), List.of(ROOM, ROOM_B));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isEqualTo(-1);
    }

    @Test
    void noConflictScoresZeroHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 11, 200, 1001, MON2, ROOM);
        TimetableSolution solution = new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
    }

    @Test
    void roomCapacityViolationIsHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson a = lesson(1, 10, 100, 1000, MON1, SMALL_ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(a), List.of(MON1, MON2, MON5), List.of(SMALL_ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void disabledConstraintDoesNotScore() {
        com.paiban.scheduling.domain.ConstraintConfig disabled =
                config(ConstraintType.CLASS_CONFLICT, true, 100, Map.of());
        disabled.setEnabled(false);
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(disabled), new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 11, 100, 1001, MON1, ROOM_B);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5), List.of(ROOM, ROOM_B));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
    }

    @Test
    void courseRequiredRoomTypeIsHard() {
        ConstraintSettingsHolder.set(settings(ConstraintType.COURSE_REQUIRED_ROOM_TYPE, true, 100,
                Map.of("courseId", 1000, "roomType", "PLAYGROUND")));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        TimetableSolution solution = new TimetableSolution(List.of(a), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void softConstraintProducesSoftPenalty() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_MAX_PER_DAY, false, 60, Map.of("maxPerDay", 1)));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 10, 200, 1001, MON2, ROOM);
        TimetableSolution solution = new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void classConflictIsHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 11, 100, 1001, MON1, null);
        TimetableSolution solution = new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void roomConflictIsHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 11, 200, 1001, MON1, ROOM);
        TimetableSolution solution = new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void courseMetadataRoomTypeIsHardEvenWithoutConstraintConfig() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson lesson = new Lesson(1L, 1L, 1000L, "体育", 10L, 100L, null, "体育", "PLAYGROUND", false, 40, 0);
        lesson.setPeriod(MON1);
        lesson.setRoom(ROOM);
        TimetableSolution solution = new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void fixedRoomKeepsTheClassInItsOwnClassroom() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson lesson = new Lesson(1L, 1L, 1000L, "数学", 10L, 100L, null, null, null, false, 40, 0);
        lesson.setPeriod(MON1);
        lesson.setRoom(ROOM_B);
        lesson.setFixedRoom(ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5), List.of(ROOM, ROOM_B));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isEqualTo(-1);
    }

    @Test
    void fixedRoomIsSatisfiedByTheClassroomItself() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson lesson = new Lesson(1L, 1L, 1000L, "数学", 10L, 100L, null, null, null, false, 40, 0);
        lesson.setPeriod(MON1);
        lesson.setRoom(ROOM);
        lesson.setFixedRoom(ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
    }

    @Test
    void fixedRoomDoesNotFightASpecialRoomRequirement() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson lesson = new Lesson(1L, 1L, 1000L, "体育", 10L, 100L, null, "体育", "NORMAL", false, 40, 0);
        lesson.setPeriod(MON1);
        lesson.setRoom(ROOM);
        lesson.setFixedRoom(ROOM_B);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5), List.of(ROOM, ROOM_B));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
    }

    @Test
    void unassignedLessonIsPenalized() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson lesson = new Lesson(1L, 1L, 1000L, "数学", 10L, 100L, null, null, null, false, 40, 0);
        TimetableSolution solution = new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void teacherUnavailableSlotIsHard() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_UNAVAILABLE, true, 100,
                Map.of("teacherId", 10, "dayOfWeek", 1, "periodNo", 1)));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void courseNotInEveningIsHard() {
        ConstraintSettingsHolder.set(settings(ConstraintType.COURSE_NOT_IN_EVENING, true, 100, Map.of("courseId", 1000)));
        Lesson lesson = lesson(1, 10, 100, 1000, EVEN1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, EVEN1), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void sameCourseOncePerDayIsHard() {
        ConstraintSettingsHolder.set(settings(ConstraintType.SAME_COURSE_ONCE_PER_DAY, true, 100, Map.of()));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 10, 100, 1000, MON2, ROOM_B);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON2), List.of(ROOM, ROOM_B));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void eveningMaxIsHard() {
        ConstraintSettingsHolder.set(settings(ConstraintType.EVENING_MAX, true, 100, Map.of("maxPerDay", 0)));
        Lesson lesson = lesson(1, 10, 100, 1000, EVEN1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, EVEN1), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void teacherMeetingBlockedIsHard() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_MEETING_BLOCKED, true, 100,
                Map.of("dayOfWeek", 1, "periodNo", 1)));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void sportFirstAfternoonIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.SPORT_NOT_FIRST_AFTERNOON, true, 100, Map.of()));
        Lesson lesson = new Lesson(1L, 1L, 1000L, "体育", 10L, 100L, null, "体育", null, false, 40, 0);
        lesson.setPeriod(MON5);
        lesson.setRoom(ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void teacherDayOffIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_DAY_OFF, true, 100,
                Map.of("teacherId", 10, "dayOfWeek", 1)));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void classMaxPerDayIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.CLASS_MAX_PER_DAY, false, 50, Map.of("maxPerDay", 1)));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 11, 100, 1001, MON2, ROOM_B);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON2), List.of(ROOM, ROOM_B));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void courseMaxPerDayIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.COURSE_MAX_PER_DAY, false, 50, Map.of("maxPerDay", 2)));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 11, 100, 1000, MON2, ROOM_B);
        Lesson c = lesson(3, 12, 100, 1000, MON3, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b, c), List.of(MON1, MON2, MON3), List.of(ROOM, ROOM_B));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void consecutiveLessonsNotMetIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.CONSECUTIVE_LESSONS, false, 60,
                Map.of("courseId", 1000, "minConsecutive", 2)));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM, null, true);
        Lesson b = lesson(2, 11, 100, 1000, MON5, ROOM_B, null, true);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON5), List.of(ROOM, ROOM_B));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void courseSpreadIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.COURSE_SPREAD, false, 50, Map.of()));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 11, 100, 1000, MON2, ROOM_B);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON2), List.of(ROOM, ROOM_B));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void mainSubjectAfterMorningIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.MAIN_SUBJECT_MORNING, false, 55,
                Map.of("courseId", 1000, "beforePeriodNo", 4)));
        Lesson lesson = lesson(1, 10, 100, 1000, MON5, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void teacherPreferredSlotIsRewarded() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_PREFERRED_SLOT, false, 40,
                Map.of("teacherId", 10, "dayOfWeek", 1, "periodNo", 1)));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isGreaterThan(0);
    }

    @Test
    void teacherMaxConsecutiveIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_MAX_CONSECUTIVE, false, 45,
                Map.of("maxConsecutive", 2)));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 10, 200, 1001, MON2, ROOM);
        Lesson c = lesson(3, 10, 300, 1002, MON3, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b, c), List.of(MON1, MON2, MON3), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void teacherNoFirstPeriodIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.TEACHER_NO_FIRST_PERIOD, false, 35,
                Map.of("teacherId", 10)));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void classGapIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.CLASS_GAP_MINIMIZED, false, 30, Map.of()));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 11, 100, 1001, MON5, ROOM_B);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON5), List.of(ROOM, ROOM_B));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void sportLastPeriodIsPenalized() {
        ConstraintSettingsHolder.set(settings(ConstraintType.PE_LAST_PERIOD_AVOID, false, 35, Map.of()));
        Lesson sport = new Lesson(1L, 1L, 1000L, "体育", 10L, 100L, null, "体育", null, false, 40, 0);
        sport.setPeriod(EVEN1);
        sport.setRoom(ROOM);
        TimetableSolution solution = new TimetableSolution(List.of(sport), List.of(MON1, EVEN1), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isLessThan(0);
    }

    @Test
    void headTeacherFirstPeriodIsRewarded() {
        ConstraintSettingsHolder.set(settings(ConstraintType.HEAD_TEACHER_FIRST_PERIOD, false, 20, Map.of()));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1, ROOM, 10L, false);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getScore().softScore()).isGreaterThan(0);
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
        Lesson first = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson second = lesson(2, 10, 200, 1001, MON2, ROOM_B);
        TimetableSolution solution = new TimetableSolution(
                List.of(first, second), List.of(MON1, MON2, MON5), List.of(ROOM, ROOM_B));

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
        Lesson lesson = lesson(1, 10, 100, 1000, MON1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
    }

    @Test
    void twoCourseTargetEntriesBothScoreHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.COURSE_NOT_IN_EVENING, true, 100, Map.of())),
                List.of(entry(ConstraintType.COURSE_NOT_IN_EVENING, Map.of("courseId", 1000)),
                        entry(ConstraintType.COURSE_NOT_IN_EVENING, Map.of("courseId", 1001))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson first = lesson(1, 10, 100, 1000, EVEN1, ROOM);
        Lesson second = lesson(2, 11, 200, 1001, EVEN1, ROOM_B);
        TimetableSolution solution =
                new TimetableSolution(List.of(first, second), List.of(MON1, EVEN1), List.of(ROOM, ROOM_B));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isEqualTo(-2);
    }

    @Test
    void anAllCoursesEntryOverlappingASpecificOneScoresOnce() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.SAME_COURSE_ONCE_PER_DAY, true, 100, Map.of())),
                List.of(entry(ConstraintType.SAME_COURSE_ONCE_PER_DAY, Map.of()),
                        entry(ConstraintType.SAME_COURSE_ONCE_PER_DAY, Map.of("courseId", 1000))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson first = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson second = lesson(2, 10, 100, 1000, MON2, ROOM_B);
        TimetableSolution solution =
                new TimetableSolution(List.of(first, second), List.of(MON1, MON2), List.of(ROOM, ROOM_B));

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
        Lesson lesson = lesson(1, 10, 100, 1000, MON2, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5), List.of(ROOM));

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
        Lesson first = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson second = lesson(2, 10, 200, 1001, MON2, ROOM_B);
        Lesson third = lesson(3, 11, 300, 1002, MON1, ROOM);
        Lesson fourth = lesson(4, 11, 400, 1003, MON2, ROOM_B);
        TimetableSolution solution = new TimetableSolution(
                List.of(first, second, third, fourth), List.of(MON1, MON2, MON5),
                List.of(ROOM, ROOM_B));

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
        Lesson first = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson second = lesson(2, 10, 200, 1001, MON2, ROOM_B);
        TimetableSolution solution = new TimetableSolution(
                List.of(first, second), List.of(MON1, MON2, MON5), List.of(ROOM, ROOM_B));

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
