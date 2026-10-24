package com.paiban.substitution.plan;

/** A structured reason why a target (or the whole search) has no cycle. */
public record PlanDiagnosis(String code, String message, Long entryId, Long periodId) {}
