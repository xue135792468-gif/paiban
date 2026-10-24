package com.paiban.scheduling.constraint;

/**
 * A rule hit. {@code code} is the {@code ConstraintType} name for preset rules so
 * the API can expose a stable identifier; the message is what teachers read.
 */
public record ConstraintViolation(String code, String message, Long lessonId, boolean hard) {}
