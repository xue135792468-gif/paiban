package com.paiban.substitution.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
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

    // ------------------------------------------------- 票 05：明确目标日期的调课

    @Test
    void explicitTargetDatePlansTheCycleOnThatExactDate() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        long second = builder.addLesson(CLASS_1, 2, 2, 2L, 3, 1);
        PlanSnapshot snapshot = builder.build();
        // 明确指定下周三：不会被替换成同星期的另一周（本周三，旧推算的落点）。
        java.time.LocalDate nextWednesday = PlanFixtures.MONDAY.plusDays(9);

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(3, 1), nextWednesday),
                SwapPlanner.Options.of(10, 6, RankingPreset.MIN_IMPACT));

        assertThat(result.candidates()).isNotEmpty();
        CandidatePlan plan = result.candidates().get(0);
        assertThat(plan.cycleLength()).isEqualTo(2);
        assertThat(plan.moves()).anySatisfy(move -> {
            if (move.entryId() == first) {
                assertThat(move.toDate()).isEqualTo(nextWednesday);
            }
            if (move.entryId() == second) {
                assertThat(move.fromDate()).isEqualTo(nextWednesday);
                assertThat(move.toDate()).isEqualTo(PlanFixtures.date(1));
            }
        });
    }

    @Test
    void sameWeekdayNextWeekIsNotMistakenForTheOriginalSlot() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        long second = builder.addLesson(CLASS_1, 2, 2, 2L, 1, 2);
        PlanSnapshot snapshot = builder.build();
        java.time.LocalDate nextMonday = PlanFixtures.MONDAY.plusDays(7);

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(1, 2), nextMonday),
                SwapPlanner.Options.of(8, 6, RankingPreset.MIN_IMPACT));

        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates().get(0).moves()).anySatisfy(move -> {
            if (move.entryId() == first) {
                // 目标落点是传入的下周一，不是同一星期的本周一（原槽）。
                assertThat(move.toDate()).isEqualTo(nextMonday);
            }
            if (move.entryId() == second) {
                assertThat(move.fromDate()).isEqualTo(nextMonday);
                assertThat(move.toDate()).isEqualTo(PlanFixtures.date(1));
            }
        });
    }

    @Test
    void multiTeacherCycleWorksWithAnExplicitTargetDate() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        long second = builder.addLesson(CLASS_1, 2, 2, 2L, 1, 2);
        long third = builder.addLesson(CLASS_1, 3, 3, 3L, 1, 3);
        // Teacher 2 在周一第 1 节已被高一2班占用，直接互移不可行，需要第三人成环。
        builder.lesson(CLASS_2, 4, 2, 4L, 1, 1);
        PlanSnapshot snapshot = builder.build();

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(1, 2), PlanFixtures.date(1)),
                SwapPlanner.Options.defaults());

        assertThat(result.candidates()).isNotEmpty();
        CandidatePlan plan = result.candidates().get(0);
        assertThat(plan.cycleLength()).isEqualTo(3);
        assertThat(plan.moves()).extracting(PlanMove::entryId)
                .containsExactlyInAnyOrder(first, second, third);
        assertThat(plan.moves()).extracting(PlanMove::toDate)
                .contains(PlanFixtures.date(1));
    }

    @Test
    void explicitTargetDateBeforeTheSourceIsRejected() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        builder.addLesson(CLASS_1, 2, 2, 2L, 3, 1);
        PlanSnapshot snapshot = builder.build();

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(3, 1), PlanFixtures.date(1).minusDays(1)),
                SwapPlanner.Options.of(10, 6, RankingPreset.MIN_IMPACT));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.diagnoses()).extracting(PlanDiagnosis::code)
                .contains("TARGET_OUT_OF_WINDOW");
    }

    @Test
    void explicitTargetDateBeyondTheWindowIsRejected() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        builder.addLesson(CLASS_1, 2, 2, 2L, 3, 1);
        PlanSnapshot snapshot = builder.build();

        // 窗口 7 天 → [周一, 周日]；下周二越界（旧推算的落点最多到 +6 天）。
        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(3, 1), PlanFixtures.date(1).plusDays(8)),
                SwapPlanner.Options.of(7, 6, RankingPreset.MIN_IMPACT));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.diagnoses()).extracting(PlanDiagnosis::code)
                .contains("TARGET_OUT_OF_WINDOW");
    }

    @Test
    void explicitTargetDateBeyondTheSemesterIsRejected() {
        PlanFixtures.Builder builder = base();
        builder.semesterEndsAfter(10);
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        builder.addLesson(CLASS_1, 2, 2, 2L, 3, 1);
        PlanSnapshot snapshot = builder.build();

        // 目标日期在窗口（30 天）内但超出学期最后一天（MONDAY+10 是周四，与课节星期一致）。
        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(4, 1), PlanFixtures.MONDAY.plusDays(10)),
                SwapPlanner.Options.of(30, 6, RankingPreset.MIN_IMPACT));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.diagnoses()).extracting(PlanDiagnosis::code)
                .contains("TARGET_OUT_OF_SEMESTER");
    }

    @Test
    void explicitTargetDateOnAnEmptySlotIsRejected() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        PlanSnapshot snapshot = builder.build();

        // 明确目标日期当天（周三），本班第 4 节没有课：空格只挪不补。
        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(3, 4), PlanFixtures.date(3)),
                SwapPlanner.Options.of(7, 6, RankingPreset.MIN_IMPACT));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.diagnoses()).extracting(PlanDiagnosis::code)
                .contains("TARGET_EMPTY");
    }

    @Test
    void explicitTargetDateWithMismatchedWeekdayIsRejected() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        builder.addLesson(CLASS_1, 2, 2, 2L, 3, 1);
        PlanSnapshot snapshot = builder.build();

        // 目标课节是周三第 1 节，目标日期却是周二：明确拒绝而不是静默换日。
        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(3, 1), PlanFixtures.date(2)),
                SwapPlanner.Options.of(7, 6, RankingPreset.MIN_IMPACT));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.diagnoses()).extracting(PlanDiagnosis::code)
                .contains("TARGET_DAY_MISMATCH");
    }

    // ------------------------------------------------- 冻结槽（票 07，部分已开始的环）

    @Test
    void frozenSlotsAreSkippedWhenSearchingCycleNodes() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        long second = builder.addLesson(CLASS_1, 2, 2, 2L, 1, 2);
        PlanSnapshot snapshot = builder.build();

        // 不带冻结槽：周一第 1 → 第 2 的二人环可解。
        PlanningResult open = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(1, 2)),
                SwapPlanner.Options.defaults());
        assertThat(open.candidates()).isNotEmpty();

        // 目标槽（周一第 2 节）已开始：冻结后不可再作为落点或环节点，无解并给出原因。
        PlanningResult frozen = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(1, 2), null,
                        Set.of(new FrozenSlot(PlanFixtures.date(1),
                                PlanFixtures.periodId(1, 2)))),
                SwapPlanner.Options.defaults());
        assertThat(frozen.candidates()).isEmpty();
        assertThat(frozen.diagnoses()).extracting(PlanDiagnosis::code)
                .contains("TARGET_FROZEN");
    }

    @Test
    void frozenSourceSlotIsRejectedWithASourceDiagnosis() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        builder.addLesson(CLASS_1, 2, 2, 2L, 1, 2);
        PlanSnapshot snapshot = builder.build();

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(1, 2), null,
                        Set.of(new FrozenSlot(PlanFixtures.date(1),
                                PlanFixtures.periodId(1, 1)))),
                SwapPlanner.Options.defaults());

        assertThat(result.candidates()).isEmpty();
        assertThat(result.diagnoses()).extracting(PlanDiagnosis::code)
                .contains("SOURCE_FROZEN");
    }

    @Test
    void cycleDetoursAroundFrozenIntermediateSlots() {
        PlanFixtures.Builder builder = base();
        long first = builder.addLesson(CLASS_1, 1, 1, 1L, 1, 1);
        long second = builder.addLesson(CLASS_1, 2, 2, 2L, 1, 2);
        long third = builder.addLesson(CLASS_1, 3, 3, 3L, 2, 1);
        PlanSnapshot snapshot = builder.build();

        // 目标 = 周二第 1 节（third）。不冻结时既有二人环也有经周一第 2 节（second）
        // 的三人环；冻结周一第 2 节后，三条腿的环被排除，只剩不碰冻结槽的二人环。
        PlanningResult open = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(2, 1)),
                SwapPlanner.Options.defaults());
        assertThat(open.candidates()).isNotEmpty();

        PlanningResult result = SwapPlanner.plan(
                snapshot,
                new SwapPlanner.Request(CLASS_1, first, PlanFixtures.date(1),
                        PlanFixtures.periodId(2, 1), null,
                        Set.of(new FrozenSlot(PlanFixtures.date(1),
                                PlanFixtures.periodId(1, 2)))),
                SwapPlanner.Options.defaults());

        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates()).allSatisfy(plan -> {
            assertThat(plan.moves()).extracting(PlanMove::entryId).doesNotContain(second);
            assertThat(plan.moves()).allSatisfy(move -> {
                boolean touchesFrozenFrom = move.fromDate().equals(PlanFixtures.date(1))
                        && move.fromPeriodId() == PlanFixtures.periodId(1, 2);
                boolean touchesFrozenTo = move.toDate().equals(PlanFixtures.date(1))
                        && move.toPeriodId() == PlanFixtures.periodId(1, 2);
                assertThat(touchesFrozenFrom || touchesFrozenTo).isFalse();
            });
        });
        assertThat(result.candidates().get(0).moves())
                .extracting(PlanMove::entryId)
                .containsExactlyInAnyOrder(first, third);
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
