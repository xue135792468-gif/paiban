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
import com.paiban.scheduling.web.ConstraintDtos.TemplateDto;
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

    /** {@code constraint_template.configs_json} is VARCHAR(8000); never truncate silently. */
    static final int TEMPLATE_JSON_LIMIT = 8000;

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
        List<String> errors = new ArrayList<>();
        List<PlannedConfig> planned = new ArrayList<>();
        Set<String> seenTypes = new HashSet<>();
        SemesterScope timetable = new SemesterScope(
                periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(resolved),
                adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(resolved));
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
            List<Map<String, Object>> plannedEntries = planEntries(type, request, timetable, errors);
            planned.add(new PlannedConfig(
                    type,
                    weight,
                    request.hard() == null ? type.isDefaultHard() : request.hard(),
                    request.enabled() == null || request.enabled(),
                    plannedEntries));
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(String.join("; ", errors));
        }

        Instant now = Instant.now();
        List<ConstraintConfig> rows = new ArrayList<>();
        List<ConstraintEntry> entryRows = new ArrayList<>();
        for (PlannedConfig plan : planned) {
            ConstraintConfig config = new ConstraintConfig();
            config.setSemesterId(resolved);
            config.setType(plan.type().name());
            // The legacy column keeps the first entry so older readers and raw SQL
            // still see a meaningful single value.
            config.setParamsJson(toJson(plan.entries().isEmpty() ? Map.of() : plan.entries().get(0)));
            config.setWeight(plan.weight());
            config.setHard(plan.hard());
            config.setEnabled(plan.enabled());
            rows.add(config);
            for (Map<String, Object> params : plan.entries()) {
                ConstraintEntry entry = new ConstraintEntry();
                entry.setSemesterId(resolved);
                entry.setType(plan.type().name());
                entry.setParamsJson(toJson(params));
                entry.setCreatedAt(now);
                entryRows.add(entry);
            }
        }
        entries.deleteBySemesterId(resolved);
        configs.deleteBySemesterId(resolved);
        configs.flush();
        List<ConstraintConfig> saved = configs.saveAll(rows);
        List<ConstraintEntry> savedEntries = entries.saveAll(entryRows);
        Map<String, List<ConstraintEntry>> byType = entriesByType(savedEntries);
        return saved.stream()
                .map(config -> toDto(config, byType.get(config.getType())))
                .toList();
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
        return toDto(templates.save(template), snapshot.size());
    }

    @Transactional(readOnly = true)
    public List<TemplateDto> listTemplates() {
        return templates.findAllByOrderByCreatedAtDesc().stream()
                .map(template -> toDto(template, readSnapshot(template).size()))
                .toList();
    }

    @Transactional
    public List<ConstraintConfigDto> applyTemplate(Long templateId, Long semesterId) {
        ConstraintTemplate template = templates.findById(templateId)
                .orElseThrow(() -> ApiException.notFound("约束模板不存在: " + templateId));
        return save(semesterId, readSnapshot(template));
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

    private record PlannedConfig(ConstraintType type, int weight, boolean hard, boolean enabled,
                                 List<Map<String, Object>> entries) {}

    /** One accepted entry with the 1-based position it had in the request. */
    private record PlannedEntry(Map<String, Object> params, Integer position) {}

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
     * identical entries are rejected: the union would otherwise count the same
     * fact twice and hide a duplicate row in the editor. Contradictory scalar
     * conditions for the same object are rejected too.
     */
    private List<Map<String, Object>> planEntries(
            ConstraintType type, ConstraintConfigRequest request, SemesterScope timetable,
            List<String> errors) {
        List<PlannedEntry> planned = new ArrayList<>();
        Map<Map<String, Object>, Integer> seen = new HashMap<>();
        List<ConstraintEntryRequest> requested = request.entries();
        if (requested == null) {
            // Legacy shape: `params` is the single entry, and its messages keep the
            // pre-ADR wording.
            Map<String, Object> params = validateParams(type, request.params(), null, errors);
            accept(type, null, params, planned, seen, timetable, errors);
            return planned.stream().map(PlannedEntry::params).toList();
        }
        if (type.getParams().isEmpty() && !requested.isEmpty()) {
            errors.add(type.getDisplayName() + "(" + type.name() + "): 该类型没有参数，不能配置条目");
            return List.of();
        }
        for (int index = 0; index < requested.size(); index++) {
            ConstraintEntryRequest raw = requested.get(index);
            int position = index + 1;
            Map<String, Object> params = validateParams(
                    type, raw == null ? null : raw.params(), position, errors);
            accept(type, position, params, planned, seen, timetable, errors);
        }
        rejectContradictoryScalars(type, planned, errors);
        rejectDuplicateThresholdScopes(type, planned, errors);
        return planned.stream().map(PlannedEntry::params).toList();
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
            ConstraintType type, Integer position, Map<String, Object> params,
            List<PlannedEntry> planned, Map<Map<String, Object>, Integer> seen,
            SemesterScope timetable, List<String> errors) {
        if (params.isEmpty() && !type.isEntryBacked()) {
            // Nothing to store: no object condition. `{}` means "no extra
            // condition" and is kept in the legacy params column only. For an
            // entry-backed type whose target is optional `{}` is a real entry: the
            // explicit "every course / the sport class" row.
            return;
        }
        Integer first = seen.putIfAbsent(params, position);
        if (first != null) {
            errors.add(location(type, position) + ": 与第" + first + " 条完全相同，重复条目已拒绝");
            return;
        }
        validateObjects(type, position, params, timetable, errors);
        planned.add(new PlannedEntry(params, position));
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
     * Object-level checks for the entry-backed types: teachers and courses are
     * global assets, semester timetables decide which slots exist. Everything else
     * keeps its old schema-only validation so its behaviour cannot drift.
     */
    private void validateObjects(
            ConstraintType type, Integer position, Map<String, Object> params,
            SemesterScope timetable, List<String> errors) {
        if (!type.isEntryBacked()) {
            return;
        }
        String location = location(type, position);
        switch (type) {
            case TEACHER_UNAVAILABLE, TEACHER_PREFERRED_SLOT -> {
                requireTeacher(location, params, errors);
                requireSlot(location, params, timetable, errors);
            }
            case TEACHER_MEETING_BLOCKED -> {
                requireSubject(location, params, errors);
                requireSlot(location, params, timetable, errors);
            }
            case TEACHER_NO_FIRST_PERIOD -> requireTeacher(location, params, errors);
            case TEACHER_DAY_OFF -> requireTeacher(location, params, errors);
            case COURSE_REQUIRED_ROOM_TYPE, COURSE_NOT_IN_EVENING, CONSECUTIVE_LESSONS,
                 MAIN_SUBJECT_MORNING -> {
                requireCourse(location, params, errors);
                if (type == ConstraintType.MAIN_SUBJECT_MORNING) {
                    requirePeriodNo(location, params, "beforePeriodNo", "上午截止节次", timetable, errors);
                }
            }
            case SAME_COURSE_ONCE_PER_DAY, SPORT_NOT_FIRST_AFTERNOON, COURSE_SPREAD,
                 PE_LAST_PERIOD_AVOID -> requireCourseIfPresent(location, params, errors);
            // Threshold entries name their object only in the exception form; a blank
            // object is the global default and needs no lookup.
            case TEACHER_MAX_PER_DAY, TEACHER_MAX_CONSECUTIVE -> requireTeacherIfPresent(
                    location, params, errors);
            case COURSE_MAX_PER_DAY -> requireCourseIfPresent(location, params, errors);
            case CLASS_MAX_PER_DAY, EVENING_MAX -> requireClassIfPresent(
                    location, params, timetable, errors);
            default -> {
                // Single-switch types keep their legacy validation.
            }
        }
    }

    private void requireTeacherIfPresent(
            String location, Map<String, Object> params, List<String> errors) {
        Long teacherId = ConstraintSettings.longValue(params.get("teacherId"));
        if (teacherId != null && !teachers.existsById(teacherId)) {
            errors.add(location + ": 参数「教师」指向不存在的教师: " + teacherId);
        }
    }

    /** Threshold classes belong to the semester being configured, never to another one. */
    private void requireClassIfPresent(
            String location, Map<String, Object> params, SemesterScope semester, List<String> errors) {
        Long classId = ConstraintSettings.longValue(params.get("classId"));
        if (classId != null && !semester.hasClass(classId)) {
            errors.add(location + ": 参数「班级」不属于当前学期: " + classId);
        }
    }

    private void requireTeacher(String location, Map<String, Object> params, List<String> errors) {
        Long teacherId = ConstraintSettings.longValue(params.get("teacherId"));
        if (teacherId == null) {
            errors.add(location + ": 参数「教师」必须是教师");
        } else if (!teachers.existsById(teacherId)) {
            errors.add(location + ": 参数「教师」指向不存在的教师: " + teacherId);
        }
    }

    private void requireCourse(String location, Map<String, Object> params, List<String> errors) {
        Long courseId = ConstraintSettings.longValue(params.get("courseId"));
        if (courseId == null) {
            errors.add(location + ": 参数「课程」必须是课程");
        } else if (!courses.existsById(courseId)) {
            errors.add(location + ": 参数「课程」指向不存在的课程: " + courseId);
        }
    }

    private void requireCourseIfPresent(
            String location, Map<String, Object> params, List<String> errors) {
        Long courseId = ConstraintSettings.longValue(params.get("courseId"));
        if (courseId != null && !courses.existsById(courseId)) {
            errors.add(location + ": 参数「课程」指向不存在的课程: " + courseId);
        }
    }

    /**
     * A subject entry must name a subject the school actually uses. The catalogue is
     * the union of teacher subjects and course subjects; when the school has not
     * filled any in yet there is nothing to check against, so the entry is accepted.
     */
    private void requireSubject(String location, Map<String, Object> params, List<String> errors) {
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
        ConstraintType type;
        try {
            type = ConstraintType.valueOf(config.getType());
        } catch (IllegalArgumentException ex) {
            type = null;
        }
        String name = type == null ? config.getType() : type.getDisplayName();
        Category category = type == null ? Category.PEDAGOGY : type.getCategory();
        String description = type == null ? "" : type.getDescription();
        List<ConstraintEntryDto> entryDtos = entryDtos(config, entryRows);
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
                    .map(entry -> new ConstraintEntryDto(entry.getId(), paramsFromJson(entry.getParamsJson())))
                    .forEach(dtos::add);
        }
        if (dtos.isEmpty() && !isEntryBacked(config.getType())) {
            Map<String, Object> legacy = paramsFromJson(config.getParamsJson());
            if (!legacy.isEmpty()) {
                dtos.add(new ConstraintEntryDto(null, legacy));
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
                        .map(entry -> new ConstraintEntryRequest(null, entry.params()))
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

    private TemplateDto toDto(ConstraintTemplate template, int count) {
        return new TemplateDto(template.getId(), template.getName(), template.getCreatedAt(), count);
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
