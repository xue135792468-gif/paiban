package com.paiban.ai.imports;

import com.paiban.foundation.domain.PeriodSection;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ticket 08's pure half: given the periods a semester already has and the (day, periodNo)
 * slots a workbook references but the semester lacks, propose a time for each slot that can
 * be defended from the neighbouring periods, and mark the rest for a human.
 *
 * <p>Rules, in the order they are applied:
 * <ul>
 *   <li>A slot in the middle of a day reuses the previous period's duration and the day's
 *       usual gap, and must end before the next period starts.</li>
 *   <li>A slot after the day's last period is appended with the same duration and gap.</li>
 *   <li>A day with no periods at all borrows another day's time for the same period number;
 *       days that disagree, or no day that has it, mean a human.</li>
 *   <li>Overlapping periods, a gap too small for the slot, a slot that falls between two
 *       sections (morning/afternoon/evening), or arithmetic that crosses midnight are all
 *       manual.</li>
 * </ul>
 *
 * <p>The planner is timezone-free: everything is {@link LocalTime}, and a shift that wraps
 * past midnight is refused rather than silently reordered.
 */
public final class PeriodGapPlanner {

    public enum Status {
        /** The time was derived from existing periods and may be written once confirmed. */
        PROPOSED,
        /** No defensible time: the user has to set the period by hand. */
        MANUAL
    }

    /** One existing period, as many days as the semester has them. */
    public record Existing(
            int dayOfWeek, int periodNo, String label, PeriodSection section,
            LocalTime startTime, LocalTime endTime) {}

    /** One (day, periodNo) the file references and the semester does not have. */
    public record Slot(int dayOfWeek, int periodNo) {}

    /** The planner's answer for one slot: a time to confirm, or a reason for a human. */
    public record Plan(
            int dayOfWeek, int periodNo, Status status, String label, PeriodSection section,
            LocalTime startTime, LocalTime endTime, String reason) {}

    private static final String NO_REFERENCE = "没有可参照的作息时间，请到作息管理手工设置";
    private static final String CONFLICTING_REFERENCE = "其他星期的第 %d 节作息不一致，无法确定";
    private static final String CONFLICTING_GAPS = "其他星期的课间间隔不一致，无法确定第 %d 节的课间";
    private static final String NEGATIVE_GAP = "既有课节时间重叠（间隔为负），请先修正作息";
    private static final String NO_GAP_REFERENCE = "没有可参照的课间间隔，请到作息管理手工设置";
    private static final String INVALID_PERIOD = "既有第 %d 节的起止时间无效，请先修正作息";
    private static final String DOES_NOT_FIT = "第 %d 节放不进第 %d 节与第 %d 节之间的空档";
    private static final String SECTION_BOUNDARY =
            "第 %d 节落在%s与%s之间，跨时段边界，需人工确认";
    private static final String MIDNIGHT = "推算结果跨午夜，需人工设置";

    private PeriodGapPlanner() {}

    /** Every slot that can be answered, in day/period order. Slots that already exist are skipped. */
    public static List<Plan> plan(List<Existing> existingPeriods, Collection<Slot> missingSlots) {
        List<Existing> existing = existingPeriods == null ? List.of() : existingPeriods;
        Map<Integer, List<Existing>> working = byDay(existing);
        List<Plan> plans = new ArrayList<>();
        if (missingSlots == null) {
            return plans;
        }
        Set<Slot> ordered = new TreeSet<>(
                Comparator.comparingInt(Slot::dayOfWeek).thenComparingInt(Slot::periodNo));
        ordered.addAll(missingSlots);
        for (Slot slot : ordered) {
            List<Existing> day = working.computeIfAbsent(slot.dayOfWeek(), key -> new ArrayList<>());
            if (day.stream().anyMatch(period -> period.periodNo() == slot.periodNo())) {
                continue;
            }
            Plan plan = planOne(slot, day, existing);
            // A proposed slot becomes the anchor for later slots of the same day, so 第10节
            // follows the 第9节 proposed in this same round instead of the old 第8节.
            if (plan.status() == Status.PROPOSED) {
                day.add(new Existing(slot.dayOfWeek(), slot.periodNo(), plan.label(),
                        plan.section(), plan.startTime(), plan.endTime()));
                day.sort(Comparator.comparingInt(Existing::periodNo));
            }
            plans.add(plan);
        }
        return plans;
    }

