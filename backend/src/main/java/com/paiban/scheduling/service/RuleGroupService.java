package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.deletion.DeletionRestoreHandler;
import com.paiban.common.deletion.RecentDeletion;
import com.paiban.common.deletion.RecentDeletionWriter;
import com.paiban.common.event.PeriodsChangedEvent;
import com.paiban.common.event.SemesterCreatedEvent;
import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.SemesterService;
import com.paiban.scheduling.domain.ConstraintConfigRepository;
import com.paiban.scheduling.domain.ConstraintEntry;
import com.paiban.scheduling.domain.ConstraintEntryRepository;
import com.paiban.scheduling.domain.ConstraintType;
import com.paiban.scheduling.domain.RuleGroup;
import com.paiban.scheduling.domain.RuleGroupMember;
import com.paiban.scheduling.domain.RuleGroupMemberRepository;
import com.paiban.scheduling.domain.RuleGroupRepository;
import com.paiban.scheduling.domain.RuleGroupType;
import com.paiban.scheduling.solver.ConstraintSettings;
import com.paiban.scheduling.web.RuleGroupDtos.RuleGroupDto;
import com.paiban.scheduling.web.RuleGroupDtos.RuleGroupImpactDto;
import com.paiban.scheduling.web.RuleGroupDtos.RuleGroupMemberDto;
import com.paiban.scheduling.web.RuleGroupDtos.RuleGroupMemberRequest;
import com.paiban.scheduling.web.RuleGroupDtos.RuleGroupReferenceDto;
import com.paiban.scheduling.web.RuleGroupDtos.RuleGroupRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Teacher / course / time groups (ticket 04): CRUD, reference impact and the 7-day
 * delete/restore lifecycle.
 *
 * <p>A group is only a batch selector; it is never evaluated. Rules store the group id
 * and {@link RuleGroupExpander} turns it into concrete members when the snapshot is
 * built, so a member change reaches every referencing rule without resaving it.
 *
 * <p>Deletion follows the product rule: a referenced group is refused with the list of
 * rules using it, unless the caller explicitly asks for {@code WITH_RULES}. The group
 * and each rule card then land in the recycle bin as separate batches (a rule card
 * already owns its own batch through {@link RuleLifecycleService}), which is what lets
 * the user restore the group and the rules independently.
 */
@Service
public class RuleGroupService implements DeletionRestoreHandler {

    static final String ENTITY_TYPE = "RULE_GROUP";
    private static final String MODE_DELETE = "DELETE";
    private static final String MODE_WITH_RULES = "WITH_RULES";

    /** The two time groups every semester starts with (ticket 06). */
    static final String MORNING_GROUP = "上午";
    static final String AFTERNOON_GROUP = "下午";
    /** Slots starting at or after this time belong to 「下午」, matching V58. */
    private static final LocalTime NOON = LocalTime.of(12, 0);

