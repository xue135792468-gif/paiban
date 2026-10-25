package com.paiban.ai.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.ai.imports.PeriodGapPlanner.Existing;
import com.paiban.ai.imports.PeriodGapPlanner.Plan;
import com.paiban.ai.imports.PeriodGapPlanner.Slot;
import com.paiban.ai.imports.PeriodGapPlanner.Status;
import com.paiban.foundation.domain.PeriodSection;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pure half of ticket 08: given the periods a semester already has and the
 * (day, periodNo) slots a workbook references but the semester lacks, either propose a
 * time it can defend from neighbouring periods, or say why a human has to decide.
 * No database, no clock, only {@link LocalTime} arithmetic.
 */
class PeriodGapPlannerTest {

    private static Existing period(int day, int no, String label, PeriodSection section, String start, String end) {
        return new Existing(day, no, label, section, LocalTime.parse(start), LocalTime.parse(end));
    }

    private static Existing morning(int day, int no, String start, String end) {
        return period(day, no, "上午第" + no + "节", PeriodSection.MORNING, start, end);
    }

    private static Existing afternoon(int day, int no, String start, String end) {
        return period(day, no, "下午第" + (no - 4) + "节", PeriodSection.AFTERNOON, start, end);
    }

    /** The 8 periods 「一键生成默认作息」 writes for one day. */
    private static List<Existing> defaultDay(int day) {
        List<Existing> day8 = new ArrayList<>();
        day8.add(morning(day, 1, "08:00", "08:45"));
        day8.add(morning(day, 2, "08:55", "09:40"));
        day8.add(morning(day, 3, "09:50", "10:35"));
        day8.add(morning(day, 4, "10:45", "11:30"));
        day8.add(afternoon(day, 5, "14:00", "14:45"));
        day8.add(afternoon(day, 6, "14:55", "15:40"));
        day8.add(afternoon(day, 7, "15:50", "16:35"));
        day8.add(afternoon(day, 8, "16:45", "17:30"));
        return day8;
    }

    private static Plan only(List<Plan> plans) {
        assertThat(plans).hasSize(1);
        return plans.get(0);
    }

    @Test
    void fillsAMiddleGapUsingTheNeighbouringDurationAndSpacing() {
        List<Existing> existing = List.of(
                morning(1, 1, "08:00", "08:45"),
                morning(1, 2, "08:55", "09:40"),
                morning(1, 4, "10:45", "11:30"));

        Plan plan = only(PeriodGapPlanner.plan(existing, List.of(new Slot(1, 3))));

        assertThat(plan.status()).isEqualTo(Status.PROPOSED);
        assertThat(plan.label()).isEqualTo("第3节");
        assertThat(plan.section()).isEqualTo(PeriodSection.MORNING);
        assertThat(plan.startTime()).isEqualTo(LocalTime.of(9, 50));
        assertThat(plan.endTime()).isEqualTo(LocalTime.of(10, 35));
        assertThat(plan.reason()).isNull();
    }

