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

    public record ConstraintConfigDto(Long id, String type, String name, String category,
                                      String categoryLabel, String description,
                                      Map<String, Object> params, int weight, boolean hard,
                                      boolean enabled) {}

    public record ConstraintConfigRequest(String type,
                                          Map<String, Object> params,
                                          Integer weight,
                                          Boolean hard,
                                          Boolean enabled) {}

    public record SaveConstraintsRequest(List<ConstraintConfigRequest> configs) {}

    public record TemplateDto(Long id, String name, Instant createdAt, int configCount) {}

    public record SaveTemplateRequest(@NotBlank(message = "模板名称不能为空") String name) {}
}
