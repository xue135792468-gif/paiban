package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.event.SemesterCreatedEvent;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.scheduling.domain.ConstraintConfig;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintConfigState;
import com.paiban.scheduling.domain.ConstraintConfigStateRepository;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.ConstraintType.Category;
import com.paiban.scheduling.domain.ConstraintType.Param;
import com.paiban.scheduling.domain.ConstraintType.ParamType;
import com.paiban.scheduling.domain.ConstraintWorkspaceRepository;
import com.paiban.scheduling.domain.RuleGroup;
import com.paiban.scheduling.domain.RuleGroupRepository;
import com.paiban.scheduling.domain.RuleGroupType;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigRequest;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintEntryDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintEntryRequest;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintPreviewDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintSaveAuditDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintStateDto;
import com.paiban.scheduling.web.ConstraintDtos.ProposalDecision;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintReferenceIssue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Constraint configuration: the type-level rows (enabled/hard/weight) plus their
 * parameter entries (ADR-0028). Saving validates the whole request before writing,
 * so a rejected request leaves the previous configuration untouched.
 *
 * <p>This service owns planning, persistence, the shared config version and the
 * effective {@link ConstraintSettings} snapshot. Every write path goes through the
 * same planning and version methods here, so no caller can disagree about a refusal.
 */
@Service
public class ConstraintConfigService {

    private final ConstraintConfigRepository configs;
    private final ConstraintConfigStateRepository states;
    private final ConstraintEntryRepository entries;
    private final ConstraintWorkspaceRepository workspaces;
    private final SemesterService semesters;
    private final TeacherRepository teachers;
    private final CourseRepository courses;
    private final PeriodRepository periods;
    private final AdminClassRepository adminClasses;
    private final ConstraintAuditService auditService;
    private final ClassCapacityService capacity;
    private final RuleGroupRepository ruleGroups;
    private final RuleGroupExpander groupExpander;
    private final ObjectMapper objectMapper;

