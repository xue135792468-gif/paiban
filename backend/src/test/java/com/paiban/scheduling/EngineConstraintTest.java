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
    private static final RoomSlot ROOM = new RoomSlot(1L, "301", "NORMAL", 50);
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
        Lesson b = lesson(2, 10, 200, 1001, MON1, ROOM);
        TimetableSolution solution = new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5), List.of(ROOM));

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isLessThan(0);
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
        ConstraintSettingsHolder.set(ConstraintSettings.from(
                List.of(config(ConstraintType.CLASS_CONFLICT, true, 100, Map.of())), new com.fasterxml.jackson.databind.ObjectMapper()));
        Lesson a = lesson(1, 10, 100, 1000, MON1, ROOM);
        Lesson b = lesson(2, 10, 200, 1001, MON1, ROOM);
        TimetableSolution solution = new TimetableSolution(List.of(a, b), List.of(MON1, MON2, MON5), List.of(ROOM));

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
