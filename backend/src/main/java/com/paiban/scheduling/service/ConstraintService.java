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
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.ConstraintTemplate;
import com.paiban.scheduling.domain.ConstraintTemplateRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.ConstraintType.Category;
import com.paiban.scheduling.domain.ConstraintType.Param;
import com.paiban.scheduling.domain.ConstraintType.ParamType;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigRequest;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintEntryDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintEntryRequest;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintTypeDto;
import com.paiban.scheduling.web.ConstraintDtos.ParamDefDto;
import com.paiban.scheduling.web.ConstraintDtos.TemplateApplyPreview;
import com.paiban.scheduling.web.ConstraintDtos.TemplateApplyRequest;
import com.paiban.scheduling.web.ConstraintDtos.TemplateDto;
import com.paiban.scheduling.web.ConstraintDtos.TemplateReferenceIssue;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Constraint configuration: the type-level rows (enabled/hard/weight) plus their
 * parameter entries (ADR-0028). Saving validates the whole request before writing,
 * so a rejected request leaves the previous configuration untouched.
 */
@Service
public class ConstraintService {

    /**
     * {@code constraint_template.configs_json} is VARCHAR(65535) since V29 (ticket 10/05:
     * 200 teacher-slot entries are already ~12k characters, so the original 8000 could
     * not hold a real template). The limit is still enforced explicitly — an oversized
     * template is refused, never truncated.
     */
    static final int TEMPLATE_JSON_LIMIT = 65535;

    private final ConstraintConfigRepository configs;
    private final ConstraintEntryRepository entries;
    private final ConstraintTemplateRepository templates;
    private final SemesterService semesters;
    private final TeacherRepository teachers;
    private final CourseRepository courses;
    private final PeriodRepository periods;
    private final AdminClassRepository adminClasses;
    private final ObjectMapper objectMapper;

    public ConstraintService(
            ConstraintConfigRepository configs,
            ConstraintEntryRepository entries,
            ConstraintTemplateRepository templates,
            SemesterService semesters,
            TeacherRepository teachers,
            CourseRepository courses,
            PeriodRepository periods,
            AdminClassRepository adminClasses,
            ObjectMapper objectMapper) {
        this.configs = configs;
        this.entries = entries;
        this.templates = templates;
        this.semesters = semesters;
        this.teachers = teachers;
        this.courses = courses;
        this.periods = periods;
        this.adminClasses = adminClasses;
        this.objectMapper = objectMapper;
    }

