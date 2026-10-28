package com.paiban.substitution.service;

import com.paiban.substitution.plan.FrozenSlot;
import com.paiban.substitution.plan.PlanMove;
import com.paiban.substitution.plan.SwapPlanner;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 冻结实例判定（票 07，规格第 6 节）。实例当前安排的开课时刻已过
 * （Asia/Shanghai 服务端时钟 + 课节开始时间）→ 冻结：已开始或已结束的授课
 * 任何路径都不可改变（含恢复基线、按旧环反向重放）。
 *
 * <p>纯函数，无 Spring 依赖。预览生成（把冻结槽交给求解器跳过）与预览/确认校验
 * （方案触及冻结槽即整单拒绝并说明）两处共用同一实现，单测钉住语义。
 */
public final class LessonFreeze {

    private LessonFreeze() {}

    /**
     * 该槽位是否已开始：开课时刻 = 日期 + 课节开始时间（学校时区 Asia/Shanghai）。
     * 严格早于才可调整——恰好开课（等于）或之后都算冻结。开始时间缺失时同样视为
     * 冻结（无法证明它还没开始，就不许动），由调用方给出「无法确定开课时刻」的说明。
     */
    public static boolean started(Instant now, LocalDate lessonDate, LocalTime startTime) {
        if (lessonDate == null || startTime == null) {
            return true;
        }
        Instant start = ZonedDateTime.of(lessonDate, startTime, LessonDeadline.SCHOOL_ZONE)
                .toInstant();
        return !now.isBefore(start);
    }

    /**
     * 方案各移动的来源槽（当前实际安排）中已开始的槽位集合：这些实例冻结，
     * 生成候选方案时求解器必须跳过它们（既不能移出、也不能落入）。
     * 开始时间无法确定的课节同样进入集合（宁可拒绝也不猜测）。
     */
    public static Set<FrozenSlot> frozenFromSlots(
            List<PlanMove> moves, Function<Long, LocalTime> periodStart, Instant now) {
        Set<FrozenSlot> frozen = new LinkedHashSet<>();
        for (PlanMove move : moves) {
            if (started(now, move.fromDate(), periodStart.apply(move.fromPeriodId()))) {
                frozen.add(new FrozenSlot(move.fromDate(), move.fromPeriodId()));
            }
        }
        return frozen;
    }

    /**
     * 确认/预览校验：方案是否触及任何已开始的槽位（来源槽 = 改动冻结实例；
     * 落点槽 = 把课移进已经开课的时间）。触及即整单拒绝，返回可读原因；
     * 未触及返回空。
     */
    public static Optional<String> frozenTouchReason(
            List<PlanMove> moves, Function<Long, LocalTime> periodStart, Instant now) {
        for (PlanMove move : moves) {
            if (started(now, move.fromDate(), periodStart.apply(move.fromPeriodId()))) {
                return Optional.of("课节（" + move.fromDate() + "）已经开课，"
                        + "不能改变已开始或已结束的授课，本次方案整单拒绝");
            }
            if (started(now, move.toDate(), periodStart.apply(move.toPeriodId()))) {
                return Optional.of("目标课节（" + move.toDate() + "）已经开课，"
                        + "不能把课移到已开始的时间，本次方案整单拒绝");
            }
        }
        return Optional.empty();
    }
}
