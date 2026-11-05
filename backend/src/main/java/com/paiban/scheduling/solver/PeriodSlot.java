package com.paiban.scheduling.solver;

import lombok.Getter;

/** Immutable time slot fact used by the solver. */
@Getter
public class PeriodSlot {

    private final Long id;
    private final int dayOfWeek;
    private final int periodNo;
    private final boolean firstOfDay;
    private final boolean lastOfDay;

    public PeriodSlot(
            Long id,
            int dayOfWeek,
            int periodNo,
            boolean firstOfDay,
            boolean lastOfDay) {
        this.id = id;
        this.dayOfWeek = dayOfWeek;
        this.periodNo = periodNo;
        this.firstOfDay = firstOfDay;
        this.lastOfDay = lastOfDay;
    }

    @Override
    public String toString() {
        return "周" + dayOfWeek + "第" + periodNo + "节";
    }
}
