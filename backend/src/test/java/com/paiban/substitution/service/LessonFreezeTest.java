package com.paiban.substitution.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.substitution.plan.FrozenSlot;
import com.paiban.substitution.plan.PlanFixtures;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.substitution.plan.SwapPlanner;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 冻结实例判定（票 07，规格第 6 节）的纯单测：实例当前安排的开课时刻已过
 * （Asia/Shanghai 服务端时钟 + 课节开始时间）即冻结。旧置换环部分实例已开始时，
 * 新方案不得包含任何对冻结实例的改动——生成方案（跳过冻结槽）与预览/确认校验
 * （触及即整单拒绝）两处共用本判定。
 */
class LessonFreezeTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2025, 9, 3);
    private static final LocalDate FRIDAY = LocalDate.of(2025, 9, 5);
    /** 周三 08:00（Asia/Shanghai）= 00:00Z。 */
    private static final Instant WEDNESDAY_START = Instant.parse("2025-09-03T00:00:00Z");
    private static final LocalTime EIGHT = LocalTime.of(8, 0);
    private static final LocalTime NINE = LocalTime.of(9, 0);

    /** 课节开始时间表：p1=08:00、p2=09:00。 */
    private static final Function<Long, LocalTime> STARTS =
            periodId -> periodId == PlanFixtures.periodId(3, 1) ? EIGHT
                    : periodId == PlanFixtures.periodId(3, 2) ? NINE
                    : periodId == PlanFixtures.periodId(5, 1) ? EIGHT
                    : null;

    @Test
    void startedIsTrueOnlyAtOrAfterTheLessonStart() {
        // 严格早于才可调整：前一瞬未冻结，恰好开课与开课之后都冻结。
        assertThat(LessonFreeze.started(WEDNESDAY_START.minusSeconds(1), WEDNESDAY, EIGHT)).isFalse();
        assertThat(LessonFreeze.started(WEDNESDAY_START, WEDNESDAY, EIGHT)).isTrue();
        assertThat(LessonFreeze.started(WEDNESDAY_START.plusSeconds(3600), WEDNESDAY, EIGHT)).isTrue();
        // 时区固定 Asia/Shanghai：周三 12:00（上海）= 04:00Z 已开课；若实现误用 UTC，
        // 开课时刻会算成 08:00Z、此刻还没开课——这条断言把时区钉死。
        assertThat(LessonFreeze.started(Instant.parse("2025-09-03T04:00:00Z"), WEDNESDAY, EIGHT)).isTrue();
        assertThat(LessonFreeze.started(Instant.parse("2025-09-02T23:59:59Z"), WEDNESDAY, EIGHT)).isFalse();
    }

    @Test
    void frozenSlotsCollectStartedSlotsOfMoves() {
        List<PlanMove> moves = List.of(
                move(100L, PlanFixtures.periodId(5, 1), FRIDAY, PlanFixtures.periodId(3, 2), WEDNESDAY),
                move(200L, PlanFixtures.periodId(3, 2), WEDNESDAY, PlanFixtures.periodId(5, 1), FRIDAY));

        // 周三 09:30：entry 200 的 from 槽（周三 09:00）已开始 → 冻结；周五 08:00 未到。
        Instant wednesday = Instant.parse("2025-09-03T01:30:00Z");
        Set<FrozenSlot> frozen = LessonFreeze.frozenFromSlots(moves, STARTS, wednesday);
        assertThat(frozen).containsExactly(new FrozenSlot(WEDNESDAY, PlanFixtures.periodId(3, 2)));

        // 周五 09:30：两个 from 槽（周三 09:00、周五 08:00）都已开始。
        assertThat(LessonFreeze.frozenFromSlots(moves, STARTS,
                Instant.parse("2025-09-05T01:30:00Z")))
                .containsExactlyInAnyOrder(
                        new FrozenSlot(FRIDAY, PlanFixtures.periodId(5, 1)),
                        new FrozenSlot(WEDNESDAY, PlanFixtures.periodId(3, 2)));

        // 周三之前：没有任何槽冻结。
        assertThat(LessonFreeze.frozenFromSlots(moves, STARTS,
                Instant.parse("2025-09-02T00:00:00Z"))).isEmpty();
    }

    @Test
    void frozenTouchReasonCoversBothFromAndToSlotsOfEveryMove() {
        List<PlanMove> moves = List.of(
                move(100L, PlanFixtures.periodId(5, 1), FRIDAY, PlanFixtures.periodId(3, 2), WEDNESDAY));

        // to 槽（周三 09:00）已开始：不能把课移到已经开课的槽位。
        assertThat(LessonFreeze.frozenTouchReason(moves, STARTS,
                Instant.parse("2025-09-03T01:10:00Z"))).isPresent();

        // from 槽（周五 08:00）已开始：方案要把它移走 → 冻结实例被改动，整单拒绝。
        assertThat(LessonFreeze.frozenTouchReason(moves, STARTS,
                Instant.parse("2025-09-04T00:30:00Z"))).isPresent();

        // 全部槽位未开始：没有冻结冲突。
        assertThat(LessonFreeze.frozenTouchReason(moves, STARTS,
                Instant.parse("2025-09-02T00:00:00Z"))).isEmpty();
    }

    @Test
    void missingStartTimeIsReportedAsFrozenTouch() {
        // 课节缺少开始时间时不能猜测：按「无法确定开课时刻」处理，拒绝写入并说明。
        List<PlanMove> moves = List.of(
                move(100L, 999L, FRIDAY, PlanFixtures.periodId(3, 2), WEDNESDAY));
        assertThat(LessonFreeze.frozenTouchReason(moves, STARTS,
                Instant.parse("2025-09-02T00:00:00Z"))).isPresent();
    }

    private static PlanMove move(long entryId, long fromPeriodId, LocalDate fromDate,
                                 long toPeriodId, LocalDate toDate) {
        return new PlanMove(entryId, PlanFixtures.CLASS_1, 1L, 1L, null,
                fromPeriodId, fromDate.getDayOfWeek().getValue(), 1, fromDate,
                toPeriodId, toDate.getDayOfWeek().getValue(), 2, toDate, 0);
    }
}
