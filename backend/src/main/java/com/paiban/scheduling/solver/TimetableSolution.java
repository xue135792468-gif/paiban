package com.paiban.scheduling.solver;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import java.util.List;

/**
 * Annotations are placed on the getters because Timefold resolves solution
 * properties through the bean property accessor.
 */
@PlanningSolution
public class TimetableSolution {

    private List<Lesson> lessons;
    private List<PeriodSlot> periods;
    private HardSoftScore score;

    public TimetableSolution() {}

    public TimetableSolution(List<Lesson> lessons, List<PeriodSlot> periods) {
        this.lessons = lessons;
        this.periods = periods;
    }

    @PlanningEntityCollectionProperty
    public List<Lesson> getLessons() {
        return lessons;
    }

    public void setLessons(List<Lesson> lessons) {
        this.lessons = lessons;
    }

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "periodRange")
    public List<PeriodSlot> getPeriods() {
        return periods;
    }

    public void setPeriods(List<PeriodSlot> periods) {
        this.periods = periods;
    }

    @PlanningScore
    public HardSoftScore getScore() {
        return score;
    }

    public void setScore(HardSoftScore score) {
        this.score = score;
    }
}