    public List<ConstraintTypeDto> types() {
        return List.of(ConstraintType.values()).stream()
                .map(type -> new ConstraintTypeDto(
                        type.name(),
                        type.getDisplayName(),
                        type.getCategory().name(),
                        type.getCategory().getLabel(),
                        type.getDescription(),
                        type.isDefaultHard(),
                        type.getDefaultWeight(),
                        type.isEntryBacked(),
                        type.isSlotGrouped(),
                        type.thresholdScope() == null ? null : type.thresholdScope().objectField(),
                        type.thresholdScope() == null ? null : type.thresholdScope().valueField(),
                        type.getParams().stream().map(this::toDto).toList()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConstraintConfigDto> list(Long semesterId) {
        Long resolved = resolveSemester(semesterId);
        Map<String, List<ConstraintEntry>> byType = entriesByType(entries.findBySemesterId(resolved));
        return configs.findBySemesterId(resolved).stream()
                .map(config -> toDto(config, byType.get(config.getType())))
                .toList();
    }

    @Transactional
    public List<ConstraintConfigDto> save(Long semesterId, List<ConstraintConfigRequest> requests) {
        Long resolved = resolveSemester(semesterId);
        Planning planning = plan(resolved, requests, existingEntries(resolved));
        List<String> errors = refusalErrors(planning);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join("; ", errors));
        }
        return persist(resolved, planning.configs());
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
    private Planning plan(Long resolved, List<ConstraintConfigRequest> requests,
                          Map<Long, ConstraintEntry> existingEntries) {
        List<String> errors = new ArrayList<>();
        List<TemplateReferenceIssue> references = new ArrayList<>();
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
            EntryPlan entries = planEntries(
                    type, request, hard, weight, timetable, existingEntries, claimedEntries, errors);
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
    private List<String> refusalErrors(Planning planning) {
        List<String> errors = new ArrayList<>(planning.errors());
        planning.references().stream().map(this::describeReference).forEach(errors::add);
        return errors;
    }

    /** `类型(CODE) 第N 条: 参数「字段」对象名#id 原因` — one saved/template row per line. */
    private String describeReference(TemplateReferenceIssue issue) {
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
                        plannedEntry.hard(), plannedEntry.weight(), plannedEntry.groupKey()));
            }
            current.values().stream()
                    .filter(row -> !kept.contains(row.getId()))
                    .forEach(entries::delete);
            result.add(toDtoWithEntries(savedConfig, entryDtos));
        }
        existingConfigs.values().stream()
                .filter(config -> !plannedTypes.contains(config.getType()))
                .forEach(configs::delete);
        existingEntries.entrySet().stream()
                .filter(byType -> !plannedTypes.contains(byType.getKey()))
                .flatMap(byType -> byType.getValue().values().stream())
                .forEach(entries::delete);
        return result;
    }

    @Transactional(readOnly = true)
    public List<ConstraintConfig> active(Long semesterId) {
        return configs.findBySemesterId(semesterId).stream()
                .filter(ConstraintConfig::isEnabled)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean hasConfigs(Long semesterId) {
        return !configs.findBySemesterId(semesterId).isEmpty();
    }

    /**
     * The one snapshot every evaluation entry point builds: solver, draft check,
     * publication readiness, substitution planner/verifier and the CLI all read the
     * same type rows and entry rows through this method.
     */
    @Transactional(readOnly = true)
    public ConstraintSettings settings(Long semesterId) {
        List<ConstraintConfig> all = configs.findBySemesterId(semesterId);
        if (all.isEmpty()) {
            return ConstraintSettings.defaults();
        }
        List<ConstraintConfig> enabled = all.stream().filter(ConstraintConfig::isEnabled).toList();
        return ConstraintSettings.from(enabled, entries.findBySemesterId(semesterId), objectMapper);
    }

    @Transactional
    public TemplateDto saveTemplate(String name, Long semesterId) {
        Long resolved = resolveSemester(semesterId);
        Map<String, List<ConstraintEntry>> byType = entriesByType(entries.findBySemesterId(resolved));
        List<ConstraintConfigRequest> snapshot = configs.findBySemesterId(resolved).stream()
                .map(config -> toSnapshotRequest(config, byType.get(config.getType())))
                .toList();
        String json = toJson(snapshot);
        if (json.length() > TEMPLATE_JSON_LIMIT) {
            throw ApiException.badRequest("模板内容为 " + json.length() + " 字符，超过 "
                    + TEMPLATE_JSON_LIMIT + " 字符上限；请减少条目或拆分模板");
        }
        ConstraintTemplate template = templates.findByName(name).orElseGet(ConstraintTemplate::new);
        template.setName(name);
        template.setConfigsJson(json);
        if (template.getCreatedAt() == null) {
            template.setCreatedAt(Instant.now());
        }
        return toDto(templates.save(template), snapshot.size(), entryCountOf(snapshot));
    }

    @Transactional(readOnly = true)
    public List<TemplateDto> listTemplates() {
        return templates.findAllByOrderByCreatedAtDesc().stream()
                .map(template -> {
                    List<ConstraintConfigRequest> snapshot = readSnapshot(template);
                    return toDto(template, snapshot.size(), entryCountOf(snapshot));
                })
                .toList();
    }

    /**
     * Replaces the whole semester with the template's configuration. Class references
     * belong to one semester, so they are either rebound explicitly (the caller names
     * the old → new ids) or the apply is refused with every offending row listed.
     * Teachers and courses are global assets and stay as they are when they still
     * exist. Nothing is ever matched by name and no invalid id reaches the database.
     *
     * <p>The refusal is one 409 whose body carries the same two lists the preview
     * returns ({@code errors} and {@code invalidReferences}), so the page parses one
     * shape no matter which of the two it called (see the controller).
     */
    @Transactional
    public List<ConstraintConfigDto> applyTemplate(
            Long templateId, Long semesterId, TemplateApplyRequest request) {
        Long resolved = resolveSemester(semesterId);
        List<ConstraintConfigRequest> snapshot = prepareSnapshot(
                readSnapshot(requireTemplate(templateId)), request);
        Planning planning = plan(resolved, snapshot, Map.of());
        List<String> errors = refusalErrors(planning);
        if (!errors.isEmpty()) {
            throw ApiException.conflict(
                    "模板无法套用到当前学期：" + String.join("；", errors),
                    refusalBody(planning));
        }
        return persist(resolved, planning.configs());
    }

    /**
     * The dry run behind the template panel: how much the apply would restore and what
     * stands in its way. It never writes, so the page can show the report before the
     * current configuration is replaced.
     */
    @Transactional(readOnly = true)
    public TemplateApplyPreview previewTemplate(
            Long templateId, Long semesterId, TemplateApplyRequest request) {
        Long resolved = resolveSemester(semesterId);
        List<ConstraintConfigRequest> snapshot = prepareSnapshot(
                readSnapshot(requireTemplate(templateId)), request);
        Planning planning = plan(resolved, snapshot, Map.of());
        int entryCount = planning.configs().stream()
                .mapToInt(config -> config.entries().size())
                .sum();
        return new TemplateApplyPreview(snapshot.size(), entryCount,
                planning.references(), planning.errors());
    }

    /** The 409 body: the two lists a preview also returns, plus the rows they point at. */
    private Map<String, Object> refusalBody(Planning planning) {
        return Map.of(
                "errors", planning.errors(),
                "invalidReferences", planning.references());
    }

    @Transactional
    public void deleteTemplate(Long id) {
        if (!templates.existsById(id)) {
            throw ApiException.notFound("约束模板不存在: " + id);
        }
        templates.deleteById(id);
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
                ConstraintType.CLASS_CONFLICT,
                ConstraintType.ROOM_CONFLICT)) {
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

    // ------------------------------------------------------------------ planning

    /** Entry parameter names that point at another aggregate, in report order. */
    private static final List<String> REFERENCE_FIELDS = List.of("teacherId", "courseId", "classId");

    private record PlannedConfig(ConstraintType type, int weight, boolean hard, boolean enabled,
                                 List<PlannedEntry> entries) {}

    /**
     * What a save would write, plus every reason it would be refused: {@code errors} is
     * everything the request itself gets wrong, {@code references} the object pointers
     * that no longer resolve. The save path (400) and both template paths (200 for the
     * dry run, 409 for the apply) read this one result, so they can never disagree about
     * which rows are at fault.
     */
    private record Planning(List<PlannedConfig> configs, List<String> errors,
                            List<TemplateReferenceIssue> references) {}

    /**
     * One accepted entry with the id it came in with, its 1-based request position and
     * its controls. {@code hard}/{@code weight} null mean "inherit the type default";
     * {@code groupKey} joins several rows into one user-managed requirement.
     */
    private record PlannedEntry(Long id, Map<String, Object> params, Integer position,
                                boolean enabled, Boolean hard, Integer weight, String groupKey) {}

    /** The entries of one type, plus the object references of those entries that do not resolve. */
    private record EntryPlan(List<PlannedEntry> entries, List<TemplateReferenceIssue> references) {}

    /**
     * The scalar conditions a type attaches to one object. Two entries for the same
     * object with different values are contradictory (one course cannot be in both a
     * lab and a playground), so the request is rejected instead of silently keeping
     * whichever row happens to be read first.
     */
    private record ScalarSpec(String objectField, List<String> scalarFields) {}

    private static final Map<ConstraintType, ScalarSpec> SCALAR_SPECS = Map.of(
            ConstraintType.COURSE_REQUIRED_ROOM_TYPE,
            new ScalarSpec("courseId", List.of("roomType")),
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
     * Validates every entry of one type and returns the ones to persist. Fully
     * identical entries — same parameters <em>and</em> same effective controls — are
     * rejected: the union would otherwise hide a duplicate row in the editor. The same
     * parameters under different controls are allowed on purpose: they are overlapping
     * requirements, and evaluation resolves them once (hard wins, otherwise the higher
     * weight). Contradictory scalar conditions for the same object are rejected too.
     */
    private EntryPlan planEntries(
            ConstraintType type, ConstraintConfigRequest request, boolean defaultHard,
            int defaultWeight, SemesterScope timetable,
            Map<Long, ConstraintEntry> existingEntries, Map<String, Set<Long>> claimedEntries,
            List<String> errors) {
        List<PlannedEntry> planned = new ArrayList<>();
        List<TemplateReferenceIssue> references = new ArrayList<>();
        Map<EntrySignature, Integer> seen = new HashMap<>();
        List<ConstraintEntryRequest> requested = request.entries();
        if (requested == null) {
            // Legacy shape: `params` is the single entry, and its messages keep the
            // pre-ADR wording. It inherits the type controls, so its meaning is the same
            // as before per-requirement controls existed.
            Map<String, Object> params = validateParams(type, request.params(), null, errors);
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
            Map<String, Object> params = validateParams(
                    type, raw == null ? null : raw.params(), position, errors);
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
        rejectContradictoryScalars(type, planned, errors);
        rejectDuplicateThresholdScopes(type, planned, errors);
        return new EntryPlan(planned, references);
    }

    /** A duplicate is the same parameters with the same effective controls. */
    private record EntrySignature(Map<String, Object> params, boolean enabled,
                                  boolean hard, int weight) {}

    private String normalizeGroupKey(
            String raw, ConstraintType type, int position, List<String> errors) {
        if (raw == null || raw.isBlank()) {
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
            Map<String, Set<Long>> claimedEntries, List<TemplateReferenceIssue> references,
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
                effectiveHard(entry, defaultHard), effectiveWeight(entry, defaultWeight));
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
     * conditions (a course in two room types, two consecutive-run lengths, two
     * morning cut-offs). Only entry-backed types carry such a spec.
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
            case TEACHER_UNAVAILABLE, TEACHER_PREFERRED_SLOT, COURSE_UNAVAILABLE ->
                    requireSlot(location, params, timetable, errors);
            case TEACHER_MEETING_BLOCKED -> {
                requireSubject(location, params, errors);
                requireSlot(location, params, timetable, errors);
            }
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
    private List<TemplateReferenceIssue> referencesOf(
            ConstraintType type, Integer position, Map<String, Object> params,
            SemesterScope timetable) {
        List<TemplateReferenceIssue> issues = new ArrayList<>();
        for (String field : REFERENCE_FIELDS) {
            Long objectId = ConstraintSettings.longValue(params.get(field));
            if (objectId == null) {
                continue;
            }
            ResolvedObject resolved = resolveReference(field, objectId, timetable);
            if (resolved.problem() == null) {
                continue;
            }
            issues.add(new TemplateReferenceIssue(
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

    /**
     * A subject entry must name a subject the school actually uses. The catalogue is
     * the union of teacher subjects and course subjects; when the school has not
     * filled any in yet there is nothing to check against, so the entry is accepted.
     */    private void requireSubject(String location, Map<String, Object> params, List<String> errors) {
        Object raw = params.get("subject");
        if (raw == null || !StringUtils.hasText(raw.toString())) {
            return;
        }
        String subject = raw.toString();
        Set<String> known = knownSubjects();
        if (!known.isEmpty() && known.stream().noneMatch(subject::equalsIgnoreCase)) {
            errors.add(location + ": 参数「学科」不存在: " + subject);
        }
    }

    private Set<String> knownSubjects() {
        Set<String> known = new LinkedHashSet<>();
        for (Teacher teacher : teachers.findAll()) {
            if (StringUtils.hasText(teacher.getSubject())) {
                known.add(teacher.getSubject().trim());
            }
        }
        for (Course course : courses.findAll()) {
            if (StringUtils.hasText(course.getSubject())) {
                known.add(course.getSubject().trim());
            }
        }
        return known;
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
            ConstraintType type, Map<String, Object> input, Integer position, List<String> errors) {
        String prefix = location(type, position) + ": ";
        Map<String, Object> source = input == null ? Map.of() : input;
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Param param : type.getParams()) {
            Object value = source.get(param.name());
            if (value == null || (value instanceof String text && !StringUtils.hasText(text))) {
                if (param.required()) {
                    errors.add(prefix + "参数「" + param.label() + "」不能为空");
                }
                continue;
            }
            if (param.type() == ParamType.TEXT) {
                normalized.put(param.name(), value.toString());
                continue;
            }
            if (param.type() == ParamType.ROOM_TYPE) {
                String text = value.toString();
                if (!param.options().contains(text)) {
                    errors.add(prefix + "教室类型无效: " + text);
                } else {
                    normalized.put(param.name(), text);
                }
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
                .forEach(key -> errors.add(prefix + "未知参数 " + key));
        return normalized;
    }

    /** Type name plus the 1-based entry position, so an error points at one row. */
    private String location(ConstraintType type, Integer position) {
        if (position == null) {
            return type.getDisplayName();
        }
        return type.getDisplayName() + "(" + type.name() + ") 第" + position + " 条";
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

    private ParamDefDto toDto(Param param) {
        return new ParamDefDto(
                param.name(),
                param.label(),
                param.type().name(),
                param.required(),
                param.min(),
                param.max(),
                param.defaultValue(),
                param.options());
    }

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
                entryDtos);
    }

    /**
     * Entry DTOs of one config row. A row that only has the legacy
     * {@code params_json} is shown as one implicit entry for the six single-switch
     * types, so old readers keep working. For an entry-backed type the legacy column is
     * ignored on purpose: V26/V27/V28 moved every value into a row, so an un-migrated
     * row means "not configured" — showing it as an entry would promise evaluation the
     * checker does not perform.
     */
    private List<ConstraintEntryDto> entryDtos(ConstraintConfig config, List<ConstraintEntry> entryRows) {
        List<ConstraintEntryDto> dtos = new ArrayList<>();
        if (entryRows != null) {
            entryRows.stream()
                    .sorted(Comparator.comparing(ConstraintEntry::getId,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(entry -> new ConstraintEntryDto(
                            entry.getId(),
                            paramsFromJson(entry.getParamsJson()),
                            entry.isEnabled(),
                            entry.getHard(),
                            entry.getWeight(),
                            entry.getGroupKey()))
                    .forEach(dtos::add);
        }
        if (dtos.isEmpty() && !isEntryBacked(config.getType())) {
            Map<String, Object> legacy = paramsFromJson(config.getParamsJson());
            if (!legacy.isEmpty()) {
                dtos.add(new ConstraintEntryDto(null, legacy, true, null, null, null));
            }
        }
        return dtos;
    }

    private boolean isEntryBacked(String typeCode) {
        try {
            return ConstraintType.valueOf(typeCode).isEntryBacked();
        } catch (IllegalArgumentException ex) {
            return false;
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

    private TemplateDto toDto(ConstraintTemplate template, int configCount, int entryCount) {
        return new TemplateDto(
                template.getId(), template.getName(), template.getCreatedAt(), configCount, entryCount);
    }

    /**
     * Entries a snapshot will restore. An entry-backed type is however many rows it
     * carries; the legacy single-params shape is one entry (and none when the map is
     * empty, which is what "not configured" looks like for a switch type).
     */
    private int entryCountOf(List<ConstraintConfigRequest> snapshot) {
        return snapshot.stream().mapToInt(this::entryCountOf).sum();
    }

    private int entryCountOf(ConstraintConfigRequest config) {
        if (config.entries() != null) {
            return config.entries().size();
        }
        return config.params() == null || config.params().isEmpty() ? 0 : 1;
    }

    /**
     * The snapshot as the apply/insert path needs it: entry ids are dropped and class
     * ids are rewritten with the caller's explicit old → new mapping.
     *
     * <p>A template is a value snapshot, not an identity — its entry ids name rows of
     * the semester it was taken from, so the apply inserts fresh rows instead of trying
     * to update rows of another semester. A class id the caller did not name keeps its
     * original value, so it is reported as invalid instead of being guessed from a
     * same-named class of the target semester.
     */
    private List<ConstraintConfigRequest> prepareSnapshot(
            List<ConstraintConfigRequest> snapshot, TemplateApplyRequest request) {
        Map<Long, Long> mapping = request == null || request.classMapping() == null
                ? Map.of() : request.classMapping();
        List<ConstraintConfigRequest> prepared = new ArrayList<>();
        for (ConstraintConfigRequest config : snapshot) {
            List<ConstraintEntryRequest> entries = config.entries() == null
                    ? null
                    : config.entries().stream()
                            .map(entry -> new ConstraintEntryRequest(
                                    null, rebindClass(mapping, entry.params()),
                                    entry.enabled(), entry.hard(), entry.weight(), entry.groupKey()))
                            .toList();
            prepared.add(new ConstraintConfigRequest(config.type(),
                    rebindClass(mapping, config.params()), config.weight(), config.hard(),
                    config.enabled(), entries));
        }
        return prepared;
    }

    private Map<String, Object> rebindClass(Map<Long, Long> mapping, Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        Long classId = ConstraintSettings.longValue(params.get("classId"));
        Long mapped = classId == null ? null : mapping.get(classId);
        if (mapped == null || mapped.equals(classId)) {
            return params;
        }
        Map<String, Object> copy = new LinkedHashMap<>(params);
        copy.put("classId", mapped);
        return copy;
    }

    private ConstraintTemplate requireTemplate(Long templateId) {
        return templates.findById(templateId)
                .orElseThrow(() -> ApiException.notFound("约束模板不存在: " + templateId));
    }

    private Long resolveSemester(Long semesterId) {
        return semesterId != null
                ? semesters.require(semesterId).getId()
                : semesters.requireCurrent().getId();
    }

    private Map<String, List<ConstraintEntry>> entriesByType(List<ConstraintEntry> rows) {
        Map<String, List<ConstraintEntry>> byType = new LinkedHashMap<>();
        rows.stream()
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

    private List<ConstraintConfigRequest> readSnapshot(ConstraintTemplate template) {
        try {
            return objectMapper.readValue(
                    template.getConfigsJson(), new TypeReference<List<ConstraintConfigRequest>>() {});
        } catch (JsonProcessingException ex) {
            throw ApiException.badRequest("约束模板内容损坏: " + template.getName());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化约束配置失败", ex);
        }
    }
}
