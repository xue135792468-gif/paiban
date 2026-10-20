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
        Lesson lesson = new Lesson(id, id, courseId, "课程" + courseId, teacherId, classId, null, null, null, false, 40, 0);
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
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.COURSE_REQUIRED_ROOM_TYPE, true, 100,
                        Map.of("courseId", 1000, "roomType", "PLAYGROUND"))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        TimetableSolution solution = new TimetableSolution(List.of(a), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void softConstraintProducesSoftPenalty() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_MAX_PER_DAY, false, 60, Map.of("maxPerDay", 1))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
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
    void unassignedLessonIsPenalized() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson lesson = new Lesson(1L, 1L, 1000L, "数学", 10L, 100L, null, null, null, false, 40, 0);
        TimetableSolution solution = new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void teacherUnavailableSlotIsHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_UNAVAILABLE, true, 100,
                        Map.of("teacherId", 10, "dayOfWeek", 1, "periodNo", 1))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void courseNotInEveningIsHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.COURSE_NOT_IN_EVENING, true, 100, Map.of("courseId", 1000))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson lesson = lesson(1, 10, 100, 1000, EVEN1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, EVEN1), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void sameCourseOncePerDayIsHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.SAME_COURSE_ONCE_PER_DAY, true, 100, Map.of())),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 10, 100, 1000, MON2, ROOM_B);
        TimetableSolution solution =
                new TimetableSolution(List.of(a, b), List.of(MON1, MON2), List.of(ROOM, ROOM_B));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void eveningMaxIsHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.EVENING_MAX, true, 100, Map.of("maxPerDay", 0))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson lesson = lesson(1, 10, 100, 1000, EVEN1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, EVEN1), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void teacherMeetingBlockedIsHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_MEETING_BLOCKED, true, 100,
                        Map.of("dayOfWeek", 1, "periodNo", 1))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
    }

    @Test
    void sportFirstAfternoonIsPenalized() {
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.SPORT_NOT_FIRST_AFTERNOON, true, 100, Map.of())),
                new com.fasterxml.jackson.databind.ObjectMapper()));
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
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.TEACHER_DAY_OFF, true, 100,
                        Map.of("teacherId", 10, "dayOfWeek", 1))),
                new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson lesson = lesson(1, 10, 100, 1000, MON1, ROOM);
        TimetableSolution solution =
                new TimetableSolution(List.of(lesson), List.of(MON1, MON2), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
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
