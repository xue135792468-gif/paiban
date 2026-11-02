package com.paiban.common.policy;

import java.util.Comparator;

/**
 * Ordering presets shared by draft swaps and published-timetable substitutions.
 *
 * <p>The key order is byte-for-byte the same as the former
 * {@code substitution.plan.RankingPreset}: the only addition is a deterministic
 * {@link ExchangePlanMetrics#stableKey()} tie-break that runs after every
 * existing key, so it can never change the outcome of a comparison the old
 * presets already decided.
 */
public enum ExchangeRankingPreset {

    MIN_IMPACT("min-impact", "最小影响") {
        @Override
        public Comparator<ExchangePlanMetrics> comparator() {
            return Comparator.comparingInt(ExchangePlanMetrics::impactDays)
                    .thenComparing(metrics -> !metrics.sameWeek())
                    .thenComparingInt(ExchangePlanMetrics::impactTeachers)
                    .thenComparingInt(ExchangePlanMetrics::softPenalty)
                    .thenComparingInt(ExchangePlanMetrics::cycleLength)
                    .thenComparing(ExchangePlanMetrics::stableKey);
        }
    },
    MIN_TEACHERS("min-teachers", "最少教师") {
        @Override
        public Comparator<ExchangePlanMetrics> comparator() {
            return Comparator.comparingInt(ExchangePlanMetrics::impactTeachers)
                    .thenComparingInt(ExchangePlanMetrics::impactDays)
                    .thenComparing(metrics -> !metrics.sameWeek())
                    .thenComparingInt(ExchangePlanMetrics::softPenalty)
                    .thenComparingInt(ExchangePlanMetrics::cycleLength)
                    .thenComparing(ExchangePlanMetrics::stableKey);
        }
    },
    STABLE_TIMETABLE("stable-timetable", "最稳课表") {
        @Override
        public Comparator<ExchangePlanMetrics> comparator() {
            return Comparator.comparingInt(ExchangePlanMetrics::softPenalty)
                    .thenComparingInt(ExchangePlanMetrics::impactDays)
                    .thenComparing(metrics -> !metrics.sameWeek())
                    .thenComparingInt(ExchangePlanMetrics::impactTeachers)
                    .thenComparingInt(ExchangePlanMetrics::cycleLength)
                    .thenComparing(ExchangePlanMetrics::stableKey);
        }
    };

    private final String code;
    private final String label;

    ExchangeRankingPreset(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public abstract Comparator<ExchangePlanMetrics> comparator();

    public static ExchangeRankingPreset fromCode(String code) {
        for (ExchangeRankingPreset preset : values()) {
            if (preset.code.equals(code)) {
                return preset;
            }
        }
        return MIN_IMPACT;
    }
}
