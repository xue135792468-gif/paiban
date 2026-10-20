package com.paiban.scheduling.solver;

import com.paiban.foundation.domain.PeriodSection;
import lombok.Getter;

/** Immutable time slot fact used by the solver. */
@Getter
public class PeriodSlot {

    private final Long id;
    private final int dayOfWeek;
    private final int periodNo;
    private final PeriodSection section;
    private final boolean firstOfDay;
    private final boolean lastOfDay;
    private final boolean firstAfternoon;

    public PeriodSlot(
            Long id,
            int dayOfWeek,
            int periodNo,
            PeriodSection section,
            boolean firstOfDay,
            boolean lastOfDay,
            boolean firstAfternoon) {
        this.id = id;
        this.dayOfWeek = dayOfWeek;
        this.periodNo = periodNo;
        this.section = section;
        this.firstOfDay = firstOfDay;
        this.lastOfDay = lastOfDay;
        this.firstAfternoon = firstAfternoon;
    }

    @Override
    public String toString() {
        return "周" + dayOfWeek + "第" + periodNo + "节";
    }
}
