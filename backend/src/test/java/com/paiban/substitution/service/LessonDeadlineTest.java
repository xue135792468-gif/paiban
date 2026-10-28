package com.paiban.substitution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paiban.substitution.service.LessonDeadline.ChangedSlot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 开课截止（规格第 6 节）的纯计算规则：
 * 学校时区固定 Asia/Shanghai，本单截止 = 本次将改变的所有实例
 * 「当前安排与拟生效安排」开课时刻的最小值；确认只允许严格早于截止。
 */
class LessonDeadlineTest {

    private static final ZoneId ZONE = LessonDeadline.SCHOOL_ZONE;

    private static Instant at(String dateTime) {
        return java.time.ZonedDateTime.of(java.time.LocalDateTime.parse(dateTime), ZONE).toInstant();
    }

    @Test
    void singleCoverSlotStartsAtTheLessonTime() {
        // 代课只换教师不换时间：当前与拟生效是同一时刻，截止 = 该课开课时刻。
        Instant deadline = LessonDeadline.earliest(List.of(
                new ChangedSlot(LocalDate.of(2025, 9, 5), LocalTime.of(8, 0))));
        assertThat(deadline).isEqualTo(at("2025-09-05T08:00"));
    }

    @Test
    void earliestPicksTheMinimumAcrossChangedInstances() {
        // 一次方案改变多笔实例（含当前与拟生效两个时刻），截止取最小。
        Instant deadline = LessonDeadline.earliest(List.of(
                new ChangedSlot(LocalDate.of(2025, 9, 5), LocalTime.of(10, 0)),
                new ChangedSlot(LocalDate.of(2025, 9, 4), LocalTime.of(14, 30)),
                new ChangedSlot(LocalDate.of(2025, 9, 8), LocalTime.of(8, 0))));
        assertThat(deadline).isEqualTo(at("2025-09-04T14:30"));
    }

    @Test
    void exactlyAtTheDeadlineIsRejected() {
        Instant deadline = LessonDeadline.earliest(List.of(
                new ChangedSlot(LocalDate.of(2025, 9, 5), LocalTime.of(8, 0))));
        // 严格小于才允许：恰好开课（等于截止）也拒绝。
        assertThat(LessonDeadline.canConfirm(at("2025-09-05T07:59:59"), deadline)).isTrue();
        assertThat(LessonDeadline.canConfirm(at("2025-09-05T08:00:00"), deadline)).isFalse();
        assertThat(LessonDeadline.canConfirm(at("2025-09-05T08:00:01"), deadline)).isFalse();
    }

    @Test
    void missingStartTimeListsWhatCannotBeResolved() {
        // 课节开始时间缺失：拒绝写入并说明缺失项，不用默认时间猜测。
        assertThatThrownBy(() -> LessonDeadline.earliest(List.of(
                        new ChangedSlot(LocalDate.of(2025, 9, 5), null)))
                ).isInstanceOf(LessonDeadline.MissingStartTimeException.class)
                .hasMessageContaining("2025-09-05")
                .hasMessageContaining("开始时间");
    }

    @Test
    void guardReReadsTheClockOnEveryCheck() {
        // 「等待行锁后必须重新读取时间」：截止复查不允许缓存时钟面。
        // 同一 checker 在时钟推进后必须得到不同结论。
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(at("2025-09-05T07:00"));
        com.paiban.substitution.service.DeadlineChecker checker =
                new com.paiban.substitution.service.DeadlineChecker(() -> now.get());
        Instant deadline = at("2025-09-05T08:00");

        checker.requireBeforeDeadline(deadline, "2025-09-05 第 1 节");

        now.set(at("2025-09-05T08:00"));
        assertThatThrownBy(() -> checker.requireBeforeDeadline(deadline, "2025-09-05 第 1 节"))
                .isInstanceOf(com.paiban.common.web.ApiException.class)
                .hasMessageContaining("开课");
    }
}
