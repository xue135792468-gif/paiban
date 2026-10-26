package com.paiban.scheduling.solver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of the enabled constraints for one solve or validation.
 *
 * <p>Since ADR-0028 a type may own several parameter entries (teacher A on Monday
 * first period, teacher B on Tuesday third). The type-level {@code enabled},
 * {@code hard} and {@code weight} stay shared; {@link #entries(ConstraintType)}
 * exposes the whole list. The original single-value accessors
 * ({@link #params}, {@link #longParam}, {@link #intParam}, {@link #textParam},
 * {@link #matchesCourse}) keep reading the first entry, which is exactly the old
 * value for a legacy configuration and keeps the 23 not-yet-expanded types
 * behaving as before.
 */
public final class ConstraintSettings {

    /** One enabled type: shared controls plus its parameter entries in id order. */
    public record Rule(boolean hard, int weight, List<Map<String, Object>> entries) {

        public Rule {
            entries = List.copyOf(entries);
        }
    }

    private final Map<ConstraintType, Rule> rules;

    private ConstraintSettings(Map<ConstraintType, Rule> rules) {
        this.rules = rules;
    }

    public static ConstraintSettings defaults() {
        Map<ConstraintType, Rule> rules = new EnumMap<>(ConstraintType.class);
        for (ConstraintType type : List.of(
                ConstraintType.TEACHER_CONFLICT,
                ConstraintType.CLASS_CONFLICT,
                ConstraintType.ROOM_CONFLICT,
                ConstraintType.ROOM_CAPACITY_FIT)) {
            rules.put(type, new Rule(true, 100, List.of()));
        }
        return new ConstraintSettings(rules);
    }

    /**
     * Legacy shape: one parameter map per type, no entry rows. Equivalent to
     * {@code from(configs, List.of(), mapper)}.
     */
    public static ConstraintSettings from(List<ConstraintConfig> configs, ObjectMapper mapper) {
        return from(configs, List.of(), mapper);
    }

    /**
     * Builds the snapshot from the type-level rows plus their entry rows. A type
     * with no entry rows falls back to its legacy single {@code params_json}, so a
     * configuration written before V26 keeps its exact meaning; an empty
     * {@code params_json} yields no entries, which matches the old "empty params"
     * verdict.
     */
    public static ConstraintSettings from(
            List<ConstraintConfig> configs, List<ConstraintEntry> entries, ObjectMapper mapper) {
        Map<String, List<Map<String, Object>>> entriesByType = groupEntries(entries, mapper);
        Map<ConstraintType, Rule> rules = new EnumMap<>(ConstraintType.class);
        for (ConstraintConfig config : configs) {
            if (!config.isEnabled()) {
                continue;
            }
            ConstraintType type;
            try {
                type = ConstraintType.valueOf(config.getType());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            List<Map<String, Object>> typeEntries = entriesByType.get(type.name());
            if (typeEntries == null || typeEntries.isEmpty()) {
                Map<String, Object> legacy = readParams(config.getParamsJson(), mapper);
                typeEntries = legacy.isEmpty() ? List.of() : List.of(legacy);
            }
            rules.put(type, new Rule(config.isHard(), config.getWeight(), typeEntries));
        }
        return new ConstraintSettings(rules);
    }

    private static Map<String, List<Map<String, Object>>> groupEntries(
            List<ConstraintEntry> entries, ObjectMapper mapper) {
        List<ConstraintEntry> ordered = new ArrayList<>(entries == null ? List.of() : entries);
        ordered.sort(Comparator.comparing(ConstraintEntry::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        for (ConstraintEntry entry : ordered) {
            if (entry.getType() == null) {
                continue;
            }
            Map<String, Object> params = readParams(entry.getParamsJson(), mapper);
            if (params.isEmpty()) {
                continue;
            }
            byType.computeIfAbsent(entry.getType(), key -> new ArrayList<>()).add(params);
        }
        return byType;
    }

    private static Map<String, Object> readParams(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    public boolean enabled(ConstraintType type) {
        return rules.containsKey(type);
    }

    public boolean hard(ConstraintType type) {
        Rule rule = rules.get(type);
        return rule != null && rule.hard();
    }

    public int weight(ConstraintType type) {
        Rule rule = rules.get(type);
        return rule == null ? 0 : rule.weight();
    }

    /** Every parameter entry of the type, in stored id order. */
    public List<Map<String, Object>> entries(ConstraintType type) {
        Rule rule = rules.get(type);
        return rule == null ? List.of() : rule.entries();
    }

    /** The first entry, i.e. the pre-ADR-0028 single value. */
    public Map<String, Object> params(ConstraintType type) {
        List<Map<String, Object>> entries = entries(type);
        return entries.isEmpty() ? Map.of() : entries.get(0);
    }

    public Long longParam(ConstraintType type, String name) {
        return longValue(params(type).get(name));
    }

    public Integer intParam(ConstraintType type, String name) {
        Long value = longParam(type, name);
        return value == null ? null : value.intValue();
    }

    public String textParam(ConstraintType type, String name) {
        Object value = params(type).get(name);
        return value == null ? null : value.toString();
    }

    /** True when the constraint targets every course, or the given course. */
    public boolean matchesCourse(ConstraintType type, Long courseId) {
        Long configured = longParam(type, "courseId");
        return configured == null || configured.equals(courseId);
    }

    /** Reads an entry's numeric field, tolerating both numbers and numeric text. */
    public static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    public static Integer intValue(Object value) {
        Long parsed = longValue(value);
        return parsed == null ? null : parsed.intValue();
    }
}
