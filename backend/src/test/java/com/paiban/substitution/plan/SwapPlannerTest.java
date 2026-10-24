package com.paiban.substitution.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SwapPlannerTest {

    private static final long CLASS_1 = PlanFixtures.CLASS_1;
    private static final long CLASS_2 = PlanFixtures.CLASS_2;

    @Test
    void findsATwoTeacherSameDayCycle() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        long second = builder.addLesson(CLASS_1, 2, 2, 2L, 1, 2);
        PlanSnapshot snapshot = builder.build();

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(1, 2)),
                SwapPlanner.Options.defaults());

        assertThat(result.candidates()).isNotEmpty();
        CandidatePlan plan = result.candidates().get(0);
        assertThat(plan.cycleLength()).isEqualTo(2);
        assertThat(plan.impactDays()).isEqualTo(1);
        assertThat(plan.impactTeachers()).isEqualTo(2);
        assertThat(plan.moves()).extracting(PlanMove::entryId).containsExactlyInAnyOrder(first, second);
        assertThat(plan.moves()).anySatisfy(move -> {
            if (move.entryId() == first) {
                assertThat(move.toPeriodId()).isEqualTo(PlanFixtures.periodId(1, 2));
            }
        });
    }

    @Test
    void escalatesToAThreeTeacherCycleWhenTheDirectPathIsBlocked() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        long second = builder.addLesson(CLASS_1, 2, 2, 2L, 1, 2);
        long third = builder.addLesson(CLASS_1, 3, 3, 3L, 1, 3);
        // Teacher 2 is already teaching class 2 at Monday period 1, so they cannot
        // take the source slot; the cycle has to bring teacher 3 in.
        builder.lesson(CLASS_2, 4, 2, 4L, 1, 1);
        PlanSnapshot snapshot = builder.build();

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(1, 2)),
                SwapPlanner.Options.defaults());

        assertThat(result.candidates()).isNotEmpty();
        CandidatePlan plan = result.candidates().get(0);
        assertThat(plan.cycleLength()).isEqualTo(3);
        assertThat(plan.impactTeachers()).isEqualTo(3);
        assertThat(plan.moves()).extracting(PlanMove::entryId)
                .containsExactlyInAnyOrder(first, second, third);
    }

    @Test
    void explainsWhyNoCycleExists() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        builder.lesson(CLASS_1, 2, 2, 2L, 1, 2);
        builder.lesson(CLASS_1, 3, 3, 3L, 1, 3);
        builder.lesson(CLASS_2, 4, 2, 4L, 1, 1);
        builder.lesson(CLASS_2, 5, 2, 5L, 1, 3);
        builder.lesson(CLASS_2, 6, 3, 6L, 1, 1);
        PlanSnapshot snapshot = builder.build();

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(1, 2)),
                SwapPlanner.Options.defaults());

        assertThat(result.candidates()).isEmpty();
        assertThat(result.diagnoses()).extracting(PlanDiagnosis::code)
                .contains("TEACHER_BUSY");
        assertThat(result.diagnoses()).extracting(PlanDiagnosis::code).contains("NO_SOLUTION");
    }

    @Test
    void findsACrossDayCycleAndKeepsEachDaysLessonCount() {
        PlanFixtures.Builder builder = base();
        long fridayFirst = builder.addLesson(CLASS_1, 1, 1, 1L, 5, 1);
        long fridaySecond = builder.addLesson(CLASS_1, 2, 2, 2L, 5, 2);
        long mondaySecond = builder.addLesson(CLASS_1, 3, 3, 3L, 1, 2);
        // Teacher 2 cannot take Friday period 1, so the cycle has to use next Monday.
        builder.lesson(CLASS_2, 4, 2, 4L, 5, 1);
        PlanSnapshot snapshot = builder.build();
        java.time.LocalDate friday = PlanFixtures.date(5);

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, fridayFirst, friday, PlanFixtures.periodId(5, 2)),
                SwapPlanner.Options.of(7, 6, RankingPreset.MIN_IMPACT));

        assertThat(result.candidates()).isNotEmpty();
        CandidatePlan plan = result.candidates().get(0);
        assertThat(plan.cycleLength()).isEqualTo(3);
        assertThat(plan.impactDays()).isEqualTo(2);
        assertThat(plan.sameWeek()).isFalse();
        assertThat(plan.moves()).extracting(PlanMove::entryId)
                .containsExactlyInAnyOrder(fridayFirst, fridaySecond, mondaySecond);
        assertThat(plan.moves()).anySatisfy(move -> {
            if (move.entryId() == mondaySecond) {
                assertThat(move.toDate()).isEqualTo(friday);
                assertThat(move.fromDate()).isEqualTo(PlanFixtures.date(1).plusDays(7));
            }
        });
    }

    @Test
    void rejectsCrossDayCyclesOutsideTheWindow() {
        PlanFixtures.Builder builder = base();
        long fridayFirst = builder.addLesson(CLASS_1, 1, 1, 1L, 5, 1);
        builder.lesson(CLASS_1, 2, 2, 2L, 5, 2);
        builder.lesson(CLASS_1, 3, 3, 3L, 1, 2);
        builder.lesson(CLASS_2, 4, 2, 4L, 5, 1);
        PlanSnapshot snapshot = builder.build();

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, fridayFirst, PlanFixtures.date(5),
                        PlanFixtures.periodId(5, 2)),
                SwapPlanner.Options.of(2, 6, RankingPreset.MIN_IMPACT));

        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void respectsTheTeacherCap() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        builder.lesson(CLASS_1, 2, 2, 2L, 1, 2);
        builder.lesson(CLASS_1, 3, 3, 3L, 1, 3);
        builder.lesson(CLASS_2, 4, 2, 4L, 1, 1);
        PlanSnapshot snapshot = builder.build();

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(1, 2)),
                SwapPlanner.Options.of(7, 2, RankingPreset.MIN_IMPACT));

        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void presetsOrderTheSameCandidatesDifferently() {
        CandidatePlan sameDayThreeTeachers = new CandidatePlan(List.of(), 1, 3, 0, 3, true);
        CandidatePlan crossDayTwoTeachers = new CandidatePlan(List.of(), 2, 2, 0, 2, false);

        assertThat(List.of(sameDayThreeTeachers, crossDayTwoTeachers).stream()
                .sorted(RankingPreset.MIN_IMPACT.comparator()).toList())
                .containsExactly(sameDayThreeTeachers, crossDayTwoTeachers);
        assertThat(List.of(sameDayThreeTeachers, crossDayTwoTeachers).stream()
                .sorted(RankingPreset.MIN_TEACHERS.comparator()).toList())
                .containsExactly(crossDayTwoTeachers, sameDayThreeTeachers);
        assertThat(List.of(sameDayThreeTeachers, crossDayTwoTeachers).stream()
                .sorted(RankingPreset.STABLE_TIMETABLE.comparator()).toList())
                .containsExactly(sameDayThreeTeachers, crossDayTwoTeachers);
    }

    private static PlanFixtures.Builder base() {
        PlanFixtures.Builder builder = PlanFixtures.builder()
                .adminClass(CLASS_1, "高一1班", 45, null)
                .adminClass(CLASS_2, "高一2班", 45, null);
        for (long teacher = 1; teacher <= 10; teacher++) {
            builder.teacher(teacher, "老师" + teacher, "科目" + teacher);
            builder.course(teacher, "课程" + teacher, null);
            builder.room(teacher, "教室" + teacher, "NORMAL", 50);
        }
        return builder;
    }
}
