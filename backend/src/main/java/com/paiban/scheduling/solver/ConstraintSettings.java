package com.paiban.scheduling.solver;
import org.springframework.util.StringUtils;

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
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Immutable snapshot of the enabled constraints for one solve or validation.
 *
 * <p>Since the ticket-14 revision of ADR-0028 the type row keeps the switch and the
 * <em>defaults</em> (hard/soft, soft weight), while every requirement may pause itself
 * and override either default. A requirement is one user-managed rule: for the
 * slot-grouped types it spans several rows (one per expanded slot) joined by
 * {@code groupKey}; for every other type a row is its own requirement.
 *
 * <p>Every reader asks this class the same questions, so the solver, draft check,
 * publication readiness, substitution planner/verifier and the read-only CLI cannot
 * disagree about which rule is in force:
 *
 * <ul>
 *   <li>{@link #match} — the strongest control among the requirements that match one
 *       violation fact (hard wins, otherwise the highest soft weight). One fact scored
 *       through this method is counted once, never once per overlapping requirement.</li>
 *   <li>{@link #limitOrNull} and {@link #thresholdControl} — the exception that replaces
 *       the global default for one object, together with its own hard/soft choice.</li>
 * </ul>
 */
public final class ConstraintSettings {

    /**
     * One active row: its requirement key (null for a stand-alone row), the effective
     * controls after resolving the entry override against the type default, and its
     * parameters.
     */
    public record EntryRule(String group, boolean hard, int weight, Map<String, Object> params) {}

    /** One user-managed requirement: rows that share a group key, with one control. */
    public record Requirement(boolean hard, int weight, List<Map<String, Object>> entries) {

        public Requirement {
            entries = List.copyOf(entries);
        }

        public Control control() {
            return hard ? new Control(true, 0) : new Control(false, weight);
        }
    }

    /** What one matched fact must do: block hard or cost {@code weight} soft points. */
    public record Control(boolean hard, int weight) {}

    /** One enabled type: its defaults plus the requirements currently in force. */
    public record Rule(boolean hard, int weight, List<Requirement> requirements) {

        public Rule {
            requirements = List.copyOf(requirements);
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

    /** Legacy shape: one parameter map per type, no entry rows. */
    public static ConstraintSettings from(List<ConstraintConfig> configs, ObjectMapper mapper) {
        return from(configs, List.of(), mapper);
    }

    /**
     * Builds the snapshot from the type-level rows plus their entry rows.
     *
     * <p>An entry-backed type has no legacy fallback: "no active rows" means "not
     * configured" and never "every target". A paused row is simply absent from the
     * snapshot, which is what makes pausing preserve the content without evaluating it.
     */
    public static ConstraintSettings from(
            List<ConstraintConfig> configs, List<ConstraintEntry> entries, ObjectMapper mapper) {
        Map<String, List<ConstraintEntry>> entriesByType = groupEntries(entries);
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
            List<EntryRule> active = activeEntries(type, config, entriesByType, mapper);
            if (active.isEmpty() && !type.isEntryBacked()) {
                Map<String, Object> legacy = readParams(config.getParamsJson(), mapper);
                if (!legacy.isEmpty()) {
                    active = List.of(new EntryRule(
                            null, config.isHard(), config.getWeight(), legacy));
                }
            }
            rules.put(type, new Rule(config.isHard(), config.getWeight(), requirementsOf(active)));
        }
        return new ConstraintSettings(rules);
    }

    private static List<EntryRule> activeEntries(
            ConstraintType type, ConstraintConfig config,
            Map<String, List<ConstraintEntry>> entriesByType, ObjectMapper mapper) {
        List<ConstraintEntry> rows = new ArrayList<>(
                entriesByType.getOrDefault(type.name(), List.of()));
        rows.sort(Comparator.comparing(ConstraintEntry::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        List<EntryRule> active = new ArrayList<>();
        for (ConstraintEntry row : rows) {
            if (!row.isEnabled()) {
                continue;
            }
            Map<String, Object> params = readParams(row.getParamsJson(), mapper);
            // An empty map is a real entry only for an entry-backed type whose target is
            // optional: the explicit "every course / the sport class" row.
            if (params.isEmpty() && !type.isEntryBacked()) {
                continue;
            }
            boolean hard = row.getHard() != null ? row.getHard() : config.isHard();
            int weight = row.getWeight() != null ? row.getWeight() : config.getWeight();
            String group = row.getGroupKey() == null || !StringUtils.hasText(row.getGroupKey())
                    ? null : row.getGroupKey().trim();
            active.add(new EntryRule(group, hard, Math.max(0, Math.min(100, weight)), params));
        }
        return active;
    }

    /**
     * Groups active rows into requirements. Rows sharing a group key are one
     * requirement; an ungrouped row is its own. Save-time validation keeps the controls
     * inside a group identical, so "any hard, else highest weight" is only the tolerant
     * reading of a legacy or hand-edited configuration — never how a saved group behaves.
     */
    private static List<Requirement> requirementsOf(List<EntryRule> entries) {
        Map<String, List<EntryRule>> grouped = new LinkedHashMap<>();
        int index = 0;
        for (EntryRule entry : entries) {
            String key = entry.group() == null ? "row#" + index++ : "group#" + entry.group();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }
        List<Requirement> requirements = new ArrayList<>();
        for (List<EntryRule> group : grouped.values()) {
            boolean hard = group.stream().anyMatch(EntryRule::hard);
            int weight = group.stream()
                    .filter(entry -> !entry.hard())
                    .mapToInt(EntryRule::weight)
                    .max()
                    .orElse(0);
            requirements.add(new Requirement(hard, weight,
                    group.stream().map(EntryRule::params).toList()));
        }
        return requirements;
    }

    private static Map<String, List<ConstraintEntry>> groupEntries(List<ConstraintEntry> entries) {
        Map<String, List<ConstraintEntry>> byType = new LinkedHashMap<>();
        for (ConstraintEntry entry : entries == null ? List.<ConstraintEntry>of() : entries) {
            if (entry.getType() == null) {
                continue;
            }
            byType.computeIfAbsent(entry.getType(), key -> new ArrayList<>()).add(entry);
        }
        return byType;
    }

    private static Map<String, Object> readParams(String json, ObjectMapper mapper) {
        if (json == null || !StringUtils.hasText(json)) {
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

    /** The type-level default hard/soft choice (a requirement may override it). */
    public boolean hard(ConstraintType type) {
        Rule rule = rules.get(type);
        return rule != null && rule.hard();
    }

    /** The type-level default soft weight (a requirement may override it). */
    public int weight(ConstraintType type) {
        Rule rule = rules.get(type);
        return rule == null ? 0 : rule.weight();
    }

    /** Every active parameter row of the type, in stored order. */
    public List<Map<String, Object>> entries(ConstraintType type) {
        Rule rule = rules.get(type);
        if (rule == null) {
            return List.of();
        }
        List<Map<String, Object>> flattened = new ArrayList<>();
        rule.requirements().forEach(requirement -> flattened.addAll(requirement.entries()));
        return List.copyOf(flattened);
    }

    /** The requirements of one type, in stored order. */
    public List<Requirement> requirements(ConstraintType type) {
        Rule rule = rules.get(type);
        return rule == null ? List.of() : rule.requirements();
    }

    /**
     * The control of a type that carries no requirement rows (the six single switches).
     * Null for an entry-backed type: "no rows" means not configured, and inventing the
     * type default would evaluate a rule the user never wrote.
     */
    public Control control(ConstraintType type) {
        Rule rule = rules.get(type);
        if (rule == null || type.isEntryBacked()) {
            return null;
        }
        return new Control(rule.hard(), rule.weight());
    }

    /**
     * The strongest control among the requirements whose rows match one violation fact:
     * hard if any matching requirement is hard, otherwise the highest soft weight.
     *
     * <p>A requirement matches when <em>any</em> of its rows matches, which is how a
     * card that expanded into several slots still counts the same fact once. Returns
     * null when no requirement matches, i.e. the rule is not in force for that fact.
     */
    public Control match(ConstraintType type, Predicate<Map<String, Object>> entryMatches) {
        Rule rule = rules.get(type);
        if (rule == null) {
            return null;
        }
        boolean matched = false;
        boolean hard = false;
        int weight = -1;
        for (Requirement requirement : rule.requirements()) {
            boolean hit = false;
            for (Map<String, Object> entry : requirement.entries()) {
                if (entryMatches.test(entry)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            matched = true;
            if (requirement.hard()) {
                hard = true;
            } else {
                weight = Math.max(weight, requirement.weight());
            }
        }
        if (!matched) {
            return null;
        }
        return hard ? new Control(true, 0) : new Control(false, weight);
    }

    /**
     * True when any active entry targets the given course: a blank {@code courseId}
     * means "every course". Entries are combined with OR, so an "all courses" entry
     * overlapping a specific one still matches the course exactly once.
     */
    public boolean matchesCourse(ConstraintType type, Long courseId) {
        for (Map<String, Object> entry : entries(type)) {
            Long configured = longValue(entry.get("courseId"));
            if (configured == null || configured.equals(courseId)) {
                return true;
            }
        }
        return false;
    }

    /** The scoped value of one field for a course-targeted type; null when no entry matches. */
    public Integer courseScalar(ConstraintType type, Long courseId, String field) {
        for (Map<String, Object> entry : entries(type)) {
            if (Objects.equals(longValue(entry.get("courseId")), courseId)) {
                return intValue(entry.get(field));
            }
        }
        return null;
    }

    /** The text value of one field for a course-targeted type; null when no entry matches. */
    public String courseText(ConstraintType type, Long courseId, String field) {
        for (Map<String, Object> entry : entries(type)) {
            if (Objects.equals(longValue(entry.get("courseId")), courseId)) {
                Object value = entry.get(field);
                return value == null ? null : value.toString();
            }
        }
        return null;
    }

    /**
     * The effective threshold for one object of a scoped threshold type:
     *
     * <pre>
     * the object's own exception, else the global default, else no limit at all
     * </pre>
     *
     * <p>"No limit at all" is a real answer: an exceptions-only configuration never
     * invents a default for the objects it did not list.
     */
    public Integer limitOrNull(ConstraintType type, Long objectId) {
        ConstraintType.ThresholdScope scope = type.thresholdScope();
        if (scope == null) {
            return null;
        }
        Integer global = null;
        for (Map<String, Object> entry : entries(type)) {
            Integer value = intValue(entry.get(scope.valueField()));
            if (value == null) {
                continue;
            }
            Long target = longValue(entry.get(scope.objectField()));
            if (target == null) {
                if (global == null) {
                    global = value;
                }
            } else if (objectId != null && target.equals(objectId)) {
                return value;
            }
        }
        return global;
    }

    /**
     * The control that belongs to the threshold actually selected for this object: the
     * exception's own hard/soft choice and weight, else the global entry's. The global
     * is never layered back on top of a replacing exception (ticket-14 spec: 先替代再判重叠).
     */
    public Control thresholdControl(ConstraintType type, Long objectId) {
        ConstraintType.ThresholdScope scope = type.thresholdScope();
        if (scope == null) {
            return null;
        }
        Control global = null;
        for (Requirement requirement : requirements(type)) {
            for (Map<String, Object> entry : requirement.entries()) {
                if (intValue(entry.get(scope.valueField())) == null) {
                    continue;
                }
                Long target = longValue(entry.get(scope.objectField()));
                if (target != null && target.equals(objectId)) {
                    return requirement.control();
                }
                if (target == null && global == null) {
                    global = requirement.control();
                }
            }
        }
        return global;
    }

    /** True when {@code count} still respects this object's limit; unlimited is always within. */
    public boolean withinLimit(ConstraintType type, Long objectId, int count) {
        Integer limit = limitOrNull(type, objectId);
        return limit == null || count <= limit;
    }

    /** Reads an entry's numeric field, tolerating both numbers and numeric text. */
    public static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
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
