package com.paiban.substitution.plan;

import java.util.List;

/** Planner output: ranked candidates plus why nothing was found. */
public record PlanningResult(List<CandidatePlan> candidates,
                             List<PlanDiagnosis> diagnoses,
                             int nodesVisited,
                             boolean timedOut) {

    public PlanningResult {
        candidates = List.copyOf(candidates);
        diagnoses = List.copyOf(diagnoses);
    }

    public static PlanningResult none(List<PlanDiagnosis> diagnoses, int nodesVisited, boolean timedOut) {
        return new PlanningResult(List.of(), diagnoses, nodesVisited, timedOut);
    }
}