    private final RuleGroupRepository groups;
    private final RuleGroupMemberRepository members;
    private final TeacherRepository teachers;
    private final CourseRepository courses;
    private final PeriodRepository periods;
    private final SemesterService semesters;
    private final ConstraintEntryRepository entries;
    private final ConstraintConfigRepository configs;
    private final RuleLifecycleService ruleLifecycle;
    private final RecentDeletionWriter deletionWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RuleGroupService(
            RuleGroupRepository groups,
            RuleGroupMemberRepository members,
            TeacherRepository teachers,
            CourseRepository courses,
            PeriodRepository periods,
            SemesterService semesters,
            ConstraintEntryRepository entries,
            ConstraintConfigRepository configs,
            RuleLifecycleService ruleLifecycle,
            RecentDeletionWriter deletionWriter,
            ObjectMapper objectMapper,
            Clock clock) {
        this.groups = groups;
        this.members = members;
        this.teachers = teachers;
        this.courses = courses;
        this.periods = periods;
        this.semesters = semesters;
        this.entries = entries;
        this.configs = configs;
        this.ruleLifecycle = ruleLifecycle;
        this.deletionWriter = deletionWriter;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public List<RuleGroupDto> list(String type, Long semesterId) {
        RuleGroupType groupType = parseType(type);
        List<RuleGroup> rows;
        if (groupType == RuleGroupType.TIME) {
            if (semesterId == null) {
                throw ApiException.badRequest("时间分组必须指定学期（semesterId）");
            }
            semesters.require(semesterId);
            rows = groups.findBySemesterIdAndDeletedAtIsNullOrderByNameAsc(semesterId);
        } else {
            rows = groups.findByGroupTypeAndDeletedAtIsNullOrderByNameAsc(groupType);
        }
        return toDtos(rows);
    }

    // --------------------------------------------------------- prebuilt groups

    /**
     * A new semester starts with the editable 「上午」/「下午」 time groups (ticket 06).
     * They are empty until the timetable exists; {@link #onPeriodsChanged} then fills
     * them from {@code period.start_time}. A semester that already has a live time group
     * was seeded before (migrated data), so it is left alone.
     */
    @EventListener
    @Transactional
    public void onSemesterCreated(SemesterCreatedEvent event) {
        Long semesterId = event.semesterId();
        if (semesterId == null
                || !groups.findBySemesterIdAndDeletedAtIsNullOrderByNameAsc(semesterId).isEmpty()) {
            return;
        }
        createPrebuilt(semesterId, MORNING_GROUP);
        createPrebuilt(semesterId, AFTERNOON_GROUP);
    }

    /** Keeps the semester's time groups aligned with its timetable after a period save. */
    @EventListener
    @Transactional
    public void onPeriodsChanged(PeriodsChangedEvent event) {
        syncTimeGroups(event.semesterId());
    }

    /**
     * Two rules apply after a period save:
     *
     * <ul>
     *   <li>a prebuilt group still following the timetable ({@code autoFilled} and the
     *       original name) is recomputed from {@code start_time}, even when the new
     *       member set is empty;</li>
     *   <li>every other group only loses the coordinates that no longer exist — a user
     *       edit is never overwritten.</li>
     * </ul>
     */
    @Transactional
    public void syncTimeGroups(Long semesterId) {
        if (semesterId == null) {
            return;
        }
        SlotSets slots = slotSets(semesterId);
        for (RuleGroup group : groups.findBySemesterIdAndDeletedAtIsNullOrderByNameAsc(semesterId)) {
            if (group.getGroupType() != RuleGroupType.TIME) {
                continue;
            }
            if (group.isAutoFilled() && MORNING_GROUP.equals(group.getName())) {
                replaceSlotMembers(group, slots.morning());
            } else if (group.isAutoFilled() && AFTERNOON_GROUP.equals(group.getName())) {
                replaceSlotMembers(group, slots.afternoon());
            } else {
                pruneMissingSlots(group, slots.all());
            }
        }
    }

    private void createPrebuilt(Long semesterId, String name) {
        Instant now = Instant.now(clock);
        RuleGroup group = new RuleGroup();
        group.setGroupType(RuleGroupType.TIME);
        group.setName(name);
        group.setSemesterId(semesterId);
        group.setLiveKey(liveKey(RuleGroupType.TIME, name, semesterId));
        group.setAutoFilled(true);
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        groups.saveAndFlush(group);
        SlotSets slots = slotSets(semesterId);
        replaceSlotMembers(group, MORNING_GROUP.equals(name) ? slots.morning() : slots.afternoon());
    }

    /**
     * Rebuilds an auto group's members. A direct delete keeps the
     * {@code (group_id, member_key)} unique index safe when a coordinate survives a save.
     */
    private void replaceSlotMembers(RuleGroup group, Set<SlotKey> slots) {
        members.deleteByGroupId(group.getId());
        members.flush();
        if (!slots.isEmpty()) {
            List<RuleGroupMember> rows = new ArrayList<>(slots.size());
            for (SlotKey slot : slots) {
                RuleGroupMember member = new RuleGroupMember();
                member.setGroupId(group.getId());
                member.setMemberKey("S:" + slot.key());
                member.setDayOfWeek(slot.dayOfWeek());
                member.setPeriodNo(slot.periodNo());
                rows.add(member);
            }
            members.saveAll(rows);
            members.flush();
        }
        group.setUpdatedAt(Instant.now(clock));
        groups.save(group);
    }

    /** Drops members whose weekday+period no longer exists in the semester timetable. */
    private void pruneMissingSlots(RuleGroup group, Set<String> liveSlots) {
        List<RuleGroupMember> stale = new ArrayList<>();
        for (RuleGroupMember member : members.findByGroupIdOrderByIdAsc(group.getId())) {
            if (member.getDayOfWeek() == null || member.getPeriodNo() == null
                    || !liveSlots.contains(member.getDayOfWeek() + "-" + member.getPeriodNo())) {
                stale.add(member);
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        members.deleteAll(stale);
        members.flush();
        group.setUpdatedAt(Instant.now(clock));
        groups.save(group);
    }

    /** The semester timetable split the same way V58 splits it: at 12:00. */
    private SlotSets slotSets(Long semesterId) {
        Set<String> all = new LinkedHashSet<>();
        Set<SlotKey> morning = new LinkedHashSet<>();
        Set<SlotKey> afternoon = new LinkedHashSet<>();
        for (Period period : periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)) {
            SlotKey slot = new SlotKey(period.getDayOfWeek(), period.getPeriodNo());
            all.add(slot.key());
            boolean isMorning = period.getStartTime() != null
                    && period.getStartTime().isBefore(NOON);
            (isMorning ? morning : afternoon).add(slot);
        }
        return new SlotSets(all, morning, afternoon);
    }

    private record SlotKey(int dayOfWeek, int periodNo) {
        String key() {
            return dayOfWeek + "-" + periodNo;
        }
    }

    private record SlotSets(Set<String> all, Set<SlotKey> morning, Set<SlotKey> afternoon) {}

    // ------------------------------------------------------------------- writing

    @Transactional
    public RuleGroupDto create(RuleGroupRequest request) {
        RuleGroupType type = parseType(request == null ? null : request.type());
        String name = normalizeName(request.name());
        Long semesterId = normalizeSemester(type, request.semesterId());
        List<MemberSpec> specs = validateMembers(
                type, semesterId, request.members() == null ? List.of() : request.members());
        String liveKey = liveKey(type, name, semesterId);
        if (groups.findByLiveKey(liveKey).isPresent()) {
            throw duplicateName(name);
        }
        Instant now = Instant.now(clock);
        RuleGroup group = new RuleGroup();
        group.setGroupType(type);
        group.setName(name);
        group.setSemesterId(semesterId);
        group.setLiveKey(liveKey);
        // A user-created group is never auto-recomputed by a timetable save.
        group.setAutoFilled(false);
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        try {
            groups.saveAndFlush(group);
        } catch (DataIntegrityViolationException ex) {
            throw duplicateName(name);
        }
        saveMembers(group.getId(), specs);
        return toDtos(List.of(group)).get(0);
    }

    @Transactional
    public RuleGroupDto update(Long id, RuleGroupRequest request) {
        RuleGroup group = requireLive(id);
        RuleGroupType requestedType = parseType(request == null ? null : request.type());
        if (requestedType != group.getGroupType()) {
            throw ApiException.badRequest("分组类型不可修改");
        }
        String name = normalizeName(request.name());
        String liveKey = liveKey(group.getGroupType(), name, group.getSemesterId());
        RuleGroup occupant = groups.findByLiveKey(liveKey).orElse(null);
        if (occupant != null && !occupant.getId().equals(group.getId())) {
            throw duplicateName(name);
        }
        List<MemberSpec> specs = validateMembers(
                group.getGroupType(), group.getSemesterId(),
                request.members() == null ? List.of() : request.members());
        members.deleteByGroupId(group.getId());
        members.flush();
        saveMembers(group.getId(), specs);
        group.setName(name);
        group.setLiveKey(liveKey);
        // The user touched this group, so a timetable save must not overwrite its members
        // again (ticket 06); the prebuilt name means nothing once it is user-owned.
        group.setAutoFilled(false);
        group.setUpdatedAt(Instant.now(clock));
        try {
            groups.saveAndFlush(group);
        } catch (DataIntegrityViolationException ex) {
            throw duplicateName(name);
        }
        return toDtos(List.of(group)).get(0);
    }

    // ------------------------------------------------------------- references

    @Transactional(readOnly = true)
    public RuleGroupImpactDto deleteImpact(Long id) {
        RuleGroup group = requireLive(id);
        List<RuleGroupReferenceDto> rules = referencesOf(id);
        return new RuleGroupImpactDto(
                group.getId(), group.getName(), group.getGroupType().name(), rules,
                impactMessage(group, rules));
    }

    /**
     * Every live rule card that names this group. The scan is a full table pass on
     * purpose: deletion is an admin action, and a JSON SQL predicate would couple this
     * service to one database dialect.
     */
    @Transactional(readOnly = true)
    public List<RuleGroupReferenceDto> referencesOf(Long groupId) {
        if (groupId == null) {
            return List.of();
        }
        Map<String, ReferenceAccumulator> byCard = new LinkedHashMap<>();
        for (ConstraintEntry entry : entries.findAll()) {
            // A recycled row is not part of the configuration (ticket 04) and must not
            // block deleting the group that only it mentions.
            if (entry.getDeletedAt() != null) {
                continue;
            }
            Map<String, Object> params = paramsOf(entry.getParamsJson());
            if (!referencesGroup(params, groupId)) {
                continue;
            }
            String cardKey = StringUtils.hasText(entry.getGroupKey())
                    ? "G:" + entry.getGroupKey().trim()
                    : "E:" + entry.getId();
            String key = entry.getSemesterId() + "|" + entry.getType() + "|" + cardKey;
            byCard.computeIfAbsent(key, ignored -> new ReferenceAccumulator(
                    entry.getSemesterId(), entry.getType(),
                    StringUtils.hasText(entry.getGroupKey()) ? entry.getGroupKey().trim() : null))
                    .entryIds.add(entry.getId());
        }
        List<RuleGroupReferenceDto> rules = new ArrayList<>();
        for (ReferenceAccumulator accumulator : byCard.values()) {
            rules.add(accumulator.toDto(this));
        }
        return rules;
    }

    // ------------------------------------------------------------------ delete

    @Transactional
    public Map<String, Object> delete(Long id, String mode) {
        RuleGroup group = requireLive(id);
        String resolvedMode = parseMode(mode);
        List<RuleGroupReferenceDto> references = referencesOf(id);
        if (MODE_DELETE.equals(resolvedMode) && !references.isEmpty()) {
            String message = impactMessage(group, references);
            throw ApiException.conflict(message, Map.of("message", message, "rules", references));
        }

        List<String> batchIds = new ArrayList<>();
        int deletedRules = 0;
        if (MODE_WITH_RULES.equals(resolvedMode)) {
            for (RuleGroupReferenceDto reference : references) {
                if (reference.configId() == null) {
                    continue;
                }
                Map<String, Object> result = ruleLifecycle.delete(
                        reference.configId(), reference.groupKey(), reference.entryIds());
                Object batchId = result.get("batchId");
                if (batchId != null) {
                    batchIds.add(String.valueOf(batchId));
                }
                deletedRules++;
            }
        }
        Instant now = Instant.now(clock);
        group.setDeletedAt(now);
        group.setLiveKey(null);
        group.setUpdatedAt(now);
        groups.save(group);
        // The group has its own recycle batch; each deleted rule card already owns one
        // (RuleLifecycleService). Restoring them independently keeps the intent explicit.
        String groupBatch = deletionWriter.record(
                ENTITY_TYPE, group.getId(), group.getName(), kindLabel(group.getGroupType()),
                true, null);
        batchIds.add(0, groupBatch);
        return Map.of("batchIds", batchIds, "deletedRules", deletedRules);
    }

    // ----------------------------------------------------------------- restore

    @Override
    public boolean supports(String entityType) {
        return ENTITY_TYPE.equalsIgnoreCase(entityType);
    }

    @Override
    public void restore(Long entityId) {
        RuleGroup group = groups.findById(entityId)
                .orElseThrow(() -> ApiException.notFound("分组不存在: " + entityId));
        if (group.getDeletedAt() == null) {
            return;
        }
        String liveKey = liveKey(group.getGroupType(), group.getName(), group.getSemesterId());
        RuleGroup occupant = groups.findByLiveKey(liveKey).orElse(null);
        if (occupant != null && !occupant.getId().equals(group.getId())) {
            throw ApiException.conflict("同名分组已存在，无法恢复：" + group.getName());
        }
        group.setDeletedAt(null);
        group.setLiveKey(liveKey);
        group.setUpdatedAt(Instant.now(clock));
        groups.save(group);
    }

    @Override
    public void restore(RecentDeletion deletion) {
        restore(deletion.getEntityId());
    }

    // ------------------------------------------------------------------ members

    private record MemberSpec(Long teacherId, Long courseId, Integer dayOfWeek,
                              Integer periodNo, String memberKey) {}

    private List<MemberSpec> validateMembers(
            RuleGroupType type, Long semesterId, List<RuleGroupMemberRequest> requested) {
        List<MemberSpec> specs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<Long> teacherIds = new LinkedHashSet<>();
        Set<Long> courseIds = new LinkedHashSet<>();
        for (int index = 0; index < requested.size(); index++) {
            RuleGroupMemberRequest row = requested.get(index);
            String where = "第" + (index + 1) + " 个成员";
            if (row == null) {
                throw ApiException.badRequest(where + "不能为空");
            }
            switch (type) {
                case TEACHER -> {
                    requireAbsent(row.courseId(), where + "不能指定课程");
                    requireAbsent(row.dayOfWeek(), where + "不能指定星期");
                    requireAbsent(row.periodNo(), where + "不能指定节次");
                    if (row.teacherId() == null) {
                        throw ApiException.badRequest(where + "缺少教师（teacherId）");
                    }
                    teacherIds.add(row.teacherId());
                    addSpec(specs, seen, new MemberSpec(
                            row.teacherId(), null, null, null, "T:" + row.teacherId()));
                }
                case COURSE -> {
                    requireAbsent(row.teacherId(), where + "不能指定教师");
                    requireAbsent(row.dayOfWeek(), where + "不能指定星期");
                    requireAbsent(row.periodNo(), where + "不能指定节次");
                    if (row.courseId() == null) {
                        throw ApiException.badRequest(where + "缺少课程（courseId）");
                    }
                    courseIds.add(row.courseId());
                    addSpec(specs, seen, new MemberSpec(
                            null, row.courseId(), null, null, "C:" + row.courseId()));
                }
                case TIME -> {
                    requireAbsent(row.teacherId(), where + "不能指定教师");
                    requireAbsent(row.courseId(), where + "不能指定课程");
                    if (row.dayOfWeek() == null || row.periodNo() == null) {
                        throw ApiException.badRequest(where + "缺少星期和节次");
                    }
                    if (row.dayOfWeek() < 1 || row.dayOfWeek() > 7) {
                        throw ApiException.badRequest(where + "的星期必须在 1-7 之间");
                    }
                    if (row.periodNo() < 1 || row.periodNo() > 20) {
                        throw ApiException.badRequest(where + "的节次必须在 1-20 之间");
                    }
                    addSpec(specs, seen, new MemberSpec(null, null,
                            row.dayOfWeek(), row.periodNo(),
                            "S:" + row.dayOfWeek() + "-" + row.periodNo()));
                }
                default -> throw ApiException.badRequest("不支持的分组类型");
            }
        }
        requireLiveTeachers(teacherIds);
        requireLiveCourses(courseIds);
        if (type == RuleGroupType.TIME) {
            Set<String> slots = periodSlots(semesterId);
            for (MemberSpec spec : specs) {
                if (!slots.contains(spec.dayOfWeek() + "-" + spec.periodNo())) {
                    throw ApiException.badRequest("星期" + spec.dayOfWeek() + "第"
                            + spec.periodNo() + "节不在本学期作息范围内");
                }
            }
        }
        return specs;
    }

    private void addSpec(List<MemberSpec> specs, Set<String> seen, MemberSpec spec) {
        if (seen.add(spec.memberKey())) {
            specs.add(spec);
        }
    }

    private void requireAbsent(Object value, String message) {
        if (value != null) {
            throw ApiException.badRequest(message);
        }
    }

    private void requireLiveTeachers(Set<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Set<Long> live = new LinkedHashSet<>();
        for (Teacher teacher : teachers.findAllById(ids)) {
            if (teacher.getDeletedAt() == null) {
                live.add(teacher.getId());
            }
        }
        for (Long id : ids) {
            if (!live.contains(id)) {
                throw ApiException.badRequest("教师不存在或已被删除: " + id);
            }
        }
    }

    private void requireLiveCourses(Set<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Set<Long> live = new LinkedHashSet<>();
        for (Course course : courses.findAllById(ids)) {
            if (course.getDeletedAt() == null) {
                live.add(course.getId());
            }
        }
        for (Long id : ids) {
            if (!live.contains(id)) {
                throw ApiException.badRequest("课程不存在或已被删除: " + id);
            }
        }
    }

    private Set<String> periodSlots(Long semesterId) {
        Set<String> slots = new LinkedHashSet<>();
        if (semesterId == null) {
            return slots;
        }
        for (Period period : periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)) {
            slots.add(period.getDayOfWeek() + "-" + period.getPeriodNo());
        }
        return slots;
    }

    private void saveMembers(Long groupId, List<MemberSpec> specs) {
        if (specs.isEmpty()) {
            return;
        }
        List<RuleGroupMember> rows = new ArrayList<>(specs.size());
        for (MemberSpec spec : specs) {
            RuleGroupMember member = new RuleGroupMember();
            member.setGroupId(groupId);
            member.setMemberKey(spec.memberKey());
            member.setTeacherId(spec.teacherId());
            member.setCourseId(spec.courseId());
            member.setDayOfWeek(spec.dayOfWeek());
            member.setPeriodNo(spec.periodNo());
            rows.add(member);
        }
        members.saveAll(rows);
        members.flush();
    }

    // ---------------------------------------------------------------- mapping

    private List<RuleGroupDto> toDtos(List<RuleGroup> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(RuleGroup::getId).toList();
        Map<Long, List<RuleGroupMember>> byGroup = new LinkedHashMap<>();
        Set<Long> teacherIds = new LinkedHashSet<>();
        Set<Long> courseIds = new LinkedHashSet<>();
        for (RuleGroupMember member : members.findByGroupIdInOrderByIdAsc(ids)) {
            byGroup.computeIfAbsent(member.getGroupId(), key -> new ArrayList<>()).add(member);
            if (member.getTeacherId() != null) {
                teacherIds.add(member.getTeacherId());
            }
            if (member.getCourseId() != null) {
                courseIds.add(member.getCourseId());
            }
        }
        Map<Long, String> teacherNames = new HashMap<>();
        if (!teacherIds.isEmpty()) {
            teachers.findAllById(teacherIds)
                    .forEach(teacher -> teacherNames.put(teacher.getId(), teacher.getName()));
        }
        Map<Long, String> courseNames = new HashMap<>();
        if (!courseIds.isEmpty()) {
            courses.findAllById(courseIds)
                    .forEach(course -> courseNames.put(course.getId(), course.getName()));
        }
        List<RuleGroupDto> dtos = new ArrayList<>(rows.size());
        for (RuleGroup group : rows) {
            List<RuleGroupMemberDto> memberDtos = new ArrayList<>();
            for (RuleGroupMember member : byGroup.getOrDefault(group.getId(), List.of())) {
                memberDtos.add(toMemberDto(group.getGroupType(), member, teacherNames, courseNames));
            }
            dtos.add(new RuleGroupDto(
                    group.getId(), group.getGroupType().name(), group.getName(),
                    group.getSemesterId(), memberDtos, group.isAutoFilled()));
        }
        return dtos;
    }

    private RuleGroupMemberDto toMemberDto(
            RuleGroupType type, RuleGroupMember member,
            Map<Long, String> teacherNames, Map<Long, String> courseNames) {
        return switch (type) {
            case TEACHER -> new RuleGroupMemberDto(
                    member.getId(), type.name(), member.getTeacherId(),
                    nameOf(teacherNames, member.getTeacherId()), null, null);
            case COURSE -> new RuleGroupMemberDto(
                    member.getId(), type.name(), member.getCourseId(),
                    nameOf(courseNames, member.getCourseId()), null, null);
            case TIME -> new RuleGroupMemberDto(
                    member.getId(), type.name(), null, null,
                    member.getDayOfWeek(), member.getPeriodNo());
        };
    }

    private String nameOf(Map<Long, String> names, Long id) {
        if (id == null) {
            return null;
        }
        return names.getOrDefault(id, "#" + id);
    }

    // ---------------------------------------------------------------- helpers

    private RuleGroup requireLive(Long id) {
        return groups.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> ApiException.notFound("分组不存在或已被删除: " + id));
    }