    private static Map<Integer, List<Existing>> byDay(List<Existing> periods) {
        Map<Integer, List<Existing>> byDay = new LinkedHashMap<>();
        for (Existing period : periods) {
            byDay.computeIfAbsent(period.dayOfWeek(), key -> new ArrayList<>()).add(period);
        }
        byDay.values().forEach(day -> day.sort(Comparator.comparingInt(Existing::periodNo)));
        return byDay;
    }

    private static Plan planOne(Slot slot, List<Existing> day, List<Existing> all) {
        if (day.isEmpty()) {
            return fromOtherDays(slot, all);
        }
        Existing previous = null;
        Existing next = null;
        for (Existing period : day) {
            if (period.periodNo() < slot.periodNo()) {
                previous = period;
            } else if (period.periodNo() > slot.periodNo() && next == null) {
                next = period;
            }
        }
        return previous != null
                ? afterPrevious(slot, day, all, previous, next)
                : beforeNext(slot, day, all, next);
    }

    private static Plan afterPrevious(
            Slot slot, List<Existing> day, List<Existing> all, Existing previous, Existing next) {
        int duration = minutes(previous.startTime(), previous.endTime());
        if (duration <= 0) {
            return manual(slot, INVALID_PERIOD.formatted(previous.periodNo()));
        }
        if (next != null && next.startTime().isBefore(previous.endTime())) {
            return manual(slot, NEGATIVE_GAP);
        }
        if (next != null && next.section() != previous.section()) {
            return manual(slot, SECTION_BOUNDARY.formatted(
                    slot.periodNo(), previous.section().getLabel(), next.section().getLabel()));
        }
        GapResult gap = gapAfter(day, all, slot, previous, next);
        if (gap.conflicting()) {
            return manual(slot, CONFLICTING_GAPS.formatted(slot.periodNo()));
        }
        if (gap.minutes() == null) {
            return manual(slot, NO_GAP_REFERENCE);
        }
        if (gap.minutes() < 0) {
            return manual(slot, NEGATIVE_GAP);
        }
        LocalTime start = shift(previous.endTime(), gap.minutes());
        if (start == null) {
            return manual(slot, MIDNIGHT);
        }
        LocalTime end = shift(start, duration);
        if (end == null) {
            return manual(slot, MIDNIGHT);
        }
        if (next != null && end.isAfter(next.startTime())) {
            return manual(slot, DOES_NOT_FIT.formatted(
                    slot.periodNo(), previous.periodNo(), next.periodNo()));
        }
        return proposed(slot, previous.section(), start, end);
    }

    private static Plan beforeNext(
            Slot slot, List<Existing> day, List<Existing> all, Existing next) {
        int duration = minutes(next.startTime(), next.endTime());
        if (duration <= 0) {
            return manual(slot, INVALID_PERIOD.formatted(next.periodNo()));
        }
        GapResult gap = gapBefore(day, all, slot, next);
        if (gap.conflicting()) {
            return manual(slot, CONFLICTING_GAPS.formatted(slot.periodNo()));
        }
        if (gap.minutes() == null) {
            return manual(slot, NO_GAP_REFERENCE);
        }
        if (gap.minutes() < 0) {
            return manual(slot, NEGATIVE_GAP);
        }
        LocalTime end = shiftBack(next.startTime(), gap.minutes());
        if (end == null) {
            return manual(slot, MIDNIGHT);
        }
        LocalTime start = shiftBack(end, duration);
        if (start == null) {
            return manual(slot, MIDNIGHT);
        }
        return proposed(slot, next.section(), start, end);
    }

    /** A day with no periods borrows another day's identical period number, if they agree. */
    private static Plan fromOtherDays(Slot slot, List<Existing> all) {
        List<Existing> sameNumber = all.stream()
                .filter(period -> period.periodNo() == slot.periodNo())
                .toList();
        if (sameNumber.isEmpty()) {
            return manual(slot, NO_REFERENCE);
        }
        Existing reference = sameNumber.get(0);
        boolean agree = sameNumber.stream().allMatch(period ->
                period.section() == reference.section()
                        && period.startTime().equals(reference.startTime())
                        && period.endTime().equals(reference.endTime()));
        if (!agree) {
            return manual(slot, CONFLICTING_REFERENCE.formatted(slot.periodNo()));
        }
        return proposed(slot, reference.section(), reference.startTime(), reference.endTime());
    }