    @Test
    void appendsAfterTheLastPeriodOfTheDay() {
        List<Existing> existing = defaultDay(1);

        List<Plan> plans = PeriodGapPlanner.plan(
                existing, List.of(new Slot(1, 9), new Slot(1, 10)));

        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).status()).isEqualTo(Status.PROPOSED);
        assertThat(plans.get(0).startTime()).isEqualTo(LocalTime.of(17, 40));
        assertThat(plans.get(0).endTime()).isEqualTo(LocalTime.of(18, 25));
        assertThat(plans.get(0).section()).isEqualTo(PeriodSection.AFTERNOON);
        assertThat(plans.get(1).startTime()).isEqualTo(LocalTime.of(18, 35));
        assertThat(plans.get(1).endTime()).isEqualTo(LocalTime.of(19, 20));
    }

    @Test
    void usesAnotherDaysSamePeriodWhenTheDayHasNoPeriods() {
        List<Existing> existing = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            existing.add(afternoon(day, 5, "14:00", "14:45"));
        }

        Plan plan = only(PeriodGapPlanner.plan(existing, List.of(new Slot(6, 5))));

        assertThat(plan.status()).isEqualTo(Status.PROPOSED);
        assertThat(plan.section()).isEqualTo(PeriodSection.AFTERNOON);
        assertThat(plan.startTime()).isEqualTo(LocalTime.of(14, 0));
        assertThat(plan.endTime()).isEqualTo(LocalTime.of(14, 45));
    }

    @Test
    void otherDaysThatDisagreeBecomeManual() {
        List<Existing> existing = List.of(
                afternoon(1, 5, "14:00", "14:45"),
                afternoon(2, 5, "15:00", "15:45"));

        Plan plan = only(PeriodGapPlanner.plan(existing, List.of(new Slot(6, 5))));

        assertThat(plan.status()).isEqualTo(Status.MANUAL);
        assertThat(plan.reason()).contains("不一致");
        assertThat(plan.startTime()).isNull();
        assertThat(plan.endTime()).isNull();
    }

    @Test
    void noReferenceAnywhereIsManual() {
        Plan plan = only(PeriodGapPlanner.plan(defaultDay(1), List.of(new Slot(6, 9))));

        assertThat(plan.status()).isEqualTo(Status.MANUAL);
        assertThat(plan.reason()).contains("参照");
    }

    @Test
    void overlappingExistingPeriodsAreManual() {
        List<Existing> existing = List.of(
                period(1, 1, "第一节", PeriodSection.MORNING, "10:00", "10:45"),
                period(1, 2, "第二节", PeriodSection.MORNING, "10:30", "11:15"));

        Plan plan = only(PeriodGapPlanner.plan(existing, List.of(new Slot(1, 3))));

        assertThat(plan.status()).isEqualTo(Status.MANUAL);
        assertThat(plan.reason()).contains("间隔为负");
    }

    @Test
    void aProposalThatWouldRunPastMidnightIsManual() {
        List<Existing> existing = new ArrayList<>(defaultDay(1));
        existing.add(period(7, 1, "晚自习", PeriodSection.EVENING, "23:30", "23:50"));

        Plan plan = only(PeriodGapPlanner.plan(existing, List.of(new Slot(7, 2))));

        assertThat(plan.status()).isEqualTo(Status.MANUAL);
        assertThat(plan.reason()).contains("午夜");
    }

    @Test
    void aMiddleGapTooSmallForTheNeighbouringSlotIsManual() {
        List<Existing> existing = List.of(
                morning(1, 2, "08:55", "09:40"),
                morning(1, 4, "09:55", "10:40"),
                morning(2, 2, "08:55", "09:40"),
                morning(2, 3, "09:50", "10:35"));

        Plan plan = only(PeriodGapPlanner.plan(existing, List.of(new Slot(1, 3))));

        assertThat(plan.status()).isEqualTo(Status.MANUAL);
        assertThat(plan.reason()).contains("放不进");
    }

    @Test
    void aSlotBetweenMorningAndAfternoonNeedsAHuman() {
        List<Existing> existing = List.of(
                morning(1, 4, "10:45", "11:30"),
                afternoon(1, 6, "14:55", "15:40"));

        Plan plan = only(PeriodGapPlanner.plan(existing, List.of(new Slot(1, 5))));

        assertThat(plan.status()).isEqualTo(Status.MANUAL);
        assertThat(plan.reason()).contains("时段");
    }

    @Test
    void aSlotThatAlreadyExistsIsNotProposedAgain() {
        List<Plan> plans = PeriodGapPlanner.plan(defaultDay(1), List.of(new Slot(1, 3)));

        assertThat(plans).isEmpty();
    }

    @Test
    void theSameSlotReferencedTwiceIsProposedOnce() {
        List<Plan> plans = PeriodGapPlanner.plan(
                defaultDay(1), List.of(new Slot(1, 9), new Slot(1, 9)));

        assertThat(plans).hasSize(1);
    }

    @Test
    void oneUnplannableSlotDoesNotStopTheOthers() {
        List<Existing> existing = new ArrayList<>(defaultDay(1));

        List<Plan> plans = PeriodGapPlanner.plan(
                existing, List.of(new Slot(1, 9), new Slot(6, 9)));

        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).status()).isEqualTo(Status.PROPOSED);
        assertThat(plans.get(0).dayOfWeek()).isEqualTo(1);
        assertThat(plans.get(1).status()).isEqualTo(Status.MANUAL);
        assertThat(plans.get(1).dayOfWeek()).isEqualTo(6);
    }
}
