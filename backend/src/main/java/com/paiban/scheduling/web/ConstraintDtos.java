package com.paiban.scheduling.web;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ConstraintDtos {

    private ConstraintDtos() {}

    public record ParamDefDto(String name, String label, String type, boolean required,
                              Integer min, Integer max, Object defaultValue, List<String> options) {}

    public record ConstraintTypeDto(String code, String name, String category, String categoryLabel,
                                    String description, boolean defaultHard, int defaultWeight,
                                    List<ParamDefDto> params) {}

    /** One parameter entry with its stable identity. */
    public record ConstraintEntryDto(Long id, Map<String, Object> params) {}

    public record ConstraintConfigDto(Long id, String type, String name, String category,
                                      String categoryLabel, String description,
                                      Map<String, Object> params, int weight, boolean hard,
                                      boolean enabled, List<ConstraintEntryDto> entries) {}

    /**
     * {@code params} is the legacy single-entry form: when {@code entries} is null
     * it becomes the only entry. {@code entries} carries the multi-entry form.
     */
    public record ConstraintEntryRequest(Long id, Map<String, Object> params) {}

    public record ConstraintConfigRequest(String type,
                                          Map<String, Object> params,
                                          Integer weight,
                                          Boolean hard,
                                          Boolean enabled,
                                          List<ConstraintEntryRequest> entries) {}

    public record SaveConstraintsRequest(List<ConstraintConfigRequest> configs) {}

    public record TemplateDto(Long id, String name, Instant createdAt, int configCount) {}

    public record SaveTemplateRequest(@NotBlank(message = "模板名称不能为空") String name) {}
}
