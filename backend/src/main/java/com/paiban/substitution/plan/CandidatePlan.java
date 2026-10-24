package com.paiban.substitution.plan;

import java.util.Comparator;
import java.util.List;

/** One candidate swap cycle, already ranked by the planner. */
public record CandidatePlan(List<PlanMove> moves,
                            int impactDays,
                            int impactTeachers,
                            int softPenalty,
                            int cycleLength,
                            boolean sameWeek) {

    public CandidatePlan {
        moves = List.copyOf(moves);
    }

    /** Stable identity: the same lesson landing on the same slot is the same plan. */
    public String key() {
        return moves.stream()
                .sorted(Comparator.comparingLong(PlanMove::entryId))
                .map(move -> move.entryId() + ":" + move.toDate() + ":" + move.toPeriodId())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }
}