    private RuleGroupType parseType(String type) {
        if (!StringUtils.hasText(type)) {
            throw ApiException.badRequest("分组类型不能为空（TEACHER、COURSE 或 TIME）");
        }
        try {
            return RuleGroupType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("未知分组类型: " + type);
        }
    }

    private String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            throw ApiException.badRequest("分组名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 64) {
            throw ApiException.badRequest("分组名称不能超过 64 个字符");
        }
        return trimmed;
    }

    private Long normalizeSemester(RuleGroupType type, Long semesterId) {
        if (type == RuleGroupType.TIME) {
            if (semesterId == null) {
                throw ApiException.badRequest("时间分组必须指定学期（semesterId）");
            }
            return semesters.require(semesterId).getId();
        }
        if (semesterId != null) {
            throw ApiException.badRequest("教师组和课程组是全局分组，不能指定学期");
        }
        return null;
    }

    private static String liveKey(RuleGroupType type, String name, Long semesterId) {
        return switch (type) {
            case TEACHER -> "TEACHER:" + name;
            case COURSE -> "COURSE:" + name;
            case TIME -> "TIME:" + semesterId + ":" + name;
        };
    }

    private ApiException duplicateName(String name) {
        return ApiException.conflict("同类型下已有同名分组: " + name);
    }

    private static String parseMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return MODE_DELETE;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (MODE_DELETE.equals(normalized) || MODE_WITH_RULES.equals(normalized)) {
            return normalized;
        }
        throw ApiException.badRequest("不支持的删除方式: " + mode);
    }

    private static String kindLabel(RuleGroupType type) {
        return switch (type) {
            case TEACHER -> "教师组";
            case COURSE -> "课程组";
            case TIME -> "时间组";
        };
    }

    private String impactMessage(RuleGroup group, List<RuleGroupReferenceDto> rules) {
        if (rules.isEmpty()) {
            return "该分组没有被规则引用";
        }
        return "有 " + rules.size() + " 条规则正在使用「" + group.getName()
                + "」，删除后这些规则会失效";
    }

    private boolean referencesGroup(Map<String, Object> params, Long groupId) {
        return groupId.equals(ConstraintSettings.longValue(params.get(RuleGroupExpander.TEACHER_GROUP)))
                || groupId.equals(ConstraintSettings.longValue(params.get(RuleGroupExpander.COURSE_GROUP)))
                || groupId.equals(ConstraintSettings.longValue(params.get(RuleGroupExpander.TIME_GROUP)));
    }

    private Map<String, Object> paramsOf(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String typeName(String type) {
        try {
            return ConstraintType.valueOf(type).getDisplayName();
        } catch (IllegalArgumentException ex) {
            return type;
        }
    }

    /** Mutable aggregation of the entry rows that form one rule card. */
    private static final class ReferenceAccumulator {

        private final Long semesterId;
        private final String type;
        private final String groupKey;
        private final List<Long> entryIds = new ArrayList<>();

        private ReferenceAccumulator(Long semesterId, String type, String groupKey) {
            this.semesterId = semesterId;
            this.type = type;
            this.groupKey = groupKey;
        }

        private RuleGroupReferenceDto toDto(RuleGroupService service) {
            Long configId = service.configs
                    .findBySemesterIdAndType(semesterId, type)
                    .filter(config -> config.getDeletedAt() == null)
                    .map(config -> config.getId())
                    .orElse(null);
            String semesterName;
            try {
                semesterName = service.semesters.require(semesterId).getName();
            } catch (ApiException ex) {
                semesterName = "未知学期";
            }
            return new RuleGroupReferenceDto(
                    configId, semesterId, semesterName, type, service.typeName(type),
                    groupKey, List.copyOf(entryIds));
        }
    }
}