    public ConstraintConfigService(
            ConstraintConfigRepository configs,
            ConstraintConfigStateRepository states,
            ConstraintEntryRepository entries,
            ConstraintWorkspaceRepository workspaces,
            SemesterService semesters,
            TeacherRepository teachers,
            CourseRepository courses,
            PeriodRepository periods,
            AdminClassRepository adminClasses,
            ConstraintAuditService auditService,
            ClassCapacityService capacity,
            RuleGroupRepository ruleGroups,
            RuleGroupExpander groupExpander,
            ObjectMapper objectMapper) {
        this.configs = configs;
        this.states = states;
        this.entries = entries;
        this.workspaces = workspaces;
        this.semesters = semesters;
        this.teachers = teachers;
        this.courses = courses;
        this.periods = periods;
        this.adminClasses = adminClasses;
        this.auditService = auditService;
        this.capacity = capacity;
        this.ruleGroups = ruleGroups;
        this.groupExpander = groupExpander;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ConstraintConfigDto> list(Long semesterId) {
        Long resolved = resolveSemester(semesterId);
        Map<String, List<ConstraintEntry>> byType = entriesByType(entries.findBySemesterId(resolved));
        return configs.findBySemesterId(resolved).stream()
                .filter(config -> config.getDeletedAt() == null)
                .map(config -> toDto(config, byType.get(config.getType())))
                .toList();
    }

    /**
     * What the editor may show. Retired and unknown-type rows are kept in the database
     * for the migration report and for "old data stays queryable" (ticket 11), but they
     * are not part of the editable configuration any more. Deleted rows live in the
     * 7-day recycle bin (ticket 04) and are hidden the same way.
     */
    @Transactional(readOnly = true)
    public List<ConstraintConfigDto> visibleList(Long semesterId) {
        Long resolved = resolveSemester(semesterId);
        Map<String, List<ConstraintEntry>> byType = entriesByType(entries.findBySemesterId(resolved));
        return configs.findBySemesterId(resolved).stream()
                .filter(config -> config.getDeletedAt() == null)
                .filter(config -> !isPreservedType(config.getType()))
                .map(config -> toDto(config, byType.get(config.getType())))
                .toList();
    }

    /**
     * The effective configuration plus its version. Every writer sends the version it
     * read back as {@code baseVersion}; a save that names an older version is refused
     * instead of overwriting someone else's work.
     */
    @Transactional(readOnly = true)
    public ConstraintStateDto state(Long semesterId) {
        Long resolved = resolveSemester(semesterId);
        return new ConstraintStateDto(versionOf(resolved), visibleList(resolved));
    }

    @Transactional
    public List<ConstraintConfigDto> save(Long semesterId, List<ConstraintConfigRequest> requests) {
        return save(semesterId, requests, null);
    }

    /**
     * Saves the whole semester configuration. {@code baseVersion} is required once the
     * stored data uses the per-requirement shape (groups or overrides), because a legacy
     * request cannot represent it and would silently erase it.
     */
    @Transactional
    public List<ConstraintConfigDto> save(
            Long semesterId, List<ConstraintConfigRequest> requests, Long baseVersion) {
        return save(semesterId, requests, baseVersion, null, List.of(), null);
    }

    /**
     * Saves the whole semester configuration and, when the input came from a
     * generation run, records the user's decisions and the exact final configuration in
     * the same transaction (ticket 14/10). A cross-user or cross-semester run is
     * refused before any write.
     */
    @Transactional
    public List<ConstraintConfigDto> save(
            Long semesterId, List<ConstraintConfigRequest> requests, Long baseVersion,
            Long runId, List<ProposalDecision> decisions, String actor) {
        Long resolved = resolveSemester(semesterId);
        ConstraintAuditService.AuditContext audit = auditService.prepare(resolved, runId, actor);
        long base = requireVersion(resolved, baseVersion);
        Planning planning = plan(resolved, requests, existingEntries(resolved));
        List<String> errors = refusalErrors(planning);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join("; ", errors));
        }
        List<ConstraintConfigDto> previous = list(resolved);
        List<ConstraintConfigDto> result = commitPlan(resolved, base, planning);
        auditService.record(resolved, base + 1, audit, decisions, previous, result);
        // The successful save is the new truth: this account's unsaved draft must not
        // resurrect afterwards, on this device or another.
        if (audit.userId() != null) {
            workspaces.deleteByUserIdAndSemesterId(audit.userId(), resolved);
        }
        return result;
    }

    /**
     * Adds one whole-school “this slot is free” requirement — a {@code CLASS_UNAVAILABLE}
     * entry without a {@code classId} — unless that weekday+period is already configured.
     *
     * <p>The import pipeline proposes exactly one such rule after a manual mapping. It
     * calls this method instead of assembling the request DTOs on its own, so version
     * checks, planning and persistence stay in one place and imports never have to
     * speak the web layer's types.
     *
     * @return {@code true} when a row was added, {@code false} when the slot already existed
     */
    @Transactional
    public boolean addWeeklyNoClassRule(Long semesterId, int dayOfWeek, int periodNo) {
        String type = ConstraintType.CLASS_UNAVAILABLE.name();
        ConstraintStateDto state = state(semesterId);
        List<ConstraintConfigRequest> requests = new ArrayList<>();
        for (ConstraintConfigDto config : state.configs()) {
            requests.add(toRequest(config));
            if (type.equals(config.type()) && hasWholeSchoolSlot(config, dayOfWeek, periodNo)) {
                return false;
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dayOfWeek", dayOfWeek);
        params.put("periodNo", periodNo);
        ConstraintEntryRequest entry =
                new ConstraintEntryRequest(null, params, true, null, null, null);
        int index = indexOfType(requests, type);
        if (index < 0) {
            requests.add(new ConstraintConfigRequest(type, null, null, true, true, List.of(entry)));
        } else {
            ConstraintConfigRequest existing = requests.get(index);
            List<ConstraintEntryRequest> entries = new ArrayList<>(
                    existing.entries() == null ? List.of() : existing.entries());
            entries.add(entry);
            requests.set(index, new ConstraintConfigRequest(
                    type, null, existing.weight(), existing.hard(), existing.enabled(), entries));
        }
        save(semesterId, requests, state.version());
        return true;
    }

    private boolean hasWholeSchoolSlot(ConstraintConfigDto config, int dayOfWeek, int periodNo) {
        for (ConstraintEntryDto entry : config.entries()) {
            Map<String, Object> params = entry.params();
            if (params.get("classId") == null
                    && Integer.valueOf(dayOfWeek).equals(asInt(params.get("dayOfWeek")))
                    && Integer.valueOf(periodNo).equals(asInt(params.get("periodNo")))) {
                return true;
            }
        }
        return false;
    }

    private ConstraintConfigRequest toRequest(ConstraintConfigDto config) {
        boolean entryBacked = isEntryBacked(config.type());
        List<ConstraintEntryRequest> entries = entryBacked || !config.entries().isEmpty()
                ? config.entries().stream()
                        .map(entry -> new ConstraintEntryRequest(
                                entry.id(), entry.params(), entry.enabled(),
                                entry.hard(), entry.weight(), entry.groupKey()))
                        .toList()
                : null;
        return new ConstraintConfigRequest(
                config.type(), entries == null ? config.params() : null,
                config.weight(), config.hard(), config.enabled(), entries);
    }

    private int indexOfType(List<ConstraintConfigRequest> requests, String type) {
        for (int index = 0; index < requests.size(); index++) {
            if (type.equals(requests.get(index).type())) {
                return index;
            }
        }
        return -1;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /**
     * Dry run for "添加到约束表": the exact refusals a save would produce, with no
     * writes and no version advance. The page can therefore reject an invalid merge
     * before it touches the borrowed draft.
     */
    @Transactional(readOnly = true)
    public ConstraintPreviewDto preview(
            Long semesterId, List<ConstraintConfigRequest> requests) {
        Long resolved = resolveSemester(semesterId);
        Planning planning = plan(resolved, requests, existingEntries(resolved));
        return new ConstraintPreviewDto(
                planning.errors(), planning.references(), warningsOf(resolved, planning));
    }

    /**
     * A dry run of rules on top of the saved configuration, for the rule page's
     * impact preview. The request's requirements replace the saved ones that share a
     * group key (or entry id) and leave every other stored rule in place, so a single
     * unsaved card is judged against the semester as it actually is. Nothing is
     * written and the version does not advance.
     */
    public record PreviewSnapshot(ConstraintPreviewDto preview, ConstraintSettings settings) {}

    @Transactional(readOnly = true)
    public PreviewSnapshot previewSnapshot(
            Long semesterId, List<ConstraintConfigRequest> requests) {
        Long resolved = resolveSemester(semesterId);
        List<ConstraintConfigRequest> merged = mergedForPreview(resolved, requests);
        Planning planning = plan(resolved, merged, existingEntries(resolved));
        return new PreviewSnapshot(
                new ConstraintPreviewDto(
                        planning.errors(), planning.references(), warningsOf(resolved, planning)),
                settingsOf(resolved, planning));
    }

    /** Saved configuration with the request's requirement groups replaced in place. */
    private List<ConstraintConfigRequest> mergedForPreview(
            Long semesterId, List<ConstraintConfigRequest> requests) {
        Map<String, ConstraintConfigRequest> byType = new LinkedHashMap<>();
        snapshotOf(semesterId).forEach(config -> byType.put(config.type(), config));
        for (ConstraintConfigRequest request : requests) {
            ConstraintConfigRequest existing = byType.get(request.type());
            if (existing == null) {
                byType.put(request.type(), request);
                continue;
            }
            List<ConstraintEntryRequest> overrides =
                    request.entries() == null ? List.of() : request.entries();
            Set<String> replacedGroups = new HashSet<>();
            Set<Long> replacedIds = new HashSet<>();
            for (ConstraintEntryRequest entry : overrides) {
                if (StringUtils.hasText(entry.groupKey())) {
                    replacedGroups.add(entry.groupKey());
                }
                if (entry.id() != null) {
                    replacedIds.add(entry.id());
                }
            }
            List<ConstraintEntryRequest> kept = new ArrayList<>();
            for (ConstraintEntryRequest entry : existing.entries() == null
                    ? List.<ConstraintEntryRequest>of() : existing.entries()) {
                boolean replaced = (StringUtils.hasText(entry.groupKey())
                        && replacedGroups.contains(entry.groupKey()))
                        || (entry.id() != null && replacedIds.contains(entry.id()));
                if (!replaced) {
                    kept.add(entry);
                }
            }
            kept.addAll(overrides);
            byType.put(request.type(), new ConstraintConfigRequest(
                    request.type(), request.params(), request.weight(), request.hard(),
                    request.enabled(), kept));
        }
        return new ArrayList<>(byType.values());
    }

    /**
     * The capacity warnings of a <em>valid</em> plan: the unsaved rules are turned into
     * the same immutable {@link ConstraintSettings} snapshot the solver reads, then the
     * pure capacity check explains any class whose weekly lessons no longer fit. An
     * invalid plan has no meaningful capacity answer, so it returns no warnings; the
     * save path is unaffected either way.
     */
    private List<String> warningsOf(Long resolved, Planning planning) {
        if (!refusalErrors(planning).isEmpty()) {
            return List.of();
        }
        String message = capacity.message(resolved, settingsOf(resolved, planning));
        return StringUtils.hasText(message) ? List.of(message) : List.of();
    }

    /**
     * Builds the effective settings of a plan without writing anything. It reuses the
     * planned mapping and {@link ConstraintSettings} itself, so there is no second
     * parser that could disagree with what a save would store.
     */
    private ConstraintSettings settingsOf(Long semesterId, Planning planning) {
        List<ConstraintConfig> configs = new ArrayList<>();
        List<PlannedEntry> plannedEntries = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (PlannedConfig planned : planning.configs()) {
            ConstraintConfig config = new ConstraintConfig();
            config.setType(planned.type().name());
            config.setWeight(planned.weight());
            config.setHard(planned.hard());
            config.setEnabled(planned.enabled());
            config.setParamsJson("{}");
            configs.add(config);
            for (PlannedEntry entry : planned.entries()) {
                plannedEntries.add(entry);
                types.add(planned.type().name());
            }
        }
        List<Map<String, Object>> paramsRows = new ArrayList<>(plannedEntries.size());
        for (PlannedEntry entry : plannedEntries) {
            paramsRows.add(entry.params());
        }
        // Same expansion as the saved snapshot, so a preview cannot promise a different
        // outcome than the save it precedes.
        List<List<Map<String, Object>>> expanded = groupExpander.expandAll(semesterId, paramsRows);
        List<ConstraintEntry> rows = new ArrayList<>();
        long rowId = 0;
        for (int index = 0; index < plannedEntries.size(); index++) {
            PlannedEntry entry = plannedEntries.get(index);
            for (Map<String, Object> params : expanded.get(index)) {
                ConstraintEntry row = new ConstraintEntry();
                row.setId(++rowId);
                row.setType(types.get(index));
                row.setEnabled(entry.enabled());
                row.setHard(entry.hard());
                row.setWeight(entry.weight());
                row.setGroupKey(entry.groupKey());
                row.setParamsJson(writeParams(params));
                rows.add(row);
            }
        }
        return ConstraintSettings.from(configs, rows, objectMapper);
    }

    private String writeParams(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params == null ? Map.of() : params);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("约束参数无法序列化", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<ConstraintSaveAuditDto> audits(Long semesterId, boolean all, String actor) {
        return auditService.audits(semesterId, all, actor);
    }

    /** Enabled, live type rows. Rows in the recycle bin never reach an evaluation. */
    @Transactional(readOnly = true)
    public List<ConstraintConfig> active(Long semesterId) {
        return configs.findBySemesterId(semesterId).stream()
                .filter(config -> config.getDeletedAt() == null)
                .filter(ConstraintConfig::isEnabled)
                .toList();
    }

    /** True when the semester has any live (not recycled) configuration left. */
    @Transactional(readOnly = true)
    public boolean hasConfigs(Long semesterId) {
        return configs.findBySemesterId(semesterId).stream()
                .anyMatch(config -> config.getDeletedAt() == null);
    }

    /**
     * The one snapshot every evaluation entry point builds: solver, draft check,
     * publication readiness, substitution planner/verifier and the CLI all read the
     * same type rows and entry rows through this method.
     */
    @Transactional(readOnly = true)
    public ConstraintSettings settings(Long semesterId) {
        List<ConstraintConfig> all = configs.findBySemesterId(semesterId).stream()
                // A recycled type row is "not configured", exactly like a type the user
                // never saved; otherwise deleting every rule would silently keep rules.
                .filter(config -> config.getDeletedAt() == null)
                .toList();
        if (all.isEmpty()) {
            return ConstraintSettings.defaults();
        }
        List<ConstraintConfig> enabled = all.stream().filter(ConstraintConfig::isEnabled).toList();
        return ConstraintSettings.from(
                enabled, expandEntries(semesterId, entries.findBySemesterId(semesterId)),
                objectMapper);
    }

    /**
     * Expands every stored row's group references into concrete member rows before it
     * reaches {@link ConstraintSettings}. The persisted rows keep their group ids; only
     * the snapshot carries members, so editing a group changes every referencing rule
     * without a save. Copies are built on purpose: this is a read-only transaction and a
     * JPA-managed entity must never be mutated by a snapshot build.
     */
    private List<ConstraintEntry> expandEntries(
            Long semesterId, List<ConstraintEntry> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> paramsRows = new ArrayList<>(rows.size());
        for (ConstraintEntry row : rows) {
            paramsRows.add(paramsFromJson(row.getParamsJson()));
        }
        List<List<Map<String, Object>>> expanded = groupExpander.expandAll(semesterId, paramsRows);
        List<ConstraintEntry> result = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            ConstraintEntry source = rows.get(index);
            for (Map<String, Object> params : expanded.get(index)) {
                ConstraintEntry copy = new ConstraintEntry();
                copy.setId(source.getId());
                copy.setSemesterId(source.getSemesterId());
                copy.setType(source.getType());
                copy.setCreatedAt(source.getCreatedAt());
                copy.setEnabled(source.isEnabled());
                copy.setHard(source.getHard());
                copy.setWeight(source.getWeight());
                copy.setGroupKey(source.getGroupKey());
                copy.setDeletedAt(source.getDeletedAt());
                copy.setParamsJson(writeParams(params));
                result.add(copy);
            }
        }
        return result;
    }

    @EventListener
    @Transactional
    public void onSemesterCreated(SemesterCreatedEvent event) {
        if (!configs.findBySemesterId(event.semesterId()).isEmpty()) {
            return;
        }
        List<ConstraintConfig> defaults = new ArrayList<>();
        for (ConstraintType type : List.of(
                ConstraintType.TEACHER_CONFLICT,
                ConstraintType.CLASS_CONFLICT)) {
            ConstraintConfig config = new ConstraintConfig();
            config.setSemesterId(event.semesterId());
            config.setType(type.name());
            config.setParamsJson("{}");
            config.setWeight(100);
            config.setHard(true);
            config.setEnabled(true);
            defaults.add(config);
        }
        configs.saveAll(defaults);
    }

    // ------------------------------------------------------------ template support

    /**
     * The semester's configuration as the value snapshot a template stores: entry ids
     * are dropped, because a template is not tied to the rows of one semester.
     */
    List<ConstraintConfigRequest> snapshotOf(Long semesterId) {
        Map<String, List<ConstraintEntry>> byType = entriesByType(entries.findBySemesterId(semesterId));
        return configs.findBySemesterId(semesterId).stream()
                // A recycled type row must not be captured into a new template; its
                // entry rows are already dropped by entriesByType().
                .filter(config -> config.getDeletedAt() == null)
                .map(config -> toSnapshotRequest(config, byType.get(config.getType())))
                .toList();
    }

    /**
     * Forget this account's unsaved working copy after a write it did not make through
     * the editor. A manual save and a template apply both change the effective
     * configuration, so a stale draft must not resurrect on any device.
     */
    void clearWorkspaceDraft(String actor, Long semesterId) {
        Long userId = auditService.userIdOf(actor);
        if (userId != null) {
            workspaces.deleteByUserIdAndSemesterId(userId, semesterId);
        }
    }

    // ------------------------------------------------------------ planning

    /** Entry parameter names that point at another aggregate, in report order. */
    private static final List<String> REFERENCE_FIELDS = List.of("teacherId", "courseId", "classId");

    record PlannedConfig(ConstraintType type, int weight, boolean hard, boolean enabled,
                         List<PlannedEntry> entries) {}

    /**
     * What a save would write, plus every reason it would be refused: {@code errors} is
     * everything the request itself gets wrong, {@code references} the object pointers
     * that no longer resolve. The save path (400) and both template paths (200 for the
     * dry run, 409 for the apply) read this one result, so they can never disagree about
     * which rows are at fault.
     */
    record Planning(List<PlannedConfig> configs, List<String> errors,
                    List<ConstraintReferenceIssue> references) {}

    /**
     * One accepted entry with the id it came in with, its 1-based request position and
     * its controls. {@code hard}/{@code weight} null mean "inherit the type default";
     * {@code groupKey} joins several rows into one user-managed requirement.
     */
    record PlannedEntry(Long id, Map<String, Object> params, Integer position,
                        boolean enabled, Boolean hard, Integer weight, String groupKey) {}

    /** The entries of one type, plus the object references of those entries that do not resolve. */
    private record EntryPlan(List<PlannedEntry> entries, List<ConstraintReferenceIssue> references) {}

    /**
     * The scalar conditions a type attaches to one object. Two entries for the same
     * object with different values are contradictory (two consecutive-run lengths or
     * two cut-offs), so the request is rejected instead of silently keeping whichever
     * row happens to be read first.
     */
    private record ScalarSpec(String objectField, List<String> scalarFields) {}

    private static final Map<ConstraintType, ScalarSpec> SCALAR_SPECS = Map.of(
            ConstraintType.CONSECUTIVE_LESSONS,
            new ScalarSpec("courseId", List.of("minConsecutive")),
            ConstraintType.MAIN_SUBJECT_MORNING,
            new ScalarSpec("courseId", List.of("beforePeriodNo")));

    /** Periods and administrative classes that exist in the semester being saved. */
    private record SemesterScope(Set<String> slots, Set<Long> classes) {

        SemesterScope(List<Period> periods, List<AdminClass> adminClasses) {
            this(periods.stream()
                    .map(period -> key(period.getDayOfWeek(), period.getPeriodNo()))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                    adminClasses.stream().map(AdminClass::getId)
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        }

        static String key(Integer dayOfWeek, Integer periodNo) {
            return dayOfWeek + "-" + periodNo;
        }

        boolean isEmpty() {
            return slots.isEmpty();
        }

        boolean contains(Integer dayOfWeek, Integer periodNo) {
            return dayOfWeek != null && periodNo != null
                    && slots.contains(key(dayOfWeek, periodNo));
        }

        boolean hasClass(Long classId) {
            return classId != null && classes.contains(classId);
        }

        /** True when any day of the semester has at least one period. */
        boolean hasDay(Integer dayOfWeek) {
            return dayOfWeek != null
                    && slots.stream().anyMatch(slot -> slot.startsWith(dayOfWeek + "-"));
        }

        /** True when any day of the semester has this period number. */
        boolean hasPeriodNo(Integer periodNo) {
            if (periodNo == null) {
                return false;
            }
            for (String slot : slots) {
                if (slot.endsWith("-" + periodNo)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Validates every config and entry against one semester and returns exactly what a
     * save would write. Nothing is persisted, which is what lets a template preview
     * report the same refusals before anything is replaced.
     *
     * <p>{@code existingEntries} is the id → row index of the semester being saved: an
     * entry that carries an id claims one of those rows, and an id that names nothing
     * (or names another type) is refused. The preview passes an empty index because a
     * template is a value snapshot and never carries ids.
     */
    Planning plan(Long resolved, List<ConstraintConfigRequest> requests,
                  Map<Long, ConstraintEntry> existingEntries) {
        List<String> errors = new ArrayList<>();
        List<ConstraintReferenceIssue> references = new ArrayList<>();
        List<PlannedConfig> planned = new ArrayList<>();
        Map<String, Set<Long>> claimedEntries = new HashMap<>();
        Set<String> seenTypes = new HashSet<>();
        SemesterScope timetable = semesterScope(resolved);
        for (ConstraintConfigRequest request : requests) {
            ConstraintType type = resolveType(request.type(), errors);
            if (type == null) {
                continue;
            }
            if (!seenTypes.add(type.name())) {
                errors.add(type.getDisplayName() + "(" + type.name()
                        + "): 同一类型在请求中出现多次；启用/软硬/权重是类型级控制，请合并为一条配置");
                continue;
            }
            int weight = request.weight() == null ? type.getDefaultWeight() : request.weight();
            if (weight < 0 || weight > 100) {
                errors.add(type.getDisplayName() + ": 权重必须在 0-100 之间");
            }
            boolean hard = request.hard() == null ? type.isDefaultHard() : request.hard();
            if (type.isHardOnly() && !hard) {
                errors.add(type.getDisplayName() + ": 必须是硬约束，不能改为尽量");
            }
            EntryPlan entries = planEntries(
                    type, request, hard, weight, resolved, timetable, existingEntries,
                    claimedEntries, errors);
            references.addAll(entries.references());
            planned.add(new PlannedConfig(
                    type,
                    weight,
                    hard,
                    request.enabled() == null || request.enabled(),
                    entries.entries()));
        }
        return new Planning(planned, errors, references);
    }

    /** Every reason a planning would be refused, in request order. */
    List<String> refusalErrors(Planning planning) {
        List<String> errors = new ArrayList<>(planning.errors());
        planning.references().stream().map(this::describeReference).forEach(errors::add);
        return errors;
    }

    /** `类型(CODE) 第N 条: 参数「字段」对象名#id 原因` — one saved/template row per line. */
    private String describeReference(ConstraintReferenceIssue issue) {
        String where = issue.position() > 0
                ? issue.typeName() + "(" + issue.type() + ") 第" + issue.position() + " 条"
                : issue.typeName() + "(" + issue.type() + ")";
        String object = issue.name() == null
                ? "#" + issue.objectId()
                : issue.name() + "#" + issue.objectId();
        return where + ": 参数「" + plainLabel(issue.fieldLabel()) + "」" + object
                + " " + issue.reason();
    }

    /** The catalogue label without its parenthetical hint (`教师（留空表示全局默认）` → `教师`). */
    private String plainLabel(String label) {
        return label.replaceAll("（[^）]*）", "");
    }

    /** The 409 body: the two lists a preview also returns, plus the rows they point at. */
    Map<String, Object> refusalBody(Planning planning) {
        return Map.of(
                "errors", planning.errors(),
                "invalidReferences", planning.references());
    }

    private Map<Long, ConstraintEntry> existingEntries(Long semesterId) {
        Map<Long, ConstraintEntry> byId = new LinkedHashMap<>();
        for (ConstraintEntry entry : entries.findBySemesterId(semesterId)) {
            byId.put(entry.getId(), entry);
        }
        return byId;
    }

    private SemesterScope semesterScope(Long semesterId) {
        return new SemesterScope(
                periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId),
                adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId));
    }

    /**
     * Writes exactly the planned configuration. Entry rows are upserted by the id the
     * request carried (spec: 按条增删改须有稳定身份, ticket 04 review): a row with an id
     * of this type is updated in place and keeps that id, a row without one is inserted,
     * and rows of that type the request did not name are deleted. A type the request
     * does not mention at all is still removed — the request is the whole semester
     * configuration, only entry identity survives inside a type.
     */
    private List<ConstraintConfigDto> persist(Long resolved, List<PlannedConfig> planned) {
        Instant now = Instant.now();
        Map<String, ConstraintConfig> existingConfigs = new LinkedHashMap<>();
        for (ConstraintConfig config : configs.findBySemesterId(resolved)) {
            existingConfigs.put(config.getType(), config);
        }
        Map<String, Map<Long, ConstraintEntry>> existingEntries = new LinkedHashMap<>();
        for (ConstraintEntry entry : entries.findBySemesterId(resolved)) {
            existingEntries.computeIfAbsent(entry.getType(), key -> new LinkedHashMap<>())
                    .put(entry.getId(), entry);
        }

        List<ConstraintConfigDto> result = new ArrayList<>();
        Set<String> plannedTypes = new HashSet<>();
        // One reference lookup for every planned row, so the save response does not run
        // three queries per entry it just wrote.
        ReferenceIndex references = referencesOf(planned.stream()
                .flatMap(plan -> plan.entries().stream())
                .map(PlannedEntry::params)
                .toList(), resolved);
        for (PlannedConfig plan : planned) {
            String type = plan.type().name();
            plannedTypes.add(type);
            ConstraintConfig config = existingConfigs.get(type);
            if (config == null) {
                config = new ConstraintConfig();
                config.setSemesterId(resolved);
                config.setType(type);
            }
            // The legacy column keeps the first entry so older readers and raw SQL
            // still see a meaningful single value.
            config.setParamsJson(toJson(
                    plan.entries().isEmpty() ? Map.of() : plan.entries().get(0).params()));
            config.setWeight(plan.weight());
            config.setHard(plan.hard());
            config.setEnabled(plan.enabled());
            // A type that was fully deleted keeps its row so old ids still resolve; a
            // save that writes the type again brings the row back in one place.
            config.setDeletedAt(null);
            ConstraintConfig savedConfig = configs.save(config);

            Map<Long, ConstraintEntry> current = existingEntries.getOrDefault(type, Map.of());
            List<ConstraintEntry> rows = new ArrayList<>();
            Set<Long> kept = new HashSet<>();
            for (PlannedEntry entry : plan.entries()) {
                ConstraintEntry row = entry.id() == null ? null : current.get(entry.id());
                if (row == null) {
                    row = new ConstraintEntry();
                    row.setSemesterId(resolved);
                    row.setType(type);
                    row.setCreatedAt(now);
                } else {
                    kept.add(row.getId());
                }
                row.setParamsJson(toJson(entry.params()));
                row.setEnabled(entry.enabled());
                row.setHard(entry.hard());
                row.setWeight(entry.weight());
                row.setGroupKey(entry.groupKey());
                rows.add(row);
            }
            List<ConstraintEntry> savedRows = entries.saveAll(rows);
            List<ConstraintEntryDto> entryDtos = new ArrayList<>();
            for (int index = 0; index < savedRows.size(); index++) {
                PlannedEntry plannedEntry = plan.entries().get(index);
                entryDtos.add(new ConstraintEntryDto(
                        savedRows.get(index).getId(), plannedEntry.params(), plannedEntry.enabled(),
                        plannedEntry.hard(), plannedEntry.weight(), plannedEntry.groupKey(),
                        invalidReferencesOf(plannedEntry.params(), references)));
            }
            current.values().stream()
                    .filter(row -> !kept.contains(row.getId()))
                    .filter(row -> row.getDeletedAt() == null)
                    .forEach(entries::delete);
            result.add(toDtoWithEntries(savedConfig, entryDtos));
        }
        existingConfigs.values().stream()
                .filter(config -> !plannedTypes.contains(config.getType()))
                // Ticket 11: retired rows are paused, never physically deleted. The rule
                // page only sends the current active types, so a full save must leave the old
                // 7 types (and their pausable entries) in place — and, since ticket 11's
                // review, rows whose type code is unknown to the catalogue too.
                .filter(config -> !isPreservedType(config.getType()))
                // Deleted rows belong to the recycle bin: a full save must not remove
                // them, otherwise "最近删除" could never restore the rule.
                .filter(config -> config.getDeletedAt() == null)
                .forEach(configs::delete);
        existingEntries.entrySet().stream()
                .filter(byType -> !plannedTypes.contains(byType.getKey()))
                .filter(byType -> !isPreservedType(byType.getKey()))
                .flatMap(byType -> byType.getValue().values().stream())
                .filter(row -> row.getDeletedAt() == null)
                .forEach(entries::delete);
        return result;
    }

    /**
     * Writes one already-validated plan. The version moves before the rows so a losing
     * race writes nothing at all, and a later failure rolls the bump back with the rest
     * of the transaction. A manual save and a template apply both go through this one
     * method, so their write order can never drift apart.
     */
    List<ConstraintConfigDto> commitPlan(Long semesterId, long baseVersion, Planning planning) {
        advanceState(semesterId, baseVersion);
        return persist(semesterId, planning.configs());
    }

    // ------------------------------------------------------------ config version

    /** The current version; a semester that was never saved reads as 0. */
    private long versionOf(Long semesterId) {
        Long version = states.findVersionById(semesterId);
        return version == null ? 0L : version;
    }

    /**
     * Enforces the base version of one write and returns the version it is based on.
     *
     * <ul>
     *   <li>a named {@code baseVersion} must equal the current version, otherwise the
     *       save is refused with the latest configuration in the body;</li>
     *   <li>an unnamed base is accepted only while no stored entry uses the
     *       per-requirement shape. A legacy request cannot represent a group or an
     *       override, so allowing it would silently erase them (ticket 14/07).</li>
     * </ul>
     */
    long requireVersion(Long semesterId, Long baseVersion) {
        long current = versionOf(semesterId);
        if (baseVersion != null) {
            if (baseVersion != current) {
                throw versionConflict(semesterId, baseVersion, current);
            }
            return current;
        }
        if (hasAdvancedEntries(semesterId)) {
            throw ApiException.badRequest(
                    "该学期已包含逐项控制或要求分组，保存时必须携带配置版本（baseVersion），"
                            + "否则旧请求会静默抹掉这些设置；请刷新页面后重试");
        }
        return current;
    }

    private ApiException versionConflict(Long semesterId, Long basedOn, long current) {
        return ApiException.conflict(
                "约束配置已被他人修改（你基于版本 " + basedOn + "，当前版本 " + current
                        + "）。你的修改仍保留在页面上，请核对最新配置后再保存。",
                Map.of("version", current, "configs", list(semesterId)));
    }

    /**
     * True when any active stored entry uses a shape a legacy request cannot represent.
     * Rows of a retired (or unknown) type are ignored: after the ticket-11 migration the
     * seven paused types carry {@code enabled = false} rows, and counting those would
     * keep rejecting every legacy save with a message about "逐项控制/分组" that no longer
     * describes anything the user can edit (ticket 11 review).
     */
    private boolean hasAdvancedEntries(Long semesterId) {
        return entries.findBySemesterId(semesterId).stream()
                .filter(entry -> !isPreservedType(entry.getType()))
                // Recycled rows are not part of the configuration, so they must not keep
                // rejecting a legacy save that the visible rows can represent.
                .filter(entry -> entry.getDeletedAt() == null)
                .anyMatch(entry ->
                        !entry.isEnabled()
                                || entry.getHard() != null
                                || entry.getWeight() != null
                                || (entry.getGroupKey() != null
                                        && StringUtils.hasText(entry.getGroupKey())));
    }

    /**
     * Advances the semester version from {@code base} to {@code base + 1}. The update is
     * conditional, so a concurrent writer that already moved it loses here and the whole
     * transaction (including its configuration rows) is rolled back.
     */
    private void advanceState(Long semesterId, long base) {
        Instant now = Instant.now();
        if (states.bump(semesterId, base, now) > 0) {
            return;
        }
        if (states.existsById(semesterId)) {
            throw versionConflict(semesterId, base, versionOf(semesterId));
        }
        ConstraintConfigState created = new ConstraintConfigState();
        created.setSemesterId(semesterId);
        created.setVersion(1L);
        created.setUpdatedAt(now);
        try {
            // Flush now so a concurrent insert (same primary key) is caught here and
            // reported as a version conflict rather than as a raw database error.
            states.saveAndFlush(created);
        } catch (DataIntegrityViolationException ex) {
            throw versionConflict(semesterId, base, versionOf(semesterId));
        }
    }

    /**
     * Advances the version for a writer that is not a configuration save.
     *
     * <p>Ticket 11's migration pauses rows in place, but the rule page, its cached
     * previews and the version-aware save must still see that the configuration
     * changed. It therefore moves the same counter through the same conditional bump
     * as {@code commitPlan}, so a concurrent save conflicts exactly as it would with
     * any other writer. The caller owns the transaction: a later failure rolls the
     * bump back together with its own writes.
     *
     * @return the version now stored
     */
    public long advanceVersion(Long semesterId) {
        long current = versionOf(semesterId);
        advanceState(semesterId, current);
        return current + 1;
    }

    // ------------------------------------------------------------------ entries

    /**
     * Validates every entry of one type and returns the ones to persist. Fully
     * identical entries — same parameters <em>and</em> same effective controls — are
     * rejected: the union would otherwise hide a duplicate row in the editor. The same
     * parameters under different controls are allowed on purpose: they are overlapping
     * requirements, and evaluation resolves them once (hard wins, otherwise the higher
     * weight). Contradictory scalar conditions for the same object are rejected too.
     */
    private EntryPlan planEntries(
            ConstraintType type, ConstraintConfigRequest request, boolean defaultHard,
            int defaultWeight, Long semesterId, SemesterScope timetable,
            Map<Long, ConstraintEntry> existingEntries, Map<String, Set<Long>> claimedEntries,
            List<String> errors) {
        List<PlannedEntry> planned = new ArrayList<>();
        List<ConstraintReferenceIssue> references = new ArrayList<>();
        Map<EntrySignature, Integer> seen = new HashMap<>();
        List<ConstraintEntryRequest> requested = request.entries();
        if (requested == null) {
            // Legacy shape: `params` is the single entry, and its messages keep the
            // pre-ADR wording. It inherits the type controls, so its meaning is the same
            // as before per-requirement controls existed.
            Map<String, Object> params = validateParams(
                    type, request.params(), null, semesterId, errors);
            accept(type, new PlannedEntry(null, params, 1, true, null, null, null),
                    planned, seen, defaultHard, defaultWeight, timetable, existingEntries,
                    claimedEntries, references, errors);
            return new EntryPlan(planned, references);
        }
        if (type.getParams().isEmpty() && !requested.isEmpty()) {
            errors.add(type.getDisplayName() + "(" + type.name() + "): 该类型没有参数，不能配置条目");
            return new EntryPlan(List.of(), List.of());
        }
        for (int index = 0; index < requested.size(); index++) {
            ConstraintEntryRequest raw = requested.get(index);
            int position = index + 1;
            if (type.isHardOnly() && raw != null && Boolean.FALSE.equals(raw.hard())) {
                errors.add(location(type, position) + ": 必须留空，不能改为尽量");
            }
            Map<String, Object> params = validateParams(
                    type, raw == null ? null : raw.params(), position, semesterId, errors);
            Integer weight = raw == null ? null : raw.weight();
            if (weight != null && (weight < 0 || weight > 100)) {
                errors.add(location(type, position) + ": 权重必须在 0-100 之间");
                weight = null;
            }
            String groupKey = raw == null ? null
                    : normalizeGroupKey(raw.groupKey(), type, position, errors);
            boolean enabled = raw == null || raw.enabled() == null || raw.enabled();
            accept(type, new PlannedEntry(raw == null ? null : raw.id(), params, position,
                            enabled, raw == null ? null : raw.hard(), weight, groupKey),
                    planned, seen, defaultHard, defaultWeight, timetable, existingEntries,
                    claimedEntries, references, errors);
        }
        rejectInconsistentGroups(type, planned, defaultHard, defaultWeight, errors);
        Set<String> mixedScopeGroups = rejectMixedClassScope(type, planned, errors);
        rejectIncompleteClassSlotGroup(type, planned, mixedScopeGroups, errors);
        rejectContradictoryScalars(type, planned, errors);
        rejectDuplicateThresholdScopes(type, planned, errors);
        return new EntryPlan(planned, references);
    }

    /**
     * A duplicate is the same parameters with the same effective controls. Only for
     * {@code CLASS_UNAVAILABLE} is the requirement ({@code groupKey}) part of the
     * identity: two cards may overlap the same slot, while the same card may not repeat
     * a row. Every other type — including the other slot-grouped ones — keeps its
     * pre-ticket-05 signature, so their legacy dedup is unchanged.
     */
    private record EntrySignature(Map<String, Object> params, boolean enabled,
                                  boolean hard, int weight, String group) {}

    private String normalizeGroupKey(
            String raw, ConstraintType type, int position, List<String> errors) {
        if (raw == null || !StringUtils.hasText(raw)) {
            return null;
        }
        String key = raw.trim();
        if (key.length() > 64) {
            errors.add(location(type, position) + ": 要求分组标识超过 64 个字符");
            return null;
        }
        return key;
    }

    /**
     * The rows of one requirement share its controls: the editor pauses or re-strengths
     * the whole requirement, never half of it. Mixed values are refused rather than
     * silently normalized, so the page and the stored configuration cannot disagree.
     */
    private void rejectInconsistentGroups(
            ConstraintType type, List<PlannedEntry> planned, boolean defaultHard,
            int defaultWeight, List<String> errors) {
        Map<String, PlannedEntry> byGroup = new LinkedHashMap<>();
        for (PlannedEntry entry : planned) {
            if (entry.groupKey() == null) {
                continue;
            }
            PlannedEntry previous = byGroup.putIfAbsent(entry.groupKey(), entry);
            if (previous == null) {
                continue;
            }
            boolean hard = effectiveHard(entry, defaultHard);
            int weight = effectiveWeight(entry, defaultWeight);
            if (previous.enabled() != entry.enabled()
                    || effectiveHard(previous, defaultHard) != hard
                    || effectiveWeight(previous, defaultWeight) != weight) {
                errors.add(location(type, entry.position()) + ": 与第" + previous.position()
                        + " 条属于同一条要求，但启停/必须或尽量/重要程度不一致；"
                        + "同一条要求展开的课节必须共用这些设置");
            }
        }
    }

    private boolean effectiveHard(PlannedEntry entry, boolean defaultHard) {
        return entry.hard() == null ? defaultHard : entry.hard();
    }

    private int effectiveWeight(PlannedEntry entry, int defaultWeight) {
        return entry.weight() == null ? defaultWeight : entry.weight();
    }

    /**
     * 票05：一条「每周不上课时段」要求要么是全校，要么是若干指定行政班。两种范围混在同
     * 一个 {@code groupKey} 里会让“暂停一条仍被另一条覆盖”变成逐行猜测，所以整个请求被
     * 拒绝，用户必须把它拆成两条要求。只有带分组的行参与；旧的逐行配置保持原义。
     *
     * @return 出现混用的 groupKey，供矩形校验跳过这些组
     */
    private Set<String> rejectMixedClassScope(
            ConstraintType type, List<PlannedEntry> planned, List<String> errors) {
        Set<String> mixed = new LinkedHashSet<>();
        if (type != ConstraintType.CLASS_UNAVAILABLE) {
            return mixed;
        }
        Map<String, PlannedEntry> firstSchool = new LinkedHashMap<>();
        Map<String, PlannedEntry> firstSelected = new LinkedHashMap<>();
        for (PlannedEntry entry : planned) {
            String group = entry.groupKey();
            if (group == null) {
                continue;
            }
            boolean wholeSchool = entry.params().get("classId") == null;
            if (wholeSchool) {
                firstSchool.putIfAbsent(group, entry);
            } else {
                firstSelected.putIfAbsent(group, entry);
            }
            PlannedEntry counterpart = wholeSchool
                    ? firstSelected.get(group) : firstSchool.get(group);
            if (counterpart != null && mixed.add(group)) {
                errors.add(location(type, entry.position()) + ": 与第" + counterpart.position()
                        + " 条属于同一条要求，但把全校与指定班级混在一起；"
                        + "请拆成「全校」和「指定班级」两条不同的要求");
            }
        }
        return mixed;
    }

    /**
     * 票05：一条「每周不上课时段」要求展开后必须是完整的「班级 × 时段」矩形。
     *
     * <p>少一行就会在载入时被编辑器补成别的组合（或漏掉一个班/一节），保存后自动排课
     * 的禁排范围因此和用户选中的范围不一致。这里在写入前拒绝缺角的要求，并指出缺了
     * 哪个组合。只有条目已完整结构（星期/节次都能解析）时才判断，参数错误另行报告。
     */
    private void rejectIncompleteClassSlotGroup(
            ConstraintType type, List<PlannedEntry> planned, Set<String> skippedGroups,
            List<String> errors) {
        if (type != ConstraintType.CLASS_UNAVAILABLE) {
            return;
        }
        Map<String, List<PlannedEntry>> byGroup = new LinkedHashMap<>();
        for (PlannedEntry entry : planned) {
            if (entry.groupKey() == null || skippedGroups.contains(entry.groupKey())) {
                continue;
            }
            byGroup.computeIfAbsent(entry.groupKey(), key -> new ArrayList<>()).add(entry);
        }
        for (List<PlannedEntry> rows : byGroup.values()) {
            if (rows.size() < 2) {
                continue;
            }
            boolean incompleteParams = rows.stream().anyMatch(row ->
                    row.params().get("dayOfWeek") == null || row.params().get("periodNo") == null);
            if (incompleteParams) {
                continue;
            }
            Set<Long> classes = new LinkedHashSet<>();
            Set<String> slots = new LinkedHashSet<>();
            Set<String> present = new LinkedHashSet<>();
            for (PlannedEntry row : rows) {
                Long classId = ConstraintSettings.longValue(row.params().get("classId"));
                Integer day = ConstraintSettings.intValue(row.params().get("dayOfWeek"));
                Integer periodNo = ConstraintSettings.intValue(row.params().get("periodNo"));
                classes.add(classId);
                slots.add(day + "-" + periodNo);
                present.add(comboKey(classId, day, periodNo));
            }
            int expected = classes.size() * slots.size();
            if (present.size() >= expected) {
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (Long classId : classes) {
                for (String slot : slots) {
                    String[] parts = slot.split("-");
                    Integer day = Integer.valueOf(parts[0]);
                    Integer periodNo = Integer.valueOf(parts[1]);
                    if (!present.contains(comboKey(classId, day, periodNo))) {
                        missing.add((classId == null ? "全校" : "班级#" + classId)
                                + " 星期" + day + "第" + periodNo + "节");
                    }
                }
            }
            errors.add(location(type, rows.get(rows.size() - 1).position())
                    + ": 同一条要求必须展开为完整的「班级×时段」组合，当前缺少 "
                    + (expected - present.size()) + " 个（"
                    + String.join("；", missing.subList(0, Math.min(3, missing.size())))
                    + "）；请补齐后再保存，避免载入后自动补出其他班级的禁排");
        }
    }

    private static String comboKey(Long classId, Integer day, Integer periodNo) {
        return (classId == null ? "all" : "class" + classId) + "@" + day + "-" + periodNo;
    }

    /**
     * At most one entry per threshold scope: one global default and one exception per
     * object. Two entries for the same scope would leave "which value applies" to the
     * read order, so the whole request is rejected instead (ticket-10 spec: duplicate
     * or conflicting thresholds are refused, never silently resolved).
     */
    private void rejectDuplicateThresholdScopes(
            ConstraintType type, List<PlannedEntry> planned, List<String> errors) {
        ConstraintType.ThresholdScope scope = type.thresholdScope();
        if (scope == null) {
            return;
        }
        Map<Long, PlannedEntry> byScope = new LinkedHashMap<>();
        for (PlannedEntry entry : planned) {
            if (ConstraintSettings.intValue(entry.params().get(scope.valueField())) == null) {
                // Already rejected as a missing required value; not a scope conflict.
                continue;
            }
            Long objectId = ConstraintSettings.longValue(entry.params().get(scope.objectField()));
            PlannedEntry previous = byScope.putIfAbsent(objectId, entry);
            if (previous == null) {
                continue;
            }
            String where = objectId == null
                    ? "全局默认"
                    : "对象「" + paramLabel(type, scope.objectField()) + "#" + objectId + "」";
            errors.add(location(type, entry.position()) + ": 与第" + previous.position()
                    + " 条针对同一作用域（" + where + "），同一作用域最多一条阈值");
        }
    }

    private void accept(
            ConstraintType type, PlannedEntry entry, List<PlannedEntry> planned,
            Map<EntrySignature, Integer> seen, boolean defaultHard, int defaultWeight,
            SemesterScope timetable, Map<Long, ConstraintEntry> existingEntries,
            Map<String, Set<Long>> claimedEntries, List<ConstraintReferenceIssue> references,
            List<String> errors) {
        Map<String, Object> params = entry.params();
        if (params.isEmpty() && !type.isEntryBacked()) {
            // Nothing to store: no object condition. `{}` means "no extra
            // condition" and is kept in the legacy params column only. For an
            // entry-backed type whose target is optional `{}` is a real entry: the
            // explicit "every course / the sport class" row.
            return;
        }
        EntrySignature signature = new EntrySignature(params, entry.enabled(),
                effectiveHard(entry, defaultHard), effectiveWeight(entry, defaultWeight),
                entry.groupKey());
        Integer first = seen.putIfAbsent(signature, entry.position());
        if (first != null) {
            errors.add(location(type, entry.position()) + ": 与第" + first + " 条完全相同，重复条目已拒绝");
            return;
        }
        if (entry.id() != null) {
            claimEntry(type, entry.position(), entry.id(), existingEntries, claimedEntries, errors);
        }
        validateTimetableConditions(type, entry.position(), params, timetable, errors);
        references.addAll(referencesOf(type, entry.position(), params, timetable));
        planned.add(entry);
    }

    /**
     * An entry that carries an id asks to update that row, so the id has to name a row
     * of this type in this semester and may appear only once per request. Anything else
     * is refused before a single write: a rejected save never leaves a partial
     * configuration (spec: 按条增删改须有稳定身份).
     */
    private void claimEntry(
            ConstraintType type, Integer position, Long id,
            Map<Long, ConstraintEntry> existingEntries,
            Map<String, Set<Long>> claimedEntries, List<String> errors) {
        String where = location(type, position);
        ConstraintEntry existing = existingEntries.get(id);
        if (existing == null) {
            errors.add(where + ": 条目 id " + id + " 不属于当前学期的约束配置");
            return;
        }
        if (!type.name().equals(existing.getType())) {
            errors.add(where + ": 条目 id " + id + " 属于 " + existing.getType()
                    + "，不能用来更新 " + type.name() + " 的条目");
            return;
        }
        Set<Long> claimed = claimedEntries.computeIfAbsent(type.name(), key -> new HashSet<>());
        if (!claimed.add(id)) {
            errors.add(where + ": 条目 id " + id + " 在同一请求中出现多次");
        }
    }

    /**
     * Rejects two entries that target the same object with different scalar
     * conditions (two consecutive-run lengths, two morning cut-offs). Only entry-backed
     * types carry such a spec.
     */
    private void rejectContradictoryScalars(
            ConstraintType type, List<PlannedEntry> planned, List<String> errors) {
        ScalarSpec spec = SCALAR_SPECS.get(type);
        if (spec == null) {
            return;
        }
        Map<Long, PlannedEntry> byObject = new LinkedHashMap<>();
        for (PlannedEntry entry : planned) {
            Long objectId = ConstraintSettings.longValue(entry.params().get(spec.objectField()));
            if (objectId == null) {
                continue;
            }
            PlannedEntry previous = byObject.putIfAbsent(objectId, entry);
            if (previous == null) {
                continue;
            }
            for (String field : spec.scalarFields()) {
                String before = scalarText(previous.params().get(field));
                String after = scalarText(entry.params().get(field));
                if (!java.util.Objects.equals(before, after)) {
                    errors.add(location(type, entry.position()) + ": 与第" + previous.position()
                            + " 条针对同一目标但参数「" + paramLabel(type, field) + "」不一致（"
                            + before + " ≠ " + after + "）；同一目标不能并存互相冲突的条件");
                    break;
                }
            }
        }
    }

    private String scalarText(Object value) {
        return value == null ? "" : value.toString();
    }

    private String paramLabel(ConstraintType type, String field) {
        return type.getParams().stream()
                .filter(param -> param.name().equals(field))
                .map(Param::label)
                .findFirst()
                .orElse(field);
    }

    /**
     * Conditions only the semester's own timetable can judge: a (weekday, period) entry
     * must name a slot the semester has, a cut-off must exist somewhere in it, and a
     * subject must be one the school actually uses. Object existence is not judged here
     * — {@link #referencesOf} is the one resolver for that, shared by every entry point.
     */
    private void validateTimetableConditions(
            ConstraintType type, Integer position, Map<String, Object> params,
            SemesterScope timetable, List<String> errors) {
        if (!type.isEntryBacked()) {
            return;
        }
        String location = location(type, position);
        switch (type) {
            case TEACHER_UNAVAILABLE, TEACHER_PREFERRED_SLOT, COURSE_UNAVAILABLE, CLASS_UNAVAILABLE -> {
                // A time group replaces the explicit slot; its members are checked when
                // the group is edited, not on every rule save.
                if (!hasTimeGroup(params)) {
                    requireSlot(location, params, timetable, errors);
                }
            }
            case TEACHER_DAY_OFF -> requireDay(location, params, timetable, errors);
            case MAIN_SUBJECT_MORNING ->
                    requirePeriodNo(location, params, "beforePeriodNo", "上午截止节次", timetable, errors);
            default -> {
                // The other entry-backed types carry no timetable condition of their own;
                // their objects are judged by the reference resolver.
            }
        }
    }

    /** One object reference, looked up exactly once: present (with its name) or missing (with the reason). */
    private record ResolvedObject(String name, String problem) {

        static ResolvedObject present(String name) {
            return new ResolvedObject(name, null);
        }

        static ResolvedObject missing(String problem) {
            return new ResolvedObject(null, problem);
        }

        /** Missing but still nameable (a class of another semester still has a name). */
        static ResolvedObject missing(String name, String problem) {
            return new ResolvedObject(name, problem);
        }
    }

    /**
     * Every object reference of one entry that does not resolve, one issue per field, so
     * a card in the template dialog points at exactly the row the user has to fix.
     * Teachers and courses are global assets (a missing one means it was deleted); a
     * class belongs to one semester and has to be rebound explicitly.
     */
    private List<ConstraintReferenceIssue> referencesOf(
            ConstraintType type, Integer position, Map<String, Object> params,
            SemesterScope timetable) {
        List<ConstraintReferenceIssue> issues = new ArrayList<>();
        for (String field : REFERENCE_FIELDS) {
            if (coveredByGroup(field, params)) {
                // The group is the target; its own existence is validated with the entry
                // (RuleGroupExpander treats a missing group as zero members).
                continue;
            }
            Long objectId = ConstraintSettings.longValue(params.get(field));
            if (objectId == null) {
                continue;
            }
            ResolvedObject resolved = resolveReference(field, objectId, timetable);
            if (resolved.problem() == null) {
                continue;
            }
            issues.add(new ConstraintReferenceIssue(
                    type.name(),
                    type.getDisplayName(),
                    position == null ? 0 : position,
                    field,
                    paramLabel(type, field),
                    objectId,
                    resolved.name(),
                    resolved.problem()));
        }
        return issues;
    }

    /** One lookup per reference: a present object also carries the name the report shows. */
    private ResolvedObject resolveReference(String field, Long objectId, SemesterScope timetable) {
        switch (field) {
            case "teacherId":
                return teachers.findById(objectId)
                        .map(teacher -> ResolvedObject.present(teacher.getName()))
                        .orElseGet(() -> ResolvedObject.missing("教师不存在或已被删除"));
            case "courseId":
                return courses.findById(objectId)
                        .map(course -> ResolvedObject.present(course.getName()))
                        .orElseGet(() -> ResolvedObject.missing("课程不存在或已被删除"));
            case "classId":
                if (timetable.hasClass(objectId)) {
                    return ResolvedObject.present(null);
                }
                return adminClasses.findById(objectId)
                        .map(row -> ResolvedObject.missing(row.getName(), "行政班属于其他学期（"
                                + semesterName(row.getSemesterId()) + "），需显式指定当前学期的班级"))
                        .orElseGet(() -> ResolvedObject.missing("行政班不存在或已被删除"));
            default:
                return ResolvedObject.present(null);
        }
    }

    /** The semester name behind a foreign class id, for the report's reason column. */
    private String semesterName(Long semesterId) {
        try {
            return semesters.require(semesterId).getName();
        } catch (ApiException ex) {
            return "未知学期";
        }
    }

    /** A fixed day off must name a weekday that actually has periods. */
    private void requireDay(
            String location, Map<String, Object> params, SemesterScope timetable,
            List<String> errors) {
        if (timetable.isEmpty()) {
            errors.add(location + ": 该学期还没有作息安排，无法校验星期；请先配置作息");
            return;
        }
        Integer day = ConstraintSettings.intValue(params.get("dayOfWeek"));
        if (!timetable.hasDay(day)) {
            errors.add(location + ": 星期" + day + "不在本学期作息范围内");
        }
    }

    /** A (weekday, period) entry must name a slot the semester timetable really has. */
    private void requireSlot(
            String location, Map<String, Object> params, SemesterScope timetable,
            List<String> errors) {
        if (timetable.isEmpty()) {
            errors.add(location + ": 该学期还没有作息安排，无法校验节次；请先配置作息");
            return;
        }
        Integer day = ConstraintSettings.intValue(params.get("dayOfWeek"));
        Integer periodNo = ConstraintSettings.intValue(params.get("periodNo"));
        if (!timetable.contains(day, periodNo)) {
            errors.add(location + ": 星期" + day + "第" + periodNo + "节不在本学期作息范围内");
        }
    }

    /** A period number (a cut-off, not a slot) must exist somewhere in the timetable. */
    private void requirePeriodNo(
            String location, Map<String, Object> params, String field, String label,
            SemesterScope timetable, List<String> errors) {
        if (timetable.isEmpty()) {
            errors.add(location + ": 该学期还没有作息安排，无法校验「" + label + "」；请先配置作息");
            return;
        }
        Integer periodNo = ConstraintSettings.intValue(params.get(field));
        if (periodNo == null || !timetable.hasPeriodNo(periodNo)) {
            errors.add(location + ": 参数「" + label + "」第" + periodNo + "节不在本学期作息范围内");
        }
    }

    private ConstraintType resolveType(String code, List<String> errors) {
        if (!StringUtils.hasText(code)) {
            errors.add("约束类型不能为空");
            return null;
        }
        try {
            return ConstraintType.valueOf(code);
        } catch (IllegalArgumentException ex) {
            errors.add("未知约束类型: " + code);
            return null;
        }
    }

    private Map<String, Object> validateParams(
            ConstraintType type, Map<String, Object> input, Integer position,
            Long semesterId, List<String> errors) {
        String prefix = location(type, position) + ": ";
        Map<String, Object> source = input == null ? Map.of() : input;
        Map<String, Object> normalized = new LinkedHashMap<>();
        Set<String> covered = validateGroupParams(
                type, source, prefix, semesterId, errors, normalized);
        for (Param param : type.getParams()) {
            Object value = source.get(param.name());
            if (isBlankNoClassClassId(type, param, value)) {
                // 省略（或 JSON null）才是全校；显式空白字符串无法区分「写错了」和
                // 「没选」，会悄悄退化成全校，因此明确拒绝。
                errors.add(prefix + "参数「" + plainLabel(param.label()) + "」不能是空白字符串；"
                        + "全校范围请省略该参数或传 null");
                continue;
            }
            if (value == null || (value instanceof String text && !StringUtils.hasText(text))) {
                if (param.required() && !covered.contains(param.name())) {
                    errors.add(prefix + "参数「" + param.label() + "」不能为空");
                }
                continue;
            }
            if (param.type() == ParamType.TEXT) {
                normalized.put(param.name(), value.toString());
                continue;
            }
            Integer number = asInteger(value);
            if (number == null) {
                errors.add(prefix + "参数「" + param.label() + "」必须是整数");
                continue;
            }
            if (param.min() != null && number < param.min()) {
                errors.add(prefix + "参数「" + param.label() + "」不能小于 " + param.min());
            }
            if (param.max() != null && number > param.max()) {
                errors.add(prefix + "参数「" + param.label() + "」不能大于 " + param.max());
            }
            normalized.put(param.name(), number);
        }
        source.keySet().stream()
                .filter(key -> type.getParams().stream().noneMatch(param -> param.name().equals(key)))
                .filter(key -> GROUP_SPECS.stream().noneMatch(spec -> spec.key().equals(key)))
                .forEach(key -> errors.add(prefix + "未知参数 " + key));
        return normalized;
    }

    /**
     * Group keys are an alternative to a concrete object, not an extra parameter. This
     * validates the three group references of one entry and returns the concrete fields
     * they replace, so a rule that names only {@code teacherGroupId} is not also told
     * that {@code teacherId} is missing.
     */
    private Set<String> validateGroupParams(
            ConstraintType type, Map<String, Object> source, String prefix, Long semesterId,
            List<String> errors, Map<String, Object> normalized) {
        Set<String> covered = new LinkedHashSet<>();
        for (GroupSpec spec : GROUP_SPECS) {
            Object raw = source.get(spec.key());
            if (!hasValue(raw)) {
                continue;
            }
            if (!allowsGroup(type, spec.type())) {
                errors.add(prefix + "参数「" + spec.label() + "」不能用于"
                        + type.getDisplayName());
                continue;
            }
            covered.addAll(spec.coveredFields());
            for (String field : spec.coveredFields()) {
                if (hasValue(source.get(field))) {
                    errors.add(prefix + "参数「" + paramLabel(type, field) + "」与「"
                            + spec.label() + "」不能同时指定；" + spec.label() + "已代表具体成员");
                    break;
                }
            }
            Long groupId = ConstraintSettings.longValue(raw);
            if (groupId == null) {
                errors.add(prefix + "参数「" + spec.label() + "」必须是整数");
                continue;
            }
            RuleGroup group = ruleGroups.findById(groupId).orElse(null);
            if (group == null || group.getDeletedAt() != null) {
                errors.add(prefix + "参数「" + spec.label() + "」指向的分组不存在或已被删除："
                        + groupId);
                continue;
            }
            if (group.getGroupType() != spec.type()) {
                errors.add(prefix + "参数「" + spec.label() + "」指向的分组类型不匹配：" + groupId);
                continue;
            }
            if (spec.type() == RuleGroupType.TIME
                    && (semesterId == null || !semesterId.equals(group.getSemesterId()))) {
                errors.add(prefix + "参数「" + spec.label() + "」不属于当前学期：" + groupId);
                continue;
            }
            normalized.put(spec.key(), groupId.intValue());
        }
        return covered;
    }

    /** A group is accepted only where the type already has a matching concrete key. */
    private boolean allowsGroup(ConstraintType type, RuleGroupType groupType) {
        String field = switch (groupType) {
            case TEACHER -> "teacherId";
            case COURSE -> "courseId";
            case TIME -> "periodNo";
        };
        return type.getParams().stream().anyMatch(param -> field.equals(param.name()));
    }

    private static boolean hasValue(Object value) {
        return value != null && (!(value instanceof String text) || StringUtils.hasText(text));
    }

    private static boolean hasTimeGroup(Map<String, Object> params) {
        return params != null && hasValue(params.get("timeGroupId"));
    }

    /** A concrete object field is replaced by its group key, so it is not resolved separately. */
    private static boolean coveredByGroup(String field, Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        return ("teacherId".equals(field) && hasValue(params.get("teacherGroupId")))
                || ("courseId".equals(field) && hasValue(params.get("courseGroupId")));
    }

    /** One group key, its display label, the group type and the concrete fields it replaces. */
    private record GroupSpec(String key, String label, RuleGroupType type,
                             Set<String> coveredFields) {}

    private static final List<GroupSpec> GROUP_SPECS = List.of(
            new GroupSpec("teacherGroupId", "教师分组", RuleGroupType.TEACHER,
                    Set.of("teacherId")),
            new GroupSpec("courseGroupId", "课程分组", RuleGroupType.COURSE,
                    Set.of("courseId")),
            new GroupSpec("timeGroupId", "时间分组", RuleGroupType.TIME,
                    Set.of("dayOfWeek", "periodNo")));

    /** Type name plus the 1-based entry position, so an error points at one row. */
    private String location(ConstraintType type, Integer position) {
        if (position == null) {
            return type.getDisplayName();
        }
        return type.getDisplayName() + "(" + type.name() + ") 第" + position + " 条";
    }

    /** 一个显式空白、而非省略的 CLASS_UNAVAILABLE classId：拒绝，绝不当作全校。 */
    private static boolean isBlankNoClassClassId(
            ConstraintType type, Param param, Object value) {
        return type == ConstraintType.CLASS_UNAVAILABLE
                && "classId".equals(param.name())
                && value instanceof String text
                && !StringUtils.hasText(text);
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ mapping

    private ConstraintConfigDto toDto(ConstraintConfig config, List<ConstraintEntry> entryRows) {
        return toDtoWithEntries(config, entryDtos(config, entryRows));
    }

    /** Same DTO from the entry rows a save just wrote, so the response is its own receipt. */
    private ConstraintConfigDto toDtoWithEntries(
            ConstraintConfig config, List<ConstraintEntryDto> entryDtos) {
        ConstraintType type;
        try {
            type = ConstraintType.valueOf(config.getType());
        } catch (IllegalArgumentException ex) {
            type = null;
        }
        String name = type == null ? config.getType() : type.getDisplayName();
        Category category = type == null ? Category.PEDAGOGY : type.getCategory();
        String description = type == null ? "" : type.getDescription();
        // `params` stays the pre-ADR single-value view: the first entry for an
        // entry-backed type, the legacy column for the types that still use it.
        Map<String, Object> legacyParams = entryDtos.isEmpty()
                ? (isEntryBacked(config.getType()) ? Map.of() : paramsFromJson(config.getParamsJson()))
                : entryDtos.get(0).params();
        List<String> invalidReferences = entryDtos.stream()
                .flatMap(entry -> entry.invalidReferences() == null
                        ? java.util.stream.Stream.empty()
                        : entry.invalidReferences().stream())
                .distinct()
                .toList();
        return new ConstraintConfigDto(
                config.getId(),
                config.getType(),
                name,
                category.name(),
                category.getLabel(),
                description,
                legacyParams,
                config.getWeight(),
                config.isHard(),
                config.isEnabled(),
                entryDtos,
                invalidReferences);
    }

    /**
     * Entry DTOs of one config row. A row that only has the legacy
     * {@code params_json} is shown as one implicit entry for the single-switch
     * types, so old readers keep working. For an entry-backed type the legacy column is
     * ignored on purpose: V26/V27/V28 moved every value into a row, so an un-migrated
     * row means "not configured" — showing it as an entry would promise evaluation the
     * checker does not perform.
     */
    private List<ConstraintEntryDto> entryDtos(ConstraintConfig config, List<ConstraintEntry> entryRows) {
        List<ConstraintEntry> sorted = entryRows == null
                ? List.of()
                : entryRows.stream()
                        .sorted(Comparator.comparing(ConstraintEntry::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList();
        List<Map<String, Object>> paramsRows = new ArrayList<>();
        for (ConstraintEntry entry : sorted) {
            paramsRows.add(paramsFromJson(entry.getParamsJson()));
        }
        // One batch lookup for the whole card instead of three queries per row.
        ReferenceIndex references = referencesOf(paramsRows, config.getSemesterId());
        List<ConstraintEntryDto> dtos = new ArrayList<>();
        for (int index = 0; index < sorted.size(); index++) {
            ConstraintEntry entry = sorted.get(index);
            Map<String, Object> params = paramsRows.get(index);
            dtos.add(new ConstraintEntryDto(
                    entry.getId(),
                    params,
                    entry.isEnabled(),
                    entry.getHard(),
                    entry.getWeight(),
                    entry.getGroupKey(),
                    invalidReferencesOf(params, references)));
        }
        if (dtos.isEmpty() && !isEntryBacked(config.getType())) {
            Map<String, Object> legacy = paramsFromJson(config.getParamsJson());
            if (!legacy.isEmpty()) {
                dtos.add(new ConstraintEntryDto(
                        null, legacy, true, null, null, null,
                        invalidReferencesOf(
                                legacy, referencesOf(List.of(legacy), config.getSemesterId()))));
            }
        }
        return dtos;
    }

    /**
     * Objects a rule row points at that can no longer be used. Rule parameters are
     * plain JSON ids (no foreign key), so a paused teacher/class/course would otherwise
     * make the rule silently stop applying; the page shows these sentences instead.
     */
    private List<String> invalidReferencesOf(Map<String, Object> params, ReferenceIndex references) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        Long teacherId = ConstraintSettings.longValue(params.get("teacherId"));
        if (teacherId != null) {
            Teacher teacher = references.teachers().get(teacherId);
            if (teacher == null) {
                reasons.add("教师已删除：#" + teacherId);
            } else if (!teacher.isActive() || teacher.getDeletedAt() != null) {
                reasons.add("教师已停用：" + teacher.getName());
            }
        }
        Long classId = ConstraintSettings.longValue(params.get("classId"));
        if (classId != null) {
            AdminClass item = references.classes().get(classId);
            if (item == null) {
                reasons.add("行政班已删除：#" + classId);
            } else if (!item.isActive() || item.getDeletedAt() != null) {
                reasons.add("行政班已停用：" + item.getName());
            }
        }
        Long courseId = ConstraintSettings.longValue(params.get("courseId"));
        if (courseId != null) {
            Course item = references.courses().get(courseId);
            if (item == null) {
                reasons.add("课程已删除：#" + courseId);
            } else if (!item.isActive() || item.getDeletedAt() != null) {
                reasons.add("课程已停用：" + item.getName());
            }
        }
        addGroupIssues(reasons, params, references);
        addSlotIssues(reasons, params, references);
        return reasons;
    }

    /**
     * A weekday/period pair the semester's timetable no longer has (for example a rule
     * copied from a semester with more periods). Only the copy path can produce such a
     * row — a normal save refuses it before writing — so this is purely the diagnostic
     * the rule page needs to explain why a copied card is paused. A row that targets a
     * time group is skipped: the group's members carry the slots.
     */
    private void addSlotIssues(
            List<String> reasons, Map<String, Object> params, ReferenceIndex references) {
        if (hasValue(params.get("timeGroupId"))) {
            return;
        }
        Integer day = ConstraintSettings.intValue(params.get("dayOfWeek"));
        if (day == null) {
            return;
        }
        Integer periodNo = ConstraintSettings.intValue(params.get("periodNo"));
        if (periodNo != null) {
            if (!references.slots().contains(day + "-" + periodNo)) {
                reasons.add("星期" + day + "第" + periodNo + "节在本学期作息中不存在");
            }
            return;
        }
        if (references.slots().stream().noneMatch(slot -> slot.startsWith(day + "-"))) {
            reasons.add("星期" + day + "在本学期作息中不存在");
        }
    }

    /**
     * A group reference that no longer resolves (deleted, unknown or of the wrong type)
     * makes the rule silently apply to nobody. The page shows the reason so the user can
     * fix or delete the card instead of wondering why the sentence does nothing.
     */
    private void addGroupIssues(
            List<String> reasons, Map<String, Object> params, ReferenceIndex references) {
        addGroupIssue(reasons, references, params.get("teacherGroupId"), RuleGroupType.TEACHER);
        addGroupIssue(reasons, references, params.get("courseGroupId"), RuleGroupType.COURSE);
        addGroupIssue(reasons, references, params.get("timeGroupId"), RuleGroupType.TIME);
    }

    private void addGroupIssue(
            List<String> reasons, ReferenceIndex references, Object raw, RuleGroupType expected) {
        Long groupId = ConstraintSettings.longValue(raw);
        if (groupId == null) {
            return;
        }
        RuleGroup group = references.groups().get(groupId);
        if (group == null) {
            reasons.add("分组已删除：#" + groupId);
            return;
        }
        if (group.getDeletedAt() != null) {
            reasons.add("分组已删除：" + group.getName());
            return;
        }
        if (group.getGroupType() != expected) {
            reasons.add("分组类型不匹配：" + group.getName());
        }
    }

    /** The referenced rows of a batch of rule parameters, loaded in a fixed number of queries. */
    private ReferenceIndex referencesOf(
            List<Map<String, Object>> paramRows, Long semesterId) {
        Set<Long> teacherIds = new LinkedHashSet<>();
        Set<Long> classIds = new LinkedHashSet<>();
        Set<Long> courseIds = new LinkedHashSet<>();
        Set<Long> groupIds = new LinkedHashSet<>();
        boolean needsSlots = false;
        for (Map<String, Object> params : paramRows) {
            if (params == null || params.isEmpty()) {
                continue;
            }
            Long teacherId = ConstraintSettings.longValue(params.get("teacherId"));
            if (teacherId != null) {
                teacherIds.add(teacherId);
            }
            Long classId = ConstraintSettings.longValue(params.get("classId"));
            if (classId != null) {
                classIds.add(classId);
            }
            Long courseId = ConstraintSettings.longValue(params.get("courseId"));
            if (courseId != null) {
                courseIds.add(courseId);
            }
            for (String key : List.of("teacherGroupId", "courseGroupId", "timeGroupId")) {
                Long groupId = ConstraintSettings.longValue(params.get(key));
                if (groupId != null) {
                    groupIds.add(groupId);
                }
            }
            if (!needsSlots && params.get("dayOfWeek") != null
                    && !hasValue(params.get("timeGroupId"))) {
                needsSlots = true;
            }
        }
        Map<Long, Teacher> teacherById = new HashMap<>();
        if (!teacherIds.isEmpty()) {
            teachers.findAllById(teacherIds).forEach(row -> teacherById.put(row.getId(), row));
        }
        Map<Long, AdminClass> classById = new HashMap<>();
        if (!classIds.isEmpty()) {
            if (semesterId != null) {
                // A class belongs to one semester; loading every id globally would make a
                // rule copied from another semester look valid. Only this semester's live
                // classes resolve here.
                for (AdminClass row : adminClasses
                        .findBySemesterIdAndDeletedAtIsNullOrderByGradeIdAscNameAsc(semesterId)) {
                    if (classIds.contains(row.getId())) {
                        classById.put(row.getId(), row);
                    }
                }
            } else {
                adminClasses.findAllById(classIds).forEach(row -> classById.put(row.getId(), row));
            }
        }
        Map<Long, Course> courseById = new HashMap<>();
        if (!courseIds.isEmpty()) {
            courses.findAllById(courseIds).forEach(row -> courseById.put(row.getId(), row));
        }
        Map<Long, RuleGroup> groupById = new HashMap<>();
        if (!groupIds.isEmpty()) {
            ruleGroups.findAllById(groupIds).forEach(row -> groupById.put(row.getId(), row));
        }
        // Slot coordinates are also diagnosed here: a rule copied from a semester with
        // more periods points at a slot this semester does not have (ticket 08). The
        // query is skipped for batches without any weekday parameter.
        Set<String> slots = new LinkedHashSet<>();
        if (semesterId != null && needsSlots) {
            for (Period period : periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)) {
                slots.add(period.getDayOfWeek() + "-" + period.getPeriodNo());
            }
        }
        return new ReferenceIndex(teacherById, classById, courseById, groupById, slots);
    }

    private record ReferenceIndex(
            Map<Long, Teacher> teachers,
            Map<Long, AdminClass> classes,
            Map<Long, Course> courses,
            Map<Long, RuleGroup> groups,
            Set<String> slots) {}

    private boolean isEntryBacked(String typeCode) {
        ConstraintType type = typeOrNull(typeCode);
        return type != null && type.isEntryBacked();
    }

    /**
     * True when a stored row must survive a save that did not name it: a retired type
     * is paused and kept readable, and a code the catalogue no longer knows at all is
     * also kept untouched — deleting what we cannot interpret would lose user data the
     * rule page never showed (ticket 11 review; previously an unknown code fell through
     * as "not retired" and was deleted on the next full save).
     */
    private boolean isPreservedType(String typeCode) {
        if (!StringUtils.hasText(typeCode)) {
            return true;
        }
        ConstraintType type = typeOrNull(typeCode);
        return type == null || type.isRetired();
    }

    /** Null for a blank or unknown code; never throws (unlike {@code valueOf}). */
    private ConstraintType typeOrNull(String typeCode) {
        if (!StringUtils.hasText(typeCode)) {
            return null;
        }
        try {
            return ConstraintType.valueOf(typeCode);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private ConstraintConfigRequest toSnapshotRequest(
            ConstraintConfig config, List<ConstraintEntry> entryRows) {
        List<ConstraintEntryDto> entryDtos = entryDtos(config, entryRows);
        boolean entryBacked = isEntryBacked(config.getType());
        // An entry-backed type always travels as an entry list — possibly empty, which
        // round-trips as "not configured" — so applying the template cannot resurrect
        // a legacy params map or fail on its required parameters.
        List<ConstraintEntryRequest> snapshotEntries = entryBacked || !entryDtos.isEmpty()
                ? entryDtos.stream()
                        .map(entry -> new ConstraintEntryRequest(
                                null, entry.params(), entry.enabled(), entry.hard(),
                                entry.weight(), entry.groupKey()))
                        .toList()
                : null;
        // With entries present the snapshot does not repeat the first entry in the
        // legacy field: one copy is enough, and the template stays small.
        Map<String, Object> legacyParams = entryBacked || !entryDtos.isEmpty()
                ? null
                : paramsFromJson(config.getParamsJson());
        return new ConstraintConfigRequest(
                config.getType(), legacyParams, config.getWeight(), config.isHard(), config.isEnabled(),
                snapshotEntries);
    }

    Long resolveSemester(Long semesterId) {
        return semesterId != null
                ? semesters.require(semesterId).getId()
                : semesters.requireCurrent().getId();
    }

    private Map<String, List<ConstraintEntry>> entriesByType(List<ConstraintEntry> rows) {
        Map<String, List<ConstraintEntry>> byType = new LinkedHashMap<>();
        rows.stream()
                // Deleted rows are recycle-bin content: they must never reach a DTO, a
                // preview or a save decision (ticket 04).
                .filter(entry -> entry.getDeletedAt() == null)
                .sorted(Comparator.comparing(ConstraintEntry::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(entry -> byType.computeIfAbsent(entry.getType(), key -> new ArrayList<>())
                        .add(entry));
        return byType;
    }

    private Map<String, Object> paramsFromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化约束配置失败", ex);
        }
    }
}
