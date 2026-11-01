package com.paiban.scheduling.service;

import com.paiban.scheduling.solver.ConstraintSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure necessary-condition check for "can this class still fit its weekly lessons?".
 *
 * <p>For one class the available weekly positions are the semester's real
 * {@code (dayOfWeek, periodNo)} slots minus the union of every effective
 * {@code CLASS_UNAVAILABLE} requirement that covers it. Overlapping requirements
 * therefore deduct a slot once, and a whole-school requirement reduces every class.
 *
 * <p>The comparison with the summed {@code weeklyLessons} is only a necessary
 * condition: teacher conflicts and the other constraints can still make a
 * within-capacity timetable unsolvable, so this class never claims a timetable is
 * feasible. It only explains a clear shortage before a pointless solve starts.
 *
 * <p>Deliberately stateless and dependency free: callers pass the raw slot list, the
 * per-class weekly total and one immutable {@link ConstraintSettings} snapshot.
 */
public final class ClassCapacityPlanner {

    /** One real position of the semester timetable. */
    public record Slot(int dayOfWeek, int periodNo) {}

    /**
     * One class's necessary capacity. {@code available} is the distinct usable
     * position count, {@code shortage} is at least zero.
     */
    public record Load(Long classId, String className, int required, int available) {

        public int shortage() {
            return Math.max(0, required - available);
        }
    }

    private ClassCapacityPlanner() {}

    /**
     * Every class that carries at least one weekly lesson, with its capacity result.
     * Ordered by class name (null last) then id so messages are stable.
     */
    public static List<Load> analyse(
            List<Slot> slots,
            Map<Long, Integer> weeklyLessonsByClass,
            Map<Long, String> classNameById,
            ConstraintSettings settings) {
        if (weeklyLessonsByClass == null || weeklyLessonsByClass.isEmpty()) {
            return List.of();
        }
        // A semester without periods has zero real positions, not "no opinion": every
        // class that already carries weekly lessons is then short by its whole load.
        Set<Slot> distinct = slots == null ? Set.of() : new LinkedHashSet<>(slots);
        List<Load> loads = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : weeklyLessonsByClass.entrySet()) {
            Long classId = entry.getKey();
            if (classId == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            int available = availablePositions(distinct, classId, settings);
            loads.add(new Load(
                    classId,
                    nameOf(classNameById, classId),
                    entry.getValue(),
                    available));
        }
        loads.sort(Comparator
                .comparing(Load::className, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Load::classId));
        return List.copyOf(loads);
    }

    /** The subset of {@link #analyse} whose weekly lessons exceed the available positions. */
    public static List<Load> shortages(
            List<Slot> slots,
            Map<Long, Integer> weeklyLessonsByClass,
            Map<Long, String> classNameById,
            ConstraintSettings settings) {
        return analyse(slots, weeklyLessonsByClass, classNameById, settings).stream()
                .filter(load -> load.shortage() > 0)
                .toList();
    }

    /** How many distinct positions one class may still use. */
    public static int availablePositions(
            List<Slot> slots, Long classId, ConstraintSettings settings) {
        if (slots == null || slots.isEmpty()) {
            return 0;
        }
        Set<Slot> distinct = new LinkedHashSet<>(slots);
        return availablePositions(distinct, classId, settings);
    }

    private static int availablePositions(
            Set<Slot> distinctSlots, Long classId, ConstraintSettings settings) {
        int available = 0;
        for (Slot slot : distinctSlots) {
            if (settings == null
                    || !settings.classUnavailable(classId, slot.dayOfWeek(), slot.periodNo())) {
                available++;
            }
        }
        return available;
    }

    /**
     * One user-facing sentence per short class: name, required, available and the
     * exact difference, plus the two actionable directions. The text never claims the
     * timetable is otherwise feasible.
     */
    public static String message(List<Load> shortages) {
        if (shortages == null || shortages.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Load load : shortages) {
            parts.add("「" + (load.className() == null
                    ? "行政班#" + load.classId() : load.className())
                    + "」周课时 " + load.required()
                    + " 节，可用位置 " + load.available()
                    + " 个，缺 " + load.shortage() + " 节");
        }
        return "每周课时超过可用位置：" + String.join("；", parts)
                + "。请减少每周不上课时段或调整授课任务";
    }

    private static String nameOf(Map<Long, String> classNameById, Long classId) {
        return classNameById == null ? null : classNameById.get(classId);
    }
}
