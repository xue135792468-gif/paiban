package com.paiban.common.event;

/** Published after a draft becomes the semester baseline. */
public record TimetablePublishedEvent(Long semesterId, Long draftId, String publishedBy) {}
