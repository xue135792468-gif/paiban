package com.paiban.scheduling.web;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ConstraintDtos {

    private ConstraintDtos() {}

    public record ParamDefDto(String name, String label, String type, boolean required,
                              Integer min, Integer max, Object defaultValue, List<String> options) {}

    /**
     * A catalogue entry. {@code entryBacked} says whether the type stores its
     * parameters as entry rows (so an empty list means "not configured"), the two
     * threshold fields name the entry parameters that scope a threshold, and
     * {@code slotGrouped} says the editor shows one card with several weekday/period
     * rows for one requirement. All three are read-only hints for the page.
     */
    public record ConstraintTypeDto(String code, String name, String category, String categoryLabel,
                                    String description, boolean defaultHard, int defaultWeight,
                                    boolean entryBacked, boolean slotGrouped,
                                    String thresholdObjectField,
                                    String thresholdValueField, List<ParamDefDto> params) {}

    /**
     * One parameter entry with its stable identity and its controls. {@code enabled}
     * pauses the requirement while keeping it; {@code hard}/{@code weight} null mean
     * "inherit the type default", which is how an upgraded configuration keeps its
     * original meaning. {@code groupKey} joins several rows into one requirement.
     */
    public record ConstraintEntryDto(Long id, Map<String, Object> params, boolean enabled,
                                     Boolean hard, Integer weight, String groupKey) {}

    public record ConstraintConfigDto(Long id, String type, String name, String category,
                                      String categoryLabel, String description,
                                      Map<String, Object> params, int weight, boolean hard,
                                      boolean enabled, List<ConstraintEntryDto> entries) {}

    /**
     * {@code params} is the legacy single-entry form: when {@code entries} is null
     * it becomes the only entry. {@code entries} carries the multi-entry form.
     */
    public record ConstraintEntryRequest(Long id, Map<String, Object> params,
                                         Boolean enabled, Boolean hard, Integer weight,
                                         String groupKey) {}

    public record ConstraintConfigRequest(String type,
                                          Map<String, Object> params,
                                          Integer weight,
                                          Boolean hard,
                                          Boolean enabled,
                                          List<ConstraintEntryRequest> entries) {}

    public record SaveConstraintsRequest(List<ConstraintConfigRequest> configs) {}

    /**
     * {@code configCount} is the number of type rows the template holds,
     * {@code entryCount} the number of parameter entries it will restore.
     */
    public record TemplateDto(Long id, String name, Instant createdAt, int configCount,
                              int entryCount) {}

    /**
     * Optional body of a template apply. {@code classMapping} names the target-semester
     * class for each class id the template carries; an id that is not named is reported
     * as invalid instead of being matched by name.
     */
    public record TemplateApplyRequest(Map<Long, Long> classMapping) {}

    /**
     * One template reference that cannot be carried into the target semester:
     * {@code position} is the 1-based entry inside its type, {@code name} the object's
     * current name when it still exists somewhere.
     */
    public record TemplateReferenceIssue(String type, String typeName, int position, String field,
                                         String fieldLabel, Long objectId, String name,
                                         String reason) {}

    /**
     * Dry run of an apply: what it would restore ({@code configCount}/{@code entryCount}),
     * which references are invalid, and any other reason the save would be refused.
     */
    public record TemplateApplyPreview(int configCount, int entryCount,
                                       List<TemplateReferenceIssue> invalidReferences,
                                       List<String> errors) {}

    public record SaveTemplateRequest(@NotBlank(message = "模板名称不能为空") String name) {}
}
