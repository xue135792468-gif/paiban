package com.paiban.scheduling.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed C01–C22 corpus from the ticket-14 preparation notes (ticket 14/09).
 *
 * <p>It is code rather than a loose file so the expected meaning travels with the
 * judge that reads it. The corpus version is part of every report; changing a sentence
 * or an expectation means changing the version too.
 */
public final class ConstraintEvaluationCorpus {

    public static final String VERSION = "C01-C22-v1";

    /** The synthetic school every sentence is evaluated against. */
    public static final Map<String, Long> TEACHERS = ordered(
            "王明", 1L, "李华", 2L, "张伟甲", 3L, "张伟乙", 4L);
    public static final Map<String, Long> COURSES = ordered(
            "语文", 11L, "数学", 12L, "英语", 13L, "体育", 14L, "化学", 15L);
    public static final Map<String, Long> CLASSES = ordered(
            "七年级1班", 21L, "七年级2班", 22L, "九年级1班", 23L, "九年级2班", 24L);
    public static final int MORNING_CUTOFF = 4;

    public enum SlotMode {
        /** One entry per (day, period). */
        SLOTS,
        /** One entry per day, no period (teacher day off). */
        DAY_ONLY,
        /** One entry with the parameters only. */
        NONE
    }

    public record Slot(int day, Integer periodNo) {}

    /** One expected independent requirement. */
    public record Expected(String type, String strength, Map<String, Object> params,
                           List<Slot> slots, SlotMode mode) {}

    public enum Boundary {
        /** C16: a homonym teacher must be a choice while the clear course item stays. */
        HOMONYM,
        /** C17: a vague amount must be asked back, never guessed. */
        VAGUE_AMOUNT,
        /** C19: a class-scoped subject rule is outside the catalogue. */
        CLASS_SCOPE_UNSUPPORTED,
        /** C20: the period is outside the timetable and must be reported. */
        ILLEGAL_PERIOD
    }

    public record Sentence(String code, String text, boolean supported,
                           List<Expected> expected, Boundary boundary) {}

    private ConstraintEvaluationCorpus() {}

