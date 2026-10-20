package com.paiban.scheduling.solver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Immutable snapshot of the enabled constraints for one solve or validation. */
public final class ConstraintSettings {

    public record Rule(boolean hard, int weight, Map<String, Object> params) {}

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
            rules.put(type, new Rule(true, 100, Map.of()));
        }
        return new ConstraintSettings(rules);
    }

    public static ConstraintSettings from(List<ConstraintConfig> configs, ObjectMapper mapper) {
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
            Map<String, Object> params = Map.of();
            if (config.getParamsJson() != null && !config.getParamsJson().isBlank()) {
                try {
                    params = mapper.readValue(
                            config.getParamsJson(), new TypeReference<Map<String, Object>>() {});
                } catch (Exception ignored) {
                    params = Map.of();
                }
            }
            rules.put(type, new Rule(config.isHard(), config.getWeight(), params));
        }
        return new ConstraintSettings(rules);
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

    public Map<String, Object> params(ConstraintType type) {
        Rule rule = rules.get(type);
        return rule == null ? Map.of() : rule.params();
    }

    public Long longParam(ConstraintType type, String name) {
        Object value = params(type).get(name);
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
}
