package com.paiban.substitution.plan;

import java.util.Comparator;

/**
 * Ordering presets. Impact days always beat the other criteria in the default
 * preset; {@code min-teachers} is for schools that care more about how many
 * people they have to call, and {@code stable-timetable} minimises disruption to
 * the soft rules first.
 */
public enum RankingPreset {

    MIN_IMPACT("min-impact", "最小影响") {
        @Override
        public Comparator<CandidatePlan> comparator() {
            return Comparator.comparingInt(CandidatePlan::impactDays)
                    .thenComparing(plan -> !plan.sameWeek())
                    .thenComparingInt(CandidatePlan::impactTeachers)
                    .thenComparingInt(CandidatePlan::softPenalty)
                    .thenComparingInt(CandidatePlan::cycleLength);
        }
    },
    MIN_TEACHERS("min-teachers", "最少教师") {
        @Override
        public Comparator<CandidatePlan> comparator() {
            return Comparator.comparingInt(CandidatePlan::impactTeachers)
                    .thenComparingInt(CandidatePlan::impactDays)
                    .thenComparing(plan -> !plan.sameWeek())
                    .thenComparingInt(CandidatePlan::softPenalty)
                    .thenComparingInt(CandidatePlan::cycleLength);
        }
    },
    STABLE_TIMETABLE("stable-timetable", "最稳课表") {
        @Override
        public Comparator<CandidatePlan> comparator() {
            return Comparator.comparingInt(CandidatePlan::softPenalty)
                    .thenComparingInt(CandidatePlan::impactDays)
                    .thenComparing(plan -> !plan.sameWeek())
                    .thenComparingInt(CandidatePlan::impactTeachers)
                    .thenComparingInt(CandidatePlan::cycleLength);
        }
    };

    private final String code;
    private final String label;

    RankingPreset(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public abstract Comparator<CandidatePlan> comparator();

    public static RankingPreset fromCode(String code) {
        for (RankingPreset preset : values()) {
            if (preset.code.equals(code)) {
                return preset;
            }
        }
        return MIN_IMPACT;
    }
}