    /**
     * The gap to leave after {@code previous}: prefer the gap right before it on the same
     * day, then the gap right after the following period, then the same pair of period
     * numbers observed on other days.
     */
    private static GapResult gapAfter(
            List<Existing> day, List<Existing> all, Slot slot, Existing previous, Existing next) {
        int index = day.indexOf(previous);
        if (index > 0) {
            return GapResult.of(minutes(day.get(index - 1).endTime(), previous.startTime()));
        }
        if (next != null) {
            int nextIndex = day.indexOf(next);
            if (nextIndex >= 0 && nextIndex + 1 < day.size()) {
                return GapResult.of(minutes(next.endTime(), day.get(nextIndex + 1).startTime()));
            }
        }
        return otherDayGap(all, slot.dayOfWeek(), previous.periodNo(), slot.periodNo());
    }

    /** The gap to leave before {@code next}, mirroring {@link #gapAfter}. */
    private static GapResult gapBefore(
            List<Existing> day, List<Existing> all, Slot slot, Existing next) {
        int index = day.indexOf(next);
        if (index >= 0 && index + 1 < day.size()) {
            return GapResult.of(minutes(next.endTime(), day.get(index + 1).startTime()));
        }
        return otherDayGap(all, slot.dayOfWeek(), slot.periodNo(), next.periodNo());
    }

    /**
     * The gap between two consecutive period numbers on other days. Days that observed
     * different gaps cannot settle the question, so the slot becomes manual.
     */
    private static GapResult otherDayGap(
            List<Existing> all, int excludedDay, int lowPeriodNo, int highPeriodNo) {
        Set<Integer> gaps = new TreeSet<>();
        for (Map.Entry<Integer, List<Existing>> entry : byDay(all).entrySet()) {
            if (entry.getKey() == excludedDay) {
                continue;
            }
            List<Existing> day = entry.getValue();
            for (int i = 0; i + 1 < day.size(); i++) {
                if (day.get(i).periodNo() == lowPeriodNo && day.get(i + 1).periodNo() == highPeriodNo) {
                    gaps.add(minutes(day.get(i).endTime(), day.get(i + 1).startTime()));
                }
            }
        }
        if (gaps.isEmpty()) {
            return GapResult.none();
        }
        if (gaps.size() > 1) {
            return GapResult.disagree();
        }
        return GapResult.of(gaps.iterator().next());
    }

    /** A gap observed on the day, on another day, or the reason there is none. */
    private record GapResult(Integer minutes, boolean conflicting) {

        static GapResult of(int minutes) {
            return new GapResult(minutes, false);
        }

        static GapResult none() {
            return new GapResult(null, false);
        }

        static GapResult disagree() {
            return new GapResult(null, true);
        }
    }

    private static Plan proposed(Slot slot, PeriodSection section, LocalTime start, LocalTime end) {
        return new Plan(slot.dayOfWeek(), slot.periodNo(), Status.PROPOSED, labelOf(slot.periodNo()),
                section, start, end, null);
    }

    private static Plan manual(Slot slot, String reason) {
        return new Plan(slot.dayOfWeek(), slot.periodNo(), Status.MANUAL, labelOf(slot.periodNo()),
                null, null, null, reason);
    }

    public static String labelOf(int periodNo) {
        return "第" + periodNo + "节";
    }

    private static int minutes(LocalTime from, LocalTime to) {
        return (int) Duration.between(from, to).toMinutes();
    }

    /** {@code time + minutes}, or null when the result would wrap past midnight. */
    private static LocalTime shift(LocalTime time, int minutes) {
        LocalTime result = time.plusMinutes(minutes);
        return result.isBefore(time) ? null : result;
    }

    /** {@code time - minutes}, or null when the result would wrap before midnight. */
    private static LocalTime shiftBack(LocalTime time, int minutes) {
        LocalTime result = time.minusMinutes(minutes);
        return result.isAfter(time) ? null : result;
    }
}
