package com.paiban.common.event;

/** Published after a semester row is created so other modules can seed defaults. */
public record SemesterCreatedEvent(Long semesterId) {}
