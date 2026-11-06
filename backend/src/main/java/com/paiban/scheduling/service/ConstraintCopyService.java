package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.web.ApiException;
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
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.RuleGroup;
import com.paiban.scheduling.domain.RuleGroupRepository;
import com.paiban.scheduling.domain.RuleGroupType;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintConfigDto;
import com.paiban.scheduling.web.ConstraintDtos.ConstraintStateDto;
import com.paiban.scheduling.web.ConstraintDtos.CopyRuleIssue;
import com.paiban.scheduling.web.ConstraintDtos.CopyRulesRequest;
import com.paiban.scheduling.web.ConstraintDtos.CopyRulesResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Copies one semester's rule set onto another (ticket 08, contract §6).
 *
 * <p>The copy replaces the target's <em>non-system</em> configuration as a whole: the
 * two hard baselines ({@code TEACHER_CONFLICT}/{@code CLASS_CONFLICT}) belong to every
 * semester and are never copied or removed. Teachers and courses are global assets, so
 * their ids travel unchanged; a time group maps by name to the target semester's live
 * group; a slot maps by its (weekday, period number) coordinate.
 *
 * <p>A card whose references cannot all be resolved is still copied but paused, and
 * its reason is returned in {@link CopyRulesResult#issues()}. Pausing is per card (all
 * rows of one {@code groupKey}), never per row: the card is the unit the user edits.
 *
 * <p>Writes bypass {@link ConstraintConfigService#save} on purpose: the save path
 * refuses exactly the unresolvable references this feature is required to copy as
 * paused cards. The version, the audit row and the workspace-draft reset still go
 * through the shared service, so a copy is indistinguishable from any other write for
 * concurrent editors.
 */
@Service
public class ConstraintCopyService {

    /** The two baselines every semester owns; never copied, never removed. */
    private static final Set<String> SYSTEM_TYPES = Set.of(
            ConstraintType.TEACHER_CONFLICT.name(),
            ConstraintType.CLASS_CONFLICT.name());

    private static final int GROUP_KEY_MAX_LENGTH = 64;

    private final ConstraintConfigRepository configs;
    private final ConstraintEntryRepository entries;
    private final ConstraintConfigService configService;
    private final ConstraintAuditService auditService;
    private final SemesterService semesters;
    private final TeacherRepository teachers;
    private final CourseRepository courses;
    private final PeriodRepository periods;
    private final RuleGroupRepository ruleGroups;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ConstraintCopyService(
            ConstraintConfigRepository configs,
            ConstraintEntryRepository entries,
            ConstraintConfigService configService,
            ConstraintAuditService auditService,
            SemesterService semesters,
            TeacherRepository teachers,
            CourseRepository courses,
            PeriodRepository periods,
            RuleGroupRepository ruleGroups,
            ObjectMapper objectMapper,
            Clock clock) {
        this.configs = configs;
        this.entries = entries;
        this.configService = configService;
        this.auditService = auditService;
        this.semesters = semesters;
        this.teachers = teachers;
        this.courses = courses;
        this.periods = periods;
        this.ruleGroups = ruleGroups;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public CopyRulesResult copy(CopyRulesRequest request, String actor) {
        Long sourceId = requireSource(request);
        Long targetId = requireTarget(request);

        ConstraintStateDto targetState = configService.state(targetId);
        long currentVersion = targetState.version();
        if (request.baseVersion() != null && request.baseVersion() != currentVersion) {
            throw ApiException.conflict(
                    "目标学期规则已被修改（你基于版本 " + request.baseVersion()
                            + "，当前版本 " + currentVersion + "），请刷新页面后重试");
        }
        List<ConstraintConfigDto> previous = targetState.configs();

        MappingContext context = new MappingContext(
                targetId, teacherMap(sourceId), courseMap(sourceId),
                groupMap(sourceId), targetTimeGroups(targetId), targetSlots(targetId));

        List<CopyRuleIssue> issues = new ArrayList<>();
        List<ConstraintEntry> newRows = new ArrayList<>();
        Map<String, ConstraintConfig> newConfigs = new LinkedHashMap<>();
        int copiedCards = 0;
        int disabledCards = 0;
        int cardIndex = 0;

        for (ConstraintConfig sourceConfig : sourceConfigs(sourceId)) {
            ConstraintType type = typeOrNull(sourceConfig.getType());
            if (type == null) {
                // A code the catalogue no longer knows cannot be shown or edited; a copy
                // must not smuggle it back into the rule page.
                continue;
            }
            for (List<ConstraintEntry> card : cardsOf(type, sourceEntries(sourceId, type))) {
                String groupKey = groupKeyOf(cardIndex++);
                List<String> reasons = new ArrayList<>();
                List<ConstraintEntry> cardRows = new ArrayList<>();
                for (ConstraintEntry sourceRow : card) {
                    Map<String, Object> params = paramsOf(sourceRow.getParamsJson());
                    mapReferences(params, context, reasons);
                    cardRows.add(toEntry(sourceRow, params, type, context.targetId()));
                }
                boolean unusable = !reasons.isEmpty();
                for (ConstraintEntry row : cardRows) {
                    row.setEnabled(row.isEnabled() && !unusable);
                    row.setGroupKey(groupKey);
                }
                newConfigs.computeIfAbsent(type.name(),
                        key -> toConfig(sourceConfig, type, context.targetId()));
                newRows.addAll(cardRows);
                copiedCards++;
                if (unusable) {
                    disabledCards++;
                    issues.add(new CopyRuleIssue(
                            type.name(), type.getDisplayName(), groupKey,
                            String.join("；", distinct(reasons))));
                }
            }
        }

        replaceTarget(targetId, newConfigs.values(), newRows);

        long version = configService.advanceVersion(targetId);
        configService.clearWorkspaceDraft(actor, targetId);
        List<ConstraintConfigDto> after = configService.state(targetId).configs();
        ConstraintAuditService.AuditContext audit =
                auditService.prepare(targetId, null, actor);
        auditService.record(targetId, version, audit, List.of(), previous, after);
        return new CopyRulesResult(copiedCards, disabledCards, issues);
    }

    // ------------------------------------------------------------- request scope

    private Long requireSource(CopyRulesRequest request) {
        if (request == null || request.sourceSemesterId() == null) {
            throw ApiException.badRequest("必须指定来源学期（sourceSemesterId）");
        }
        return semesters.require(request.sourceSemesterId()).getId();
    }

    private Long requireTarget(CopyRulesRequest request) {
        if (request.targetSemesterId() == null) {
            throw ApiException.badRequest("必须指定目标学期（targetSemesterId）");
        }
        Long targetId = semesters.require(request.targetSemesterId()).getId();
        if (targetId.equals(request.sourceSemesterId())) {
            throw ApiException.badRequest("来源学期和目标学期不能相同");
        }
        return targetId;
    }

    // --------------------------------------------------------------- source data

    /**
     * The source's live non-system type rows in insertion order. Recycled rows are
     * neither shown nor copied: they are not part of the source configuration.
     */
    private List<ConstraintConfig> sourceConfigs(Long sourceId) {
        return configs.findBySemesterId(sourceId).stream()
                .filter(config -> config.getDeletedAt() == null)
                .filter(config -> !isSystemType(config.getType()))
                .sorted(Comparator.comparing(ConstraintConfig::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private List<ConstraintEntry> sourceEntries(Long sourceId, ConstraintType type) {
        return entries.findBySemesterIdAndTypeOrderByIdAsc(sourceId, type.name()).stream()
                .filter(entry -> entry.getDeletedAt() == null)
                .toList();
    }

    /**
     * Splits one type's rows into cards: rows sharing a {@code groupKey} are one card,
     * a row without one is a card of its own. A type with no rows is one card only when
     * it is not entry-backed (the single-switch types store their meaning on the config
     * row itself); for an entry-backed type no rows means "not configured", so copying
     * it would create an empty promise.
     */
    private List<List<ConstraintEntry>> cardsOf(ConstraintType type, List<ConstraintEntry> rows) {
        if (rows.isEmpty()) {
            return type.isEntryBacked() ? List.of() : List.of(List.of());
        }
        Map<String, List<ConstraintEntry>> byGroup = new LinkedHashMap<>();
        for (ConstraintEntry row : rows) {
            if (StringUtils.hasText(row.getGroupKey())) {
                byGroup.computeIfAbsent(row.getGroupKey().trim(), key -> new ArrayList<>())
                        .add(row);
            } else {
                byGroup.put("row:" + row.getId(), List.of(row));
            }
        }
        return new ArrayList<>(byGroup.values());
    }

    /**
     * {@code copy-<uuid>:<index>}. The prefix keeps a copied card distinguishable from
     * the source's own keys, and the index keeps two cards unique even if the same UUID
     * were ever reused. Well under the 64-character column limit.
     */
    private String groupKeyOf(int index) {
        String key = "copy-" + UUID.randomUUID() + ":" + index;
        return key.length() <= GROUP_KEY_MAX_LENGTH
                ? key
                : key.substring(0, GROUP_KEY_MAX_LENGTH);
    }

    // ------------------------------------------------------------------- mapping

    /** Everything one copy needs to resolve: no per-row queries inside the loop. */
    private record MappingContext(
            Long targetId,
            Map<Long, Teacher> teachers,
            Map<Long, Course> courses,
            Map<Long, RuleGroup> groups,
            Map<String, RuleGroup> targetTimeGroups,
            Set<String> slots) {}

    private Map<Long, Teacher> teacherMap(Long sourceId) {
        Set<Long> ids = referencedIds(sourceId, "teacherId");
        Map<Long, Teacher> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            teachers.findAllById(ids).forEach(row -> byId.put(row.getId(), row));
        }
        return byId;
    }

    private Map<Long, Course> courseMap(Long sourceId) {
        Set<Long> ids = referencedIds(sourceId, "courseId");
        Map<Long, Course> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            courses.findAllById(ids).forEach(row -> byId.put(row.getId(), row));
        }
        return byId;
    }

    private Map<Long, RuleGroup> groupMap(Long sourceId) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.addAll(referencedIds(sourceId, "teacherGroupId"));
        ids.addAll(referencedIds(sourceId, "courseGroupId"));
        ids.addAll(referencedIds(sourceId, "timeGroupId"));
        Map<Long, RuleGroup> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            ruleGroups.findAllById(ids).forEach(row -> byId.put(row.getId(), row));
        }
        return byId;
    }

    /** One field's ids across every live non-system source row. */
    private Set<Long> referencedIds(Long sourceId, String field) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ConstraintEntry entry : entries.findBySemesterId(sourceId)) {
            if (entry.getDeletedAt() != null || isSystemType(entry.getType())) {
                continue;
            }
            Long id = ConstraintSettings.longValue(paramsOf(entry.getParamsJson()).get(field));
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Live TIME groups of the target semester, looked up by their name. */
    private Map<String, RuleGroup> targetTimeGroups(Long targetId) {
        Map<String, RuleGroup> byName = new LinkedHashMap<>();
        for (RuleGroup group : ruleGroups.findBySemesterIdAndDeletedAtIsNullOrderByNameAsc(targetId)) {
            if (group.getGroupType() == RuleGroupType.TIME) {
                byName.putIfAbsent(group.getName(), group);
            }
        }
        return byName;
    }

    private Set<String> targetSlots(Long targetId) {
        Set<String> slots = new LinkedHashSet<>();
        for (Period period : periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(targetId)) {
            slots.add(period.getDayOfWeek() + "-" + period.getPeriodNo());
        }
        return slots;
    }

    /**
     * Resolves one row's references against the target semester and rewrites the params
     * in place. Every failure only appends a reason; the caller pauses the whole card
     * and keeps the params so the page can show and repair the row.
     */
    private void mapReferences(
            Map<String, Object> params, MappingContext context, List<String> reasons) {
        mapTeacher(params.get("teacherId"), context, reasons);
        mapCourse(params.get("courseId"), context, reasons);
        mapGroup(params.get("teacherGroupId"), RuleGroupType.TEACHER, context, reasons);
        mapGroup(params.get("courseGroupId"), RuleGroupType.COURSE, context, reasons);
        boolean hadTimeGroup = mapTimeGroup(params, context, reasons);
        if (!hadTimeGroup) {
            mapSlot(params, context, reasons);
        }
    }

    private void mapTeacher(Object raw, MappingContext context, List<String> reasons) {
        Long id = ConstraintSettings.longValue(raw);
        if (id == null) {
            return;
        }
        Teacher teacher = context.teachers().get(id);
        if (teacher == null || teacher.getDeletedAt() != null) {
            reasons.add("教师已删除：#" + id);
        } else if (!teacher.isActive()) {
            reasons.add("教师已停用：" + teacher.getName());
        }
    }

    private void mapCourse(Object raw, MappingContext context, List<String> reasons) {
        Long id = ConstraintSettings.longValue(raw);
        if (id == null) {
            return;
        }
        Course course = context.courses().get(id);
        if (course == null || course.getDeletedAt() != null) {
            reasons.add("课程已删除：#" + id);
        } else if (!course.isActive()) {
            reasons.add("课程已停用：" + course.getName());
        }
    }

    /** Teacher/course groups are global, so a live group of the right type needs no rewrite. */
    private void mapGroup(
            Object raw, RuleGroupType expected, MappingContext context, List<String> reasons) {
        Long id = ConstraintSettings.longValue(raw);
        if (id == null) {
            return;
        }
        RuleGroup group = context.groups().get(id);
        if (group == null) {
            reasons.add("分组已删除：#" + id);
        } else if (group.getDeletedAt() != null) {
            reasons.add("分组已删除：" + group.getName());
        } else if (group.getGroupType() != expected) {
            reasons.add("分组类型不匹配：" + group.getName());
        }
    }

    /**
     * Time groups are semester-owned, so only the name is portable. On success the row
     * points at the target semester's group id; on failure the reference is cleared so
     * the copied row cannot accidentally point at a same-id group of the target.
     *
     * @return {@code true} when the row had a time group, so the slot check is skipped
     */
    private boolean mapTimeGroup(
            Map<String, Object> params, MappingContext context, List<String> reasons) {
        Long id = ConstraintSettings.longValue(params.get("timeGroupId"));
        if (id == null) {
            return false;
        }
        RuleGroup source = context.groups().get(id);
        if (source == null || source.getDeletedAt() != null) {
            reasons.add("分组已删除：#" + id);
            params.remove("timeGroupId");
            return true;
        }
        if (source.getGroupType() != RuleGroupType.TIME) {
            reasons.add("分组类型不匹配：" + source.getName());
            params.remove("timeGroupId");
            return true;
        }
        RuleGroup target = context.targetTimeGroups().get(source.getName());
        if (target == null) {
            reasons.add("本学期没有时间组：" + source.getName());
            params.remove("timeGroupId");
            return true;
        }
        params.put("timeGroupId", target.getId());
        return true;
    }

    /**
     * A (weekday, period) pair maps only when the target timetable has that coordinate.
     * A day-only parameter (a fixed day off) needs the weekday to have at least one
     * period. The values stay in the params so the page can show what failed.
     */
    private void mapSlot(
            Map<String, Object> params, MappingContext context, List<String> reasons) {
        Integer day = ConstraintSettings.intValue(params.get("dayOfWeek"));
        if (day == null) {
            return;
        }
        Integer periodNo = ConstraintSettings.intValue(params.get("periodNo"));
        if (periodNo != null) {
            if (!context.slots().contains(day + "-" + periodNo)) {
                reasons.add("星期" + day + "第" + periodNo + "节在本学期作息中不存在");
            }
            return;
        }
        if (context.slots().stream().noneMatch(slot -> slot.startsWith(day + "-"))) {
            reasons.add("星期" + day + "在本学期作息中不存在");
        }
    }

    // --------------------------------------------------------------- target rows

    private ConstraintEntry toEntry(
            ConstraintEntry source, Map<String, Object> params, ConstraintType type,
            Long targetId) {
        ConstraintEntry row = new ConstraintEntry();
        row.setSemesterId(targetId);
        row.setType(type.name());
        row.setParamsJson(toJson(params));
        row.setCreatedAt(Instant.now(clock));
        row.setEnabled(source.isEnabled());
        row.setHard(source.getHard());
        row.setWeight(source.getWeight());
        return row;
    }

    private ConstraintConfig toConfig(ConstraintConfig source, ConstraintType type, Long targetId) {
        ConstraintConfig config = new ConstraintConfig();
        config.setSemesterId(targetId);
        config.setType(type.name());
        // Entry-backed types read their values from rows; the single-switch types have no
        // parameters at all, so "{}" is the honest value in both cases.
        config.setParamsJson("{}");
        config.setWeight(source.getWeight());
        config.setHard(source.isHard());
        config.setEnabled(source.isEnabled());
        return config;
    }

    /**
     * Replaces every live non-system row of the target with the copied set. The hard
     * baselines are untouched. Rows are deleted before the new ones are written because
     * {@code constraint_config} is unique on (semester_id, type).
     */
    private void replaceTarget(
            Long targetId, Iterable<ConstraintConfig> newConfigs, List<ConstraintEntry> newRows) {
        List<ConstraintConfig> removedConfigs = configs.findBySemesterId(targetId).stream()
                .filter(config -> !isSystemType(config.getType()))
                .toList();
        List<ConstraintEntry> removedEntries = entries.findBySemesterId(targetId).stream()
                .filter(entry -> !isSystemType(entry.getType()))
                .toList();
        if (!removedEntries.isEmpty()) {
            entries.deleteAll(removedEntries);
            entries.flush();
        }
        if (!removedConfigs.isEmpty()) {
            configs.deleteAll(removedConfigs);
            configs.flush();
        }

        List<ConstraintConfig> configsToSave = new ArrayList<>();
        newConfigs.forEach(configsToSave::add);
        if (!configsToSave.isEmpty()) {
            configs.saveAll(configsToSave);
            configs.flush();
        }
        if (!newRows.isEmpty()) {
            entries.saveAll(newRows);
            entries.flush();
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<String> distinct(List<String> reasons) {
        return new ArrayList<>(new LinkedHashSet<>(reasons));
    }

    private boolean isSystemType(String type) {
        return type != null && SYSTEM_TYPES.contains(type);
    }

    private ConstraintType typeOrNull(String type) {
        if (!StringUtils.hasText(type)) {
            return null;
        }
        try {
            return ConstraintType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Map<String, Object> paramsOf(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(
                    objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}));
        } catch (JsonProcessingException ex) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化约束参数失败", ex);
        }
    }
}
