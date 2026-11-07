package com.paiban.scheduling.service;

import com.paiban.foundation.domain.Course;
import com.paiban.foundation.domain.CourseRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.scheduling.domain.RuleGroup;
import com.paiban.scheduling.domain.RuleGroupMember;
import com.paiban.scheduling.domain.RuleGroupMemberRepository;
import com.paiban.scheduling.domain.RuleGroupRepository;
import com.paiban.scheduling.domain.RuleGroupType;
import com.paiban.scheduling.solver.ConstraintSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Expands user-group references ({@code teacherGroupId} / {@code courseGroupId} /
 * {@code timeGroupId}) into concrete parameter rows (ticket 04, contract §2.2).
 *
 * <p>This is the only place a group is turned into members. The stored
 * {@code constraint_entry} row keeps the group id; the solver, draft check,
 * substitution planner and CLI only ever see the expanded snapshot built by
 * {@link ConstraintConfigService#settings(Long)}. A group edit therefore changes every
 * referencing rule immediately, and the settings fingerprint changes with it (which is
 * why a stale swap plan token is refused).
 *
 * <p>An unknown, deleted or empty group expands to zero rows: the rule silently stops
 * applying, with the API's {@code invalidReferences} naming the reason.
 */
@Component
public class RuleGroupExpander {

    static final String TEACHER_GROUP = "teacherGroupId";
    static final String COURSE_GROUP = "courseGroupId";
    static final String TIME_GROUP = "timeGroupId";

    private static final List<String> GROUP_KEYS =
            List.of(TEACHER_GROUP, COURSE_GROUP, TIME_GROUP);

    private final RuleGroupRepository groups;
    private final RuleGroupMemberRepository members;
    private final TeacherRepository teachers;
    private final CourseRepository courses;
    private final PeriodRepository periods;

    public RuleGroupExpander(
            RuleGroupRepository groups,
            RuleGroupMemberRepository members,
            TeacherRepository teachers,
            CourseRepository courses,
            PeriodRepository periods) {
        this.groups = groups;
        this.members = members;
        this.teachers = teachers;
        this.courses = courses;
        this.periods = periods;
    }

    /**
     * Batch form used by the snapshot builders: one pass loads every group and member
     * the rows name, so a configuration with many group rows costs a fixed number of
     * queries. Rows without any group reference come back unchanged.
     */
    public List<List<Map<String, Object>>> expandAll(
            Long semesterId, List<Map<String, Object>> paramsRows) {
        if (paramsRows == null || paramsRows.isEmpty()) {
            return List.of();
        }
        List<List<GroupRef>> refsByRow = new ArrayList<>(paramsRows.size());
        Set<Long> referencedIds = new LinkedHashSet<>();
        boolean anyReference = false;
        for (Map<String, Object> params : paramsRows) {
            List<GroupRef> refs = groupRefsOf(params);
            refsByRow.add(refs);
            if (!refs.isEmpty()) {
                anyReference = true;
                for (GroupRef ref : refs) {
                    Long id = ConstraintSettings.longValue(params.get(ref.key()));
                    if (id != null) {
                        referencedIds.add(id);
                    }
                }
            }
        }
        if (!anyReference) {
            List<List<Map<String, Object>>> unchanged = new ArrayList<>(paramsRows.size());
            paramsRows.forEach(row -> unchanged.add(List.of(row)));
            return unchanged;
        }

        Map<Long, RuleGroup> loaded = new HashMap<>();
        if (!referencedIds.isEmpty()) {
            groups.findByIdIn(referencedIds).stream()
                    .filter(group -> group.getDeletedAt() == null)
                    .forEach(group -> loaded.put(group.getId(), group));
        }
        Set<Long> usableIds = new LinkedHashSet<>();
        for (Map.Entry<Long, RuleGroup> entry : loaded.entrySet()) {
            if (entry.getValue().getGroupType() != null
                    && (!RuleGroupType.TIME.equals(entry.getValue().getGroupType())
                            || (semesterId != null
                                    && semesterId.equals(entry.getValue().getSemesterId())))) {
                usableIds.add(entry.getKey());
            }
        }
        Map<Long, List<Map<String, Object>>> valuesByGroup =
                expandMembers(semesterId, loaded, usableIds);

        List<List<Map<String, Object>>> result = new ArrayList<>(paramsRows.size());
        for (int index = 0; index < paramsRows.size(); index++) {
            List<GroupRef> refs = refsByRow.get(index);
            Map<String, Object> params = paramsRows.get(index);
            if (refs.isEmpty()) {
                result.add(List.of(params));
                continue;
            }
            Map<String, Object> base = new LinkedHashMap<>(params);
            List<Map<String, Object>> accumulated = new ArrayList<>();
            accumulated.add(base);
            for (GroupRef ref : refs) {
                Long groupId = ConstraintSettings.longValue(params.get(ref.key()));
                RuleGroup group = groupId == null ? null : loaded.get(groupId);
                List<Map<String, Object>> values =
                        group == null || group.getGroupType() != ref.type()
                                ? List.of()
                                : valuesByGroup.getOrDefault(groupId, List.of());
                if (values.isEmpty()) {
                    accumulated = List.of();
                    break;
                }
                List<Map<String, Object>> next =
                        new ArrayList<>(accumulated.size() * values.size());
                for (Map<String, Object> partial : accumulated) {
                    for (Map<String, Object> value : values) {
                        Map<String, Object> row = new LinkedHashMap<>(partial);
                        row.putAll(value);
                        next.add(row);
                    }
                }
                accumulated = next;
            }
            for (Map<String, Object> row : accumulated) {
                GROUP_KEYS.forEach(row::remove);
                // Internal-only marker: the row came from a group, so threshold
                // resolution can prefer a rule naming one object over a group rule.
                row.put(ConstraintSettings.GROUP_EXPANDED, true);
            }
            result.add(accumulated);
        }
        return result;
    }

    /** Concrete member values of every usable group, in stored member order. */
    private Map<Long, List<Map<String, Object>>> expandMembers(
            Long semesterId, Map<Long, RuleGroup> loaded, Set<Long> usableIds) {
        Map<Long, List<Map<String, Object>>> values = new LinkedHashMap<>();
        if (usableIds.isEmpty()) {
            return values;
        }
        List<RuleGroupMember> rows = members.findByGroupIdInOrderByIdAsc(usableIds);
        if (rows.isEmpty()) {
            return values;
        }
        Set<Long> teacherIds = new LinkedHashSet<>();
        Set<Long> courseIds = new LinkedHashSet<>();
        for (RuleGroupMember row : rows) {
            if (row.getTeacherId() != null) {
                teacherIds.add(row.getTeacherId());
            }
            if (row.getCourseId() != null) {
                courseIds.add(row.getCourseId());
            }
        }
        Map<Long, Teacher> teacherById = new HashMap<>();
        if (!teacherIds.isEmpty()) {
            teachers.findAllById(teacherIds).forEach(row -> teacherById.put(row.getId(), row));
        }
        Map<Long, Course> courseById = new HashMap<>();
        if (!courseIds.isEmpty()) {
            courses.findAllById(courseIds).forEach(row -> courseById.put(row.getId(), row));
        }
        Set<String> slots = semesterId == null
                ? Set.of()
                : periodSlots(semesterId);

        for (RuleGroupMember row : rows) {
            RuleGroup group = loaded.get(row.getGroupId());
            if (group == null) {
                continue;
            }
            Map<String, Object> value = switch (group.getGroupType()) {
                case TEACHER -> teacherValue(row.getTeacherId(), teacherById);
                case COURSE -> courseValue(row.getCourseId(), courseById);
                case TIME -> slotValue(row, slots);
            };
            if (value != null) {
                values.computeIfAbsent(row.getGroupId(), key -> new ArrayList<>()).add(value);
            }
        }
        return values;
    }

    private Map<String, Object> teacherValue(Long teacherId, Map<Long, Teacher> byId) {
        Teacher teacher = teacherId == null ? null : byId.get(teacherId);
        if (teacher == null || teacher.getDeletedAt() != null || !teacher.isActive()) {
            return null;
        }
        return Map.of("teacherId", teacher.getId());
    }

    private Map<String, Object> courseValue(Long courseId, Map<Long, Course> byId) {
        Course course = courseId == null ? null : byId.get(courseId);
        if (course == null || course.getDeletedAt() != null || !course.isActive()) {
            return null;
        }
        return Map.of("courseId", course.getId());
    }

    private Map<String, Object> slotValue(RuleGroupMember row, Set<String> slots) {
        if (row.getDayOfWeek() == null || row.getPeriodNo() == null
                || !slots.contains(slotKey(row.getDayOfWeek(), row.getPeriodNo()))) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("dayOfWeek", row.getDayOfWeek());
        value.put("periodNo", row.getPeriodNo());
        return value;
    }

    private Set<String> periodSlots(Long semesterId) {
        Set<String> slots = new LinkedHashSet<>();
        for (Period period : periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId)) {
            slots.add(slotKey(period.getDayOfWeek(), period.getPeriodNo()));
        }
        return slots;
    }

    private static String slotKey(Integer dayOfWeek, Integer periodNo) {
        return dayOfWeek + "-" + periodNo;
    }

    private List<GroupRef> groupRefsOf(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        List<GroupRef> refs = new ArrayList<>(2);
        if (params.get(TEACHER_GROUP) != null) {
            refs.add(new GroupRef(TEACHER_GROUP, RuleGroupType.TEACHER));
        }
        if (params.get(COURSE_GROUP) != null) {
            refs.add(new GroupRef(COURSE_GROUP, RuleGroupType.COURSE));
        }
        if (params.get(TIME_GROUP) != null) {
            refs.add(new GroupRef(TIME_GROUP, RuleGroupType.TIME));
        }
        return refs;
    }

    private record GroupRef(String key, RuleGroupType type) {}
}
