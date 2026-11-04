package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.deletion.DeletionRestoreHandler;
import com.paiban.common.deletion.RecentDeletion;
import com.paiban.common.deletion.RecentDeletionWriter;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.solver.ConstraintSettings;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Rule-card deletion and restore (ticket 04). A rule card is a group of
 * {@code constraint_entry} rows sharing {@code "<cardId>:<index>"}; deleting it
 * stamps {@code deleted_at} on those rows, pauses them and, when no live row of the
 * type remains, soft-deletes the type row too. The card is registered in the 7-day
 * recycle bin with the entry ids it needs to revive.
 *
 * <p>This is deliberately not a second rule store: the evaluating snapshot
 * ({@link ConstraintSettings}) and every list already skip {@code deleted_at}, so the
 * solver, draft check, publication and substitution keep reading one configuration.
 */
@Service
public class RuleLifecycleService implements DeletionRestoreHandler {

    static final String ENTITY_TYPE = "CONSTRAINT_RULE";
    private static final String KIND_LABEL = "排课规则";
    private static final String SWITCH_PREFIX = "switch:";
    /** Builder card id of a rule row read back without a group key ({@code entry:<id>}). */
    private static final String LEGACY_CARD_PREFIX = "entry:";

    private final ConstraintConfigRepository configs;
    private final ConstraintEntryRepository entries;
    private final ConstraintConfigService configService;
    private final TeacherRepository teachers;
    private final AdminClassRepository adminClasses;
    private final CourseRepository courses;
    private final RecentDeletionWriter deletionWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RuleLifecycleService(
            ConstraintConfigRepository configs,
            ConstraintEntryRepository entries,
            ConstraintConfigService configService,
            TeacherRepository teachers,
            AdminClassRepository adminClasses,
            CourseRepository courses,
            RecentDeletionWriter deletionWriter,
            ObjectMapper objectMapper,
            Clock clock) {
        this.configs = configs;
        this.entries = entries;
        this.configService = configService;
        this.teachers = teachers;
        this.adminClasses = adminClasses;
        this.courses = courses;
        this.deletionWriter = deletionWriter;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Deletes one card of a type row. {@code cardKey} is the card id (the group-key
     * prefix); blank deletes the whole type row (used by switch cards and the advanced
     * page). Returns the recycle batch plus how many entries were paused.
     *
     * <p>{@code entryIds} names the rows the card is made of. It is the reliable way to
     * delete a card whose rows have no group key (legacy rows read back as
     * {@code entry:<id>}), and it also pins the exact rows when a card id is ambiguous.
     */
    @Transactional
    public Map<String, Object> delete(Long configId, String cardKey, List<Long> entryIds) {
        ConstraintConfig config = configs.findById(configId)
                .orElseThrow(() -> ApiException.notFound("排课规则不存在: " + configId));
        if (config.getDeletedAt() != null) {
            throw ApiException.notFound("排课规则不存在: " + configId);
        }
        List<ConstraintEntry> rows = entries.findBySemesterIdAndTypeOrderByIdAsc(
                config.getSemesterId(), config.getType());
        boolean wholeType = !StringUtils.hasText(cardKey)
                || cardKey.startsWith(SWITCH_PREFIX)
                || cardKey.equals(config.getType());
        boolean byEntryIds = entryIds != null && !entryIds.isEmpty();
        List<ConstraintEntry> targets;
        if (byEntryIds) {
            Set<Long> ids = new LinkedHashSet<>(entryIds);
            targets = rows.stream()
                    .filter(row -> row.getDeletedAt() == null && ids.contains(row.getId()))
                    .toList();
        } else {
            targets = wholeType
                    ? rows.stream().filter(row -> row.getDeletedAt() == null).toList()
                    : rows.stream()
                            .filter(row -> row.getDeletedAt() == null && matchesCard(row, cardKey))
                            .toList();
        }
        if (!byEntryIds && !wholeType && targets.isEmpty()) {
            throw ApiException.notFound("规则卡不存在或已被删除: " + cardKey);
        }
        if (byEntryIds && targets.isEmpty()) {
            throw ApiException.notFound("规则卡不存在或已被删除: " + cardKey);
        }

        Instant now = Instant.now(clock);
        // Remember the switch state of every row before pausing it: a card that was
        // turned off must come back turned off, not silently enabled.
        Map<Long, Boolean> enabledByEntry = new LinkedHashMap<>();
        for (ConstraintEntry row : targets) {
            enabledByEntry.put(row.getId(), row.isEnabled());
            row.setDeletedAt(now);
            row.setEnabled(false);
        }
        Set<Long> targetIds = new LinkedHashSet<>();
        targets.forEach(row -> targetIds.add(row.getId()));
        boolean anyLiveLeft = rows.stream()
                .anyMatch(row -> row.getDeletedAt() == null && !targetIds.contains(row.getId()));
        boolean configEnabled = config.isEnabled();
        if (!anyLiveLeft) {
            config.setDeletedAt(now);
        }

        String label = labelOf(config, targets);
        String snapshot = snapshotJson(config.getId(), cardKey, targetIds, enabledByEntry, configEnabled);
        String batchId = deletionWriter.record(
                ENTITY_TYPE, config.getId(), label, KIND_LABEL, snapshot);
        configService.advanceVersion(config.getSemesterId());
        return Map.of("batchId", batchId, "deletedCount", targets.size());
    }

    /** Older callers delete by card key or whole type only. */
    @Transactional
    public Map<String, Object> delete(Long configId, String cardKey) {
        return delete(configId, cardKey, null);
    }

    @Override
    public boolean supports(String entityType) {
        return ENTITY_TYPE.equalsIgnoreCase(entityType);
    }

    @Override
    public void restore(RecentDeletion deletion) {
        restore(deletion.getEntityId(), deletion.getSnapshotJson());
    }

    @Override
    public void restore(Long entityId) {
        restore(entityId, null);
    }

    private void restore(Long configId, String snapshotJson) {
        ConstraintConfig config = configs.findById(configId)
                .orElseThrow(() -> ApiException.notFound("排课规则不存在: " + configId));
        JsonNode snapshot = readSnapshot(snapshotJson);
        if (snapshot != null && snapshot.has("entryIds")) {
            Map<Long, Boolean> enabledByEntry = enabledByEntryOf(snapshot);
            for (JsonNode node : snapshot.get("entryIds")) {
                entries.findById(node.asLong()).ifPresent(row -> {
                    if (config.getType().equals(row.getType())) {
                        row.setDeletedAt(null);
                        // Older snapshots have no flag: those rows were enabled at delete
                        // time (the pre-review code always wrote that), so default true.
                        row.setEnabled(enabledByEntry.getOrDefault(row.getId(), true));
                    }
                });
            }
        } else {
            // Older rows or a whole-type delete: revive every paused row of this type.
            for (ConstraintEntry row : entries.findBySemesterIdAndTypeOrderByIdAsc(
                    config.getSemesterId(), config.getType())) {
                if (row.getDeletedAt() != null) {
                    row.setDeletedAt(null);
                    row.setEnabled(true);
                }
            }
        }
        if (config.getDeletedAt() != null) {
            config.setDeletedAt(null);
            config.setEnabled(booleanOf(snapshot, "configEnabled", true));
            configs.save(config);
        }
        configService.advanceVersion(config.getSemesterId());
    }

    // ---------------------------------------------------------------- helpers

    private boolean matchesCard(ConstraintEntry row, String cardKey) {
        String trimmed = cardKey.trim();
        // Compatibility for a card read back from a legacy row (no group key): the
        // builder names it "entry:<rowId>", so delete by row id instead of group.
        if (trimmed.startsWith(LEGACY_CARD_PREFIX)) {
            Long entryId = parseLong(trimmed.substring(LEGACY_CARD_PREFIX.length()));
            return entryId != null && entryId.equals(row.getId());
        }
        String group = row.getGroupKey();
        if (!StringUtils.hasText(group)) {
            return false;
        }
        return group.equals(trimmed) || group.startsWith(trimmed + ":");
    }

    private String snapshotJson(
            Long configId, String cardKey, Set<Long> entryIds,
            Map<Long, Boolean> enabledByEntry, boolean configEnabled) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("configId", configId);
        snapshot.put("cardKey", cardKey);
        snapshot.put("entryIds", new ArrayList<>(entryIds));
        Map<String, Boolean> flags = new LinkedHashMap<>();
        enabledByEntry.forEach((id, enabled) -> flags.put(String.valueOf(id), enabled));
        snapshot.put("enabledByEntry", flags);
        snapshot.put("configEnabled", configEnabled);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("规则删除快照无法序列化", ex);
        }
    }

    /** Entry id → enabled flag from a snapshot; empty (default true) for old rows. */
    private Map<Long, Boolean> enabledByEntryOf(JsonNode snapshot) {
        Map<Long, Boolean> result = new LinkedHashMap<>();
        JsonNode flags = snapshot.get("enabledByEntry");
        if (flags == null || !flags.isObject()) {
            return result;
        }
        flags.fields().forEachRemaining(entry -> {
            Long id = parseLong(entry.getKey());
            if (id != null) {
                result.put(id, entry.getValue().asBoolean(true));
            }
        });
        return result;
    }

    private boolean booleanOf(JsonNode snapshot, String field, boolean fallback) {
        if (snapshot == null || !snapshot.has(field) || snapshot.get(field).isNull()) {
            return fallback;
        }
        return snapshot.get(field).asBoolean(fallback);
    }

    private Long parseLong(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private JsonNode readSnapshot(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    /**
     * A short human sentence for the recycle bin: the type plus the objects its rows
     * name. The raw parameters are not necessary and would leak ids into the list.
     */
    private String labelOf(ConstraintConfig config, List<ConstraintEntry> rows) {
        String typeLabel = config.getType();
        try {
            ConstraintType type = ConstraintType.valueOf(config.getType());
            typeLabel = type.getDisplayName();
        } catch (IllegalArgumentException ignored) {
            // A retired or unknown type keeps its code.
        }
        Set<String> names = new LinkedHashSet<>();
        for (ConstraintEntry row : rows) {
            Map<String, Object> params;
            try {
                params = objectMapper.readValue(row.getParamsJson(), Map.class);
            } catch (JsonProcessingException ex) {
                continue;
            }
            addName(names, teacherName(ConstraintSettings.longValue(params.get("teacherId"))));
            addName(names, className(ConstraintSettings.longValue(params.get("classId"))));
            addName(names, courseName(ConstraintSettings.longValue(params.get("courseId"))));
        }
        return names.isEmpty() ? typeLabel : typeLabel + "：" + String.join("、", names);
    }

    private String teacherName(Long id) {
        return id == null ? null : teachers.findById(id).map(Teacher::getName).orElse(null);
    }

    private String className(Long id) {
        return id == null ? null : adminClasses.findById(id).map(AdminClass::getName).orElse(null);
    }

    private String courseName(Long id) {
        return id == null ? null : courses.findById(id).map(Course::getName).orElse(null);
    }

    private void addName(Set<String> names, String name) {
        if (StringUtils.hasText(name)) {
            names.add(name);
        }
    }
}
