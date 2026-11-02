package com.paiban.common.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The shared presets must keep the exact key order the old
 * {@code substitution.plan.RankingPreset} had; the stable key is a final
 * tie-break only.
 */
class ExchangeRankingPresetTest {

    @Test
    void minImpactPutsImpactDaysFirst() {
        Metrics sameDayThreeTeachers = new Metrics(1, true, 3, 0, 3, "a");
        Metrics crossDayTwoTeachers = new Metrics(2, false, 2, 0, 2, "b");

        assertThat(sorted(ExchangeRankingPreset.MIN_IMPACT, sameDayThreeTeachers, crossDayTwoTeachers))
                .containsExactly(sameDayThreeTeachers, crossDayTwoTeachers);
    }

    @Test
    void minTeachersPutsDistinctTeachersFirst() {
        Metrics sameDayThreeTeachers = new Metrics(1, true, 3, 0, 3, "a");
        Metrics crossDayTwoTeachers = new Metrics(2, false, 2, 0, 2, "b");

        assertThat(sorted(ExchangeRankingPreset.MIN_TEACHERS, sameDayThreeTeachers, crossDayTwoTeachers))
                .containsExactly(crossDayTwoTeachers, sameDayThreeTeachers);
    }

    @Test
    void stableTimetablePutsSoftPenaltyFirst() {
        Metrics lowPenalty = new Metrics(5, false, 4, 0, 4, "a");
        Metrics highPenalty = new Metrics(1, true, 1, 9, 1, "b");

        assertThat(sorted(ExchangeRankingPreset.STABLE_TIMETABLE, lowPenalty, highPenalty))
                .containsExactly(lowPenalty, highPenalty);
    }

    @Test
    void stableKeyBreaksAFullTieAndTheOrderIsRepeatable() {
        Metrics first = new Metrics(2, true, 2, 1, 3, "entry-1");
        Metrics second = new Metrics(2, true, 2, 1, 3, "entry-2");

        for (ExchangeRankingPreset preset : ExchangeRankingPreset.values()) {
            assertThat(sorted(preset, second, first)).containsExactly(first, second);
            assertThat(sorted(preset, second, first)).containsExactly(first, second);
        }
    }

    @Test
    void stableKeyNeverOverridesAnExistingKey() {
        // "z" sorts last, but a smaller impact-day count still wins.
        Metrics fewerDays = new Metrics(1, true, 3, 0, 3, "z");
        Metrics moreDays = new Metrics(2, true, 1, 0, 1, "a");

        assertThat(sorted(ExchangeRankingPreset.MIN_IMPACT, moreDays, fewerDays))
                .containsExactly(fewerDays, moreDays);
    }

    private List<Metrics> sorted(ExchangeRankingPreset preset, Metrics... metrics) {
        return new ArrayList<>(Stream.of(metrics).sorted(preset.comparator()).toList());
    }

    private record Metrics(int impactDays, boolean sameWeek, int impactTeachers,
                           int softPenalty, int cycleLength, String stableKey)
            implements ExchangePlanMetrics {}
}
