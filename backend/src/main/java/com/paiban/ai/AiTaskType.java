package com.paiban.ai;

/**
 * AI task slots that can be bound to a provider/model.
 *
 * <p>Persisted as the lower-case {@link #value()} so the database and the
 * cross-ticket API contract share one spelling.
 */
public enum AiTaskType {

    DATA_IMPORT("data_import"),
    SUBSTITUTION_RECOMMEND("substitution_recommend"),
    NATURAL_LANGUAGE_CONSTRAINTS("natural_language_constraints");

    private final String value;

    AiTaskType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static AiTaskType fromValue(String value) {
        for (AiTaskType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的 AI 任务类型: " + value);
    }
}
