package com.paiban.ai.imports.workbook;

import com.paiban.ai.imports.ImportDtos.RowDto;
import com.paiban.ai.imports.ImportDtos.RowErrorDto;
import com.paiban.ai.imports.SlotText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One workbook, many sheets, one timetable. Rows from every sheet are folded by slot
 * ("class + day + period"): a complete row (course and teacher) wins over a partial one,
 * partial duplicates of a winning slot are dropped and counted, and a slot with neither
 * course nor teacher is a free period - skipped and counted, not imported as a lesson.
 * Everything else stays a blocking row error.
 */
public final class SlotMerger {

    /** A row that survived the merge, still attributed to the sheet it came from. */
    public record Kept(String sheet, int rowNumber, RowDto row) {}

    /** Per-sheet contribution, the numbers the preview lists for each sheet. */
    public record SheetStats(int recognized, int kept, int freeSlots, int droppedIncomplete, int duplicates) {}

    public record Merge(
            List<Kept> kept,
            int freeSlots,
            int droppedIncomplete,
            int duplicates,
            List<RowErrorDto> errors,
            Map<String, SheetStats> perSheet) {}

    private SlotMerger() {}

    public static Merge merge(List<Kept> rows) {
        Map<String, Slot> slots = new LinkedHashMap<>();
        List<RowErrorDto> errors = new ArrayList<>();
        Map<String, Counter> counters = new LinkedHashMap<>();
        int numbering = 0;
        for (Kept kept : rows) {
            numbering++;
            Counter counter = counters.computeIfAbsent(kept.sheet(), name -> new Counter());
            counter.recognized++;
            Map<String, String> values = kept.row().values();
            String className = value(values, "className");
            String dayText = value(values, "dayOfWeek");
            String periodText = value(values, "periodNo");
            String course = value(values, "courseName");
            String teacher = value(values, "teacherName");

            boolean blocked = false;
            String slotText = slotLabel(className, dayText, periodText);
            if (className.isBlank()) {
                errors.add(error(numbering, kept, slotText, "className", "班级不能为空"));
                blocked = true;
            }
            Integer day = SlotText.day(dayText).orElse(null);
            if (dayText.isBlank()) {
                errors.add(error(numbering, kept, slotText, "dayOfWeek", "星期不能为空"));
                blocked = true;
            } else if (day == null) {
                errors.add(error(numbering, kept, slotText, "dayOfWeek", "无法识别星期：" + dayText));
                blocked = true;
            }
            Integer period = SlotText.periodNo(periodText).orElse(null);
            if (periodText.isBlank()) {
                errors.add(error(numbering, kept, slotText, "periodNo", "节次不能为空"));
                blocked = true;
            } else if (period == null) {
                errors.add(error(numbering, kept, slotText, "periodNo", "无法识别节次：" + periodText));
                blocked = true;
            }
            if (blocked) {
                continue;
            }

            Key key = new Key(SlotText.normalizeName(className), day, period);
            Slot slot = slots.computeIfAbsent(key.canonical(), name -> new Slot());
            slot.candidates.add(new Candidate(
                    kept, numbering, course.isBlank(), teacher.isBlank()));
        }

        List<Kept> keptRows = new ArrayList<>();
        int freeSlots = 0;
        int droppedIncomplete = 0;
        int duplicates = 0;
        for (Slot slot : slots.values()) {
            List<Candidate> complete = slot.candidates.stream().filter(Candidate::complete)
                    .sorted(Comparator
                            .comparingInt((Candidate candidate) -> filled(candidate.row.row())).reversed()
                            .thenComparingInt(candidate -> candidate.numbering))
                    .toList();
            List<Candidate> incomplete = slot.candidates.stream()
                    .filter(candidate -> !candidate.complete() && !candidate.free())
                    .toList();
            List<Candidate> free = slot.candidates.stream().filter(Candidate::free).toList();
            if (!complete.isEmpty()) {
                Candidate winner = complete.get(0);
                keptRows.add(winner.row);
                counters.get(winner.row.sheet()).kept++;
                duplicates += complete.size() - 1;
                for (int i = 1; i < complete.size(); i++) {
                    counters.get(complete.get(i).row.sheet()).duplicates++;
                }
                if (!incomplete.isEmpty()) {
                    droppedIncomplete += incomplete.size();
                    for (Candidate candidate : incomplete) {
                        counters.get(candidate.row.sheet()).droppedIncomplete++;
                    }
                }
                // An empty duplicate of a slot that does have a lesson is still a free
                // period in the file; counting it keeps the warning honest.
                if (!free.isEmpty()) {
                    freeSlots += free.size();
                    for (Candidate candidate : free) {
                        counters.get(candidate.row.sheet()).freeSlots++;
                    }
                }
                continue;
            }
            if (!incomplete.isEmpty()) {
                for (Candidate candidate : incomplete) {
                    String candidateSlot = slotLabel(
                            value(candidate.row.row().values(), "className"),
                            value(candidate.row.row().values(), "dayOfWeek"),
                            value(candidate.row.row().values(), "periodNo"));
                    errors.add(error(
                            candidate.numbering, candidate.row, candidateSlot,
                            candidate.blankCourse() ? "courseName" : "teacherName",
                            candidate.blankCourse() ? "课程不能为空" : "教师不能为空"));
                }
                continue;
            }
            if (!free.isEmpty()) {
                freeSlots += free.size();
                for (Candidate candidate : free) {
                    counters.get(candidate.row.sheet()).freeSlots++;
                }
            }
        }
        Map<String, SheetStats> perSheet = new LinkedHashMap<>();
        for (Map.Entry<String, Counter> entry : counters.entrySet()) {
            Counter counter = entry.getValue();
            perSheet.put(entry.getKey(), new SheetStats(
                    counter.recognized, counter.kept, counter.freeSlots,
                    counter.droppedIncomplete, counter.duplicates));
        }
        return new Merge(
                List.copyOf(keptRows), freeSlots, droppedIncomplete, duplicates,
                List.copyOf(errors), perSheet);
    }

