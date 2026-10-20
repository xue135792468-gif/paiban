package com.paiban.scheduling.solver;

import lombok.Getter;

/** Immutable room fact used by the solver. */
@Getter
public class RoomSlot {

    private final Long id;
    private final String name;
    private final String type;
    private final int capacity;

    public RoomSlot(Long id, String name, String type, int capacity) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.capacity = capacity;
    }

    @Override
    public String toString() {
        return name;
    }
}
