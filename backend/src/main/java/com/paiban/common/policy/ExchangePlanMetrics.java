package com.paiban.common.policy;

/**
 * Ranking metrics shared by every exchange-plan producer: the draft swap search
 * and the published-timetable substitution planner.
 *
 * <p>Keeping the metrics in the neutral {@code common.policy} package lets
 * {@code scheduling} consume the same ordering rules without importing anything
 * from {@code substitution} (contracts §3, no reverse module dependency).
 */
public interface ExchangePlanMetrics {

    /** Distinct dates touched by the plan. */
    int impactDays();

    /** True when every move stays inside the same ISO week. */
    boolean sameWeek();

    /** Distinct teachers involved, regardless of how many lessons they move. */
    int impactTeachers();

    /** Soft-constraint penalty delta; lower is steadier. */
    int softPenalty();

    /** Number of lessons moved, i.e. the cycle length. */
    int cycleLength();

    /** Deterministic identity used only as the final tie-break. */
    String stableKey();
}
