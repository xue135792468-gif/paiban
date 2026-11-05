package com.paiban.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.ScoreManager;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.scheduling.constraint.ConstraintChecker;
import com.paiban.scheduling.constraint.ConstraintViolation;
import com.paiban.scheduling.constraint.LessonView;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.solver.ConstraintSettingsHolder;
import com.paiban.scheduling.solver.Lesson;
import com.paiban.scheduling.solver.PaibanConstraintProvider;
import com.paiban.scheduling.solver.PeriodSlot;
import com.paiban.scheduling.solver.TimetableSolution;
import com.paiban.substitution.plan.PlanFixtures;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.substitution.plan.PlanSnapshot;
import com.paiban.substitution.plan.PlanVerifier;
import com.paiban.common.policy.ExchangeRankingPreset;
import com.paiban.substitution.plan.SwapPlanner;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The rule has the same meaning in scoring, validation and dated plan verification. */
class WeeklyNoClassEngineTest {
    @AfterEach void reset() { ConstraintSettingsHolder.reset(); }

    @Test
    void oneClassIsBlockedWithoutBanningItsTeacherFromAnotherClass() throws Exception {
        PlanFixtures.Builder blocked = base(settings(false));
        blocked.lesson(1, 1, 1, 1L, 1, 2);
        PlanSnapshot a = blocked.build();
        assertThat(score(a).hardScore()).isNegative();
        assertThat(ConstraintChecker.checkHard(a.context(), views(a)))
                .extracting(ConstraintViolation::code).containsExactly("CLASS_UNAVAILABLE");
        PlanSnapshot b = base(settings(false)).lesson(2, 1, 1, 1L, 1, 2).build();
        assertThat(score(b).hardScore()).isZero();
        assertThat(ConstraintChecker.checkHard(b.context(), views(b))).isEmpty();
    }

    @Test
    void intentionalEmptySlotDoesNotCostSoftGapPointsButAnotherClassStillDoes() throws Exception {
        PlanSnapshot snapshot = base(settings(false))
                .lesson(1, 1, 1, 1L, 1, 1).lesson(1, 2, 2, 1L, 1, 3)
                .lesson(2, 1, 3, 2L, 1, 1).lesson(2, 2, 4, 2L, 1, 3).build();
        assertThat(score(snapshot)).isEqualTo(HardSoftScore.of(0, -40));
        assertThat(ConstraintChecker.softPenalty(snapshot.context(), views(snapshot))).isEqualTo(40);
    }

    @Test
    void intentionalEmptySlotAlsoSatisfiesAHardNoGapRequirement() throws Exception {
        PlanSnapshot snapshot = base(settings(true))
                .lesson(1, 1, 1, 1L, 1, 1).lesson(1, 2, 2, 1L, 1, 3).build();
        assertThat(score(snapshot).hardScore()).isZero();
        assertThat(ConstraintChecker.checkHard(snapshot.context(), views(snapshot))).isEmpty();
    }

    @Test
    void searchAndIndependentVerifierRejectMovingIntoTheUnavailableSlot() throws Exception {
        PlanFixtures.Builder builder = base(settings(false));
        long a = builder.addLesson(1, 1, 1, 1, 1);
        long b = builder.addLesson(1, 2, 2, 1, 2);
        PlanSnapshot snapshot = builder.build();
        var result = SwapPlanner.plan(snapshot, new SwapPlanner.Request(1, a,
                PlanFixtures.MONDAY, 102), SwapPlanner.Options.of(1, 2, ExchangeRankingPreset.MIN_IMPACT));
        assertThat(result.candidates()).isEmpty();
        assertThat(result.diagnoses()).extracting(d -> d.code()).contains("CLASS_UNAVAILABLE");
        var moves = List.of(
                new PlanMove(a, 1, 1, 1, 101, 1, 1, PlanFixtures.MONDAY,
                        102, 1, 2, PlanFixtures.MONDAY, 0),
                new PlanMove(b, 1, 2, 2, 102, 1, 2, PlanFixtures.MONDAY,
                        101, 1, 1, PlanFixtures.MONDAY, 1));
        var verification = PlanVerifier.verify(snapshot, moves, PlanVerifier.Kind.SWAP, -1);
        assertThat(verification.passed()).isFalse();
        assertThat(verification.issues()).extracting(PlanVerifier.Issue::code).contains("CLASS_UNAVAILABLE");
    }

    private static ConstraintSettings settings(boolean hardGap) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ConstraintConfig ban = config("CLASS_UNAVAILABLE", true);
        ConstraintConfig gap = config("CLASS_GAP_MINIMIZED", hardGap);
        ConstraintEntry entry = new ConstraintEntry();
        entry.setType("CLASS_UNAVAILABLE");
        entry.setEnabled(true);
        entry.setCreatedAt(Instant.EPOCH);
        entry.setParamsJson(mapper.writeValueAsString(Map.of("classId", 1, "dayOfWeek", 1, "periodNo", 2)));
        return ConstraintSettings.from(List.of(ban, gap), List.of(entry), mapper);
    }

    private static ConstraintConfig config(String type, boolean hard) {
        ConstraintConfig config = new ConstraintConfig();
        config.setType(type);
        config.setEnabled(true);
        config.setHard(hard);
        config.setWeight(40);
        config.setParamsJson("{}");
        return config;
    }

    private static PlanFixtures.Builder base(ConstraintSettings settings) {
        PlanFixtures.Builder builder = PlanFixtures.builder().days(1).periodsPerDay(3).settings(settings)
                .adminClass(1, "A班", 40, null).adminClass(2, "B班", 40, null)
                .course(1, "C1", null).course(2, "C2", null);
        for (int id = 1; id <= 4; id++) builder.teacher(id, "T" + id, "S");
        return builder;
    }

    private static List<LessonView> views(PlanSnapshot snapshot) {
        return snapshot.baseline().stream().map(row -> new LessonView(row.entryId(), row.classId(),
                row.courseId(), row.teacherId(), row.periodId(), 40)).toList();
    }

    private static HardSoftScore score(PlanSnapshot snapshot) {
        ConstraintSettingsHolder.set(snapshot.settings());
        Map<Long, PeriodSlot> periods = new HashMap<>();
        snapshot.periods().values().forEach(p -> periods.put(p.id(), new PeriodSlot(p.id(), p.dayOfWeek(),
                p.periodNo(), p.periodNo() == 1, p.periodNo() == 3)));
        List<Lesson> lessons = snapshot.baseline().stream().map(row -> {
            Lesson lesson = new Lesson(row.entryId(), row.entryId(), row.courseId(), "C" + row.courseId(),
                    row.teacherId(), row.classId(), null, "S", false, 40, 0);
            lesson.setPeriod(periods.get(row.periodId()));
            return lesson;
        }).toList();
        TimetableSolution solution = new TimetableSolution(lessons, List.copyOf(periods.values()));
        var config = new SolverConfig().withSolutionClass(TimetableSolution.class)
                .withEntityClasses(Lesson.class).withConstraintProviderClass(PaibanConstraintProvider.class);
        ScoreManager.create(SolverFactory.create(config)).updateScore(solution);
        return solution.getScore();
    }
}
