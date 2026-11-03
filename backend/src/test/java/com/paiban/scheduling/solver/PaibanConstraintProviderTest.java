package com.paiban.scheduling.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.ScoreManager;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.paiban.foundation.domain.PeriodSection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A source-draft lock is enforced at engine level, not only by the DTO/warm start. Moving
 * a locked lesson away from its period must cost an unconditional hard point.
 */
class PaibanConstraintProviderTest {

    private static final PeriodSlot MON1 = new PeriodSlot(1L, 1, 1, PeriodSection.MORNING, true, false, false);
    private static final PeriodSlot MON2 = new PeriodSlot(2L, 1, 2, PeriodSection.MORNING, false, false, false);
    private static final RoomSlot ROOM = new RoomSlot(1L, "301", "NORMAL", 50);

    @AfterEach
    void resetSettings() {
        ConstraintSettingsHolder.reset();
    }

    @Test
    void movingALockedLessonOffItsPeriodScoresHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson lesson = lesson(1L, MON2);
        lesson.setLocked(true);
        lesson.setLockedPeriodId(MON1.getId());

        TimetableSolution solution = solution(lesson);

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isEqualTo(-1);
    }

    @Test
    void keepingALockedLessonOnItsPeriodScoresZeroHard() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson lesson = lesson(1L, MON1);
        lesson.setLocked(true);
        lesson.setLockedPeriodId(MON1.getId());

        TimetableSolution solution = solution(lesson);

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
    }

    @Test
    void anUnlockedLessonMovesFreely() {
        ConstraintSettingsHolder.set(ConstraintSettings.defaults());
        Lesson lesson = lesson(1L, MON2);

        TimetableSolution solution = solution(lesson);

        scoreManager().updateScore(solution);

        assertThat(solution.getScore().hardScore()).isZero();
    }

    private static ScoreManager<TimetableSolution, HardSoftScore> scoreManager() {
        SolverConfig config = new SolverConfig()
                .withSolutionClass(TimetableSolution.class)
                .withEntityClasses(Lesson.class)
                .withConstraintProviderClass(PaibanConstraintProvider.class);
        return ScoreManager.create(SolverFactory.create(config));
    }

    private static TimetableSolution solution(Lesson lesson) {
        return new TimetableSolution(List.of(lesson), List.of(MON1, MON2), List.of(ROOM));
    }

    private static Lesson lesson(long id, PeriodSlot period) {
        Lesson lesson = new Lesson(id, 100L + id, 1000L + id, "课程", 10L, 500L,
                null, null, null, false, 40, 0);
        lesson.setPeriod(period);
        lesson.setRoom(ROOM);
        return lesson;
    }
}
