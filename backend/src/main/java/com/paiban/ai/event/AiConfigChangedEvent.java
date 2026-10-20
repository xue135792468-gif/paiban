package com.paiban.ai.event;

/**
 * Published after any write that can change task routing: providers, models and
 * task assignments. The gateway's config cache is dropped on receipt.
 */
public record AiConfigChangedEvent(String source) {}