    public static List<Sentence> sentences() {
        List<Sentence> all = new ArrayList<>();
        all.add(ready("C01", "王老师周三下午不排课。",
                expected("TEACHER_UNAVAILABLE", "HARD", Map.of("teacher", "王明"),
                        slots().day(3, 5, 6, 7, 8).build())));
        all.add(ready("C02", "王老师和李老师周二第1、2节都不排。",
                expected("TEACHER_UNAVAILABLE", "HARD", Map.of("teacher", "王明"),
                        slots().day(2, 1, 2).build()),
                expected("TEACHER_UNAVAILABLE", "HARD", Map.of("teacher", "李华"),
                        slots().day(2, 1, 2).build())));
        all.add(ready("C03", "每周三下午语文组统一教研，不安排课。",
                expected("TEACHER_MEETING_BLOCKED", "HARD", Map.of("subject", "语文"),
                        slots().day(3, 5, 6, 7, 8).build())));
        all.add(ready("C04", "体育课必须在操场上。",
                expected("COURSE_REQUIRED_ROOM_TYPE", "HARD",
                        Map.of("course", "体育", "roomType", "PLAYGROUND"), SlotMode.NONE)));
        all.add(ready("C05", "化学课安排在实验室。",
                expected("COURSE_REQUIRED_ROOM_TYPE", "INHERIT",
                        Map.of("course", "化学", "roomType", "LAB"), SlotMode.NONE)));
        all.add(ready("C06", "体育和化学都不排晚自习。",
                expected("COURSE_NOT_IN_EVENING", "HARD", Map.of("course", "体育"),
                        SlotMode.NONE),
                expected("COURSE_NOT_IN_EVENING", "HARD", Map.of("course", "化学"),
                        SlotMode.NONE)));
        all.add(ready("C07", "全部课程在同一个班一天最多上一节。",
                expected("SAME_COURSE_ONCE_PER_DAY", "HARD", Map.of(), SlotMode.NONE)));
        all.add(ready("C08", "所有老师每天最多上4节，王老师最多3节。",
                expected("TEACHER_MAX_PER_DAY", "HARD", Map.of("maxPerDay", 4), SlotMode.NONE),
                expected("TEACHER_MAX_PER_DAY", "HARD",
                        Map.of("teacher", "王明", "maxPerDay", 3), SlotMode.NONE)));
        all.add(ready("C09", "七年级1班每天最多8节，七年级2班最多7节。",
                expected("CLASS_MAX_PER_DAY", "HARD",
                        Map.of("class", "七年级1班", "maxPerDay", 8), SlotMode.NONE),
                expected("CLASS_MAX_PER_DAY", "HARD",
                        Map.of("class", "七年级2班", "maxPerDay", 7), SlotMode.NONE)));
        all.add(ready("C10", "数学在每个班每天最多上2节。",
                expected("COURSE_MAX_PER_DAY", "HARD",
                        Map.of("course", "数学", "maxPerDay", 2), SlotMode.NONE)));
        all.add(ready("C11", "每个班每天晚自习最多2节，九年级1班最多1节。",
                expected("EVENING_MAX", "HARD", Map.of("maxPerDay", 2), SlotMode.NONE),
                expected("EVENING_MAX", "HARD",
                        Map.of("class", "九年级1班", "maxPerDay", 1), SlotMode.NONE)));
        all.add(ready("C12", "体育尽量不要排下午第一节。",
                expected("SPORT_NOT_FIRST_AFTERNOON", "SOFT", Map.of("course", "体育"),
                        SlotMode.NONE)));
        all.add(ready("C13", "语文尽量分散到不同天，数学尽量排上午。",
                expected("COURSE_SPREAD", "SOFT", Map.of("course", "语文"), SlotMode.NONE),
                expected("MAIN_SUBJECT_MORNING", "SOFT",
                        Map.of("course", "数学", "beforePeriodNo", 4), SlotMode.NONE)));
        all.add(ready("C14", "王老师每天最多连上2节，其他老师最多3节。",
                expected("TEACHER_MAX_CONSECUTIVE", "HARD",
                        Map.of("maxConsecutive", 3), SlotMode.NONE),
                expected("TEACHER_MAX_CONSECUTIVE", "HARD",
                        Map.of("teacher", "王明", "maxConsecutive", 2), SlotMode.NONE)));
        all.add(ready("C15", "王老师每周三尽量整天无课。",
                expected("TEACHER_DAY_OFF", "SOFT", Map.of("teacher", "王明"),
                        days(3), SlotMode.DAY_ONLY)));
        all.add(boundary("C16", "张老师周三下午不排，体育不排晚自习。", Boundary.HOMONYM));
        all.add(boundary("C17", "王老师最近身体不好，给他少排一点课。", Boundary.VAGUE_AMOUNT));
        all.add(ready("C18", "体育不排每天第一节，语文不排晚自习。",
                expected("COURSE_UNAVAILABLE", "HARD", Map.of("course", "体育"),
                        slots().day(1, 1).day(2, 1).day(3, 1).day(4, 1).day(5, 1).build()),
                expected("COURSE_NOT_IN_EVENING", "HARD", Map.of("course", "语文"),
                        SlotMode.NONE)));
        all.add(boundary("C19", "毕业班的数学尽量上午上，其他年级不作要求。",
                Boundary.CLASS_SCOPE_UNSUPPORTED));
        all.add(boundary("C20", "王老师周三第99节不排。", Boundary.ILLEGAL_PERIOD));
        all.add(ready("C21", "体育课不排上午第1、2节。",
                expected("COURSE_UNAVAILABLE", "HARD", Map.of("course", "体育"),
                        slots().day(1, 1, 2).day(2, 1, 2).day(3, 1, 2)
                                .day(4, 1, 2).day(5, 1, 2).build())));
        all.add(ready("C22", "化学周三、周五上午第1节和下午第2节都不排。",
                expected("COURSE_UNAVAILABLE", "HARD", Map.of("course", "化学"),
                        slots().day(3, 1, 6).day(5, 1, 6).build())));
        return List.copyOf(all);
    }

    /** The sentences that count towards the 52/54 threshold. */
    public static List<Sentence> supportedSentences() {
        return sentences().stream().filter(Sentence::supported).toList();
    }

    /** The four sentences whose twelve runs must contain zero mistranslations. */
    public static List<Sentence> boundarySentences() {
        return sentences().stream().filter(sentence -> !sentence.supported()).toList();
    }

    // ------------------------------------------------------------------ builders

    private static Sentence ready(String code, String text, Expected... expected) {
        return new Sentence(code, text, true, List.of(expected), null);
    }

    private static Sentence boundary(String code, String text, Boundary boundary) {
        return new Sentence(code, text, false, List.of(), boundary);
    }

    private static Expected expected(
            String type, String strength, Map<String, Object> params, List<Slot> slots) {
        return new Expected(type, strength, params, slots, SlotMode.SLOTS);
    }

    private static Expected expected(
            String type, String strength, Map<String, Object> params, SlotMode mode) {
        return new Expected(type, strength, params, List.of(), mode);
    }

    private static Expected expected(
            String type, String strength, Map<String, Object> params, List<Slot> slots,
            SlotMode mode) {
        return new Expected(type, strength, params, slots, mode);
    }

    private static Slots slots() {
        return new Slots();
    }

    private static List<Slot> days(int... days) {
        List<Slot> slots = new ArrayList<>();
        for (int day : days) {
            slots.add(new Slot(day, null));
        }
        return slots;
    }

    /** Small builder: {@code slots().day(3, 5, 6).day(5, 1)}. */
    private static final class Slots {
        private final List<Slot> slots = new ArrayList<>();

        Slots day(int day, Integer... periods) {
            for (Integer period : periods) {
                slots.add(new Slot(day, period));
            }
            return this;
        }

        List<Slot> build() {
            return List.copyOf(slots);
        }
    }

    private static Map<String, Long> ordered(Object... pairs) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], (Long) pairs[index + 1]);
        }
        return Map.copyOf(map);
    }
}
