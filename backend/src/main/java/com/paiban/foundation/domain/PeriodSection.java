package com.paiban.foundation.domain;

public enum PeriodSection {
    MORNING("上午"),
    AFTERNOON("下午"),
    EVENING("晚自习");

    private final String label;

    PeriodSection(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