    private static RowErrorDto error(
            int numbering, Kept kept, String slot, String field, String message) {
        String where = slot == null || slot.isBlank() ? "" : "（" + slot + "）";
        return new RowErrorDto(
                numbering, field,
                "工作表「" + kept.sheet() + "」第 " + kept.rowNumber() + " 行" + where + "：" + message,
                List.of());
    }

    /** How a user names the slot: {@code 八（1） 星期一 第1节}, blanks left out. */
    private static String slotLabel(String className, String dayText, String periodText) {
        List<String> parts = new ArrayList<>();
        if (className != null && !className.isBlank()) {
            parts.add(className.trim());
        }
        if (dayText != null && !dayText.isBlank()) {
            parts.add(dayText.trim());
        }
        if (periodText != null && !periodText.isBlank()) {
            String period = periodText.trim().replace("第", "").replace("节", "");
            parts.add("第" + period + "节");
        }
        return String.join(" ", parts);
    }

    private static int filled(RowDto row) {
        if (row.values() == null) {
            return 0;
        }
        return (int) row.values().values().stream().filter(value -> value != null && !value.isBlank()).count();
    }

    private static String value(Map<String, String> values, String field) {
        String value = values == null ? null : values.get(field);
        return value == null ? "" : value.trim();
    }

    private record Key(String className, int day, int period) {
        String canonical() {
            return className + "\u0001" + day + "\u0001" + period;
        }
    }

    private record Candidate(Kept row, int numbering, boolean blankCourse, boolean blankTeacher) {
        boolean complete() {
            return !blankCourse && !blankTeacher;
        }

        boolean free() {
            return blankCourse && blankTeacher;
        }
    }

    private static final class Slot {
        final List<Candidate> candidates = new ArrayList<>();
    }

    private static final class Counter {
        int recognized;
        int kept;
        int freeSlots;
        int droppedIncomplete;
        int duplicates;
    }
}
