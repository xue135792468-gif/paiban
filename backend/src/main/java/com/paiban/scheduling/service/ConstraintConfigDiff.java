package com.paiban.scheduling.service;

import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintEntryDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The effective change a save actually wrote, computed from the configuration before it
 * and the response it returned (ticket 14/10). The audit stores this diff next to the
 * user's proposal decisions, so "what I confirmed" and "what became effective" are both
 * provable without reconstructing anything from the current configuration.
 */
public final class ConstraintConfigDiff {

    /** One type whose controls or entries changed. */
    public record Change(String type,
                         Map<String, Object> beforeControl,
                         Map<String, Object> afterControl,
                         List<ConstraintEntryDto> addedEntries,
                         List<ConstraintEntryDto> removedEntries) {}

    public record Diff(List<String> addedTypes, List<String> removedTypes,
                       List<Change> changedTypes) {}

    private ConstraintConfigDiff() {}

    public static Diff diff(List<ConstraintConfigDto> before, List<ConstraintConfigDto> after) {
        Map<String, ConstraintConfigDto> beforeByType = byType(before);
        Map<String, ConstraintConfigDto> afterByType = byType(after);
        List<String> addedTypes = new ArrayList<>();
        List<String> removedTypes = new ArrayList<>();
        List<Change> changedTypes = new ArrayList<>();
        for (ConstraintConfigDto current : after == null ? List.<ConstraintConfigDto>of() : after) {
            ConstraintConfigDto previous = beforeByType.get(current.type());
            if (previous == null) {
                addedTypes.add(current.type());
                continue;
            }
            Change change = change(previous, current);
            if (change != null) {
                changedTypes.add(change);
            }
        }
        for (String type : beforeByType.keySet()) {
            if (!afterByType.containsKey(type)) {
                removedTypes.add(type);
            }
        }
        return new Diff(List.copyOf(addedTypes), List.copyOf(removedTypes),
                List.copyOf(changedTypes));
    }

    private static Change change(ConstraintConfigDto before, ConstraintConfigDto after) {
        List<ConstraintEntryDto> beforeEntries = entries(before);
        List<ConstraintEntryDto> afterEntries = entries(after);
        Set<String> beforeKeys = new LinkedHashSet<>();
        beforeEntries.forEach(entry -> beforeKeys.add(entryKey(entry)));
        Set<String> afterKeys = new LinkedHashSet<>();
        afterEntries.forEach(entry -> afterKeys.add(entryKey(entry)));

        List<ConstraintEntryDto> added = new ArrayList<>();
        for (int index = 0; index < afterEntries.size(); index++) {
            if (!beforeKeys.contains(entryKey(afterEntries.get(index)))) {
                added.add(afterEntries.get(index));
            }
        }
        List<ConstraintEntryDto> removed = new ArrayList<>();
        for (int index = 0; index < beforeEntries.size(); index++) {
            if (!afterKeys.contains(entryKey(beforeEntries.get(index)))) {
                removed.add(beforeEntries.get(index));
            }
        }
        Map<String, Object> beforeControl = control(before);
        Map<String, Object> afterControl = control(after);
        if (beforeControl.equals(afterControl) && added.isEmpty() && removed.isEmpty()) {
            return null;
        }
        return new Change(after.type(), beforeControl, afterControl,
                List.copyOf(added), List.copyOf(removed));
    }

    private static Map<String, Object> control(ConstraintConfigDto config) {
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("enabled", config.enabled());
        control.put("hard", config.hard());
        control.put("weight", config.weight());
        return control;
    }

    private static String entryKey(ConstraintEntryDto entry) {
        StringBuilder key = new StringBuilder(entryKeyPart(entry.params()));
        key.append("|enabled=").append(entry.enabled());
        key.append("|hard=").append(entry.hard());
        key.append("|weight=").append(entry.weight());
        key.append("|group=").append(entry.groupKey());
        return key.toString();
    }

    /** Parameters sorted, numbers normalized, so JSON width never decides equality. */
    private static String entryKeyPart(Map<String, Object> params) {
        Map<String, Object> sorted = new TreeMap<>();
        if (params != null) {
            params.forEach((key, value) -> sorted.put(key,
                    value instanceof Number number ? (Object) number.longValue() : value));
        }
        StringBuilder key = new StringBuilder();
        sorted.forEach((name, value) -> {
            if (key.length() > 0) {
                key.append('|');
            }
            key.append(name).append('=').append(Objects.toString(value, ""));
        });
        return key.toString();
    }

    private static List<ConstraintEntryDto> entries(ConstraintConfigDto config) {
        return config.entries() == null ? List.of() : config.entries();
    }

    private static Map<String, ConstraintConfigDto> byType(List<ConstraintConfigDto> configs) {
        Map<String, ConstraintConfigDto> byType = new LinkedHashMap<>();
        if (configs != null) {
            configs.forEach(config -> byType.put(config.type(), config));
        }
        return byType;
    }
}
