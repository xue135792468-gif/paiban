package com.paiban.integration.org;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.integration.domain.DingUser;
import com.paiban.integration.org.DingTalkSyncPlan.Category;
import com.paiban.integration.org.DingTalkSyncPlan.FieldChange;
import com.paiban.integration.org.DingTalkSyncPlan.Item;
import com.paiban.integration.org.DingTalkSyncPlan.TeacherCandidate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Classifies every remote user into CREATE / MERGE / SUSPECTED / SKIP.
 *
 * <p>Matching order is dingtalk_user_id -> employee number -> phone. A name match
 * alone never merges: it becomes SUSPECTED with the local candidates attached, and
 * only an explicit {@link DingTalkSyncDecisions.Action#MERGE} decision turns it into
 * a write. This is what keeps 47 Excel-imported teachers (no employee number, no
 * phone) from being duplicated by the first recursive sync.
 */
@Component
public class DingTalkSyncPlanner {

    private final DingTalkTeacherMatcher matcher;
    private final TeacherRepository teachers;
    private final DingTalkMergeRules rules;

    public DingTalkSyncPlanner(
            DingTalkTeacherMatcher matcher, TeacherRepository teachers, DingTalkMergeRules rules) {
        this.matcher = matcher;
        this.teachers = teachers;
        this.rules = rules;
    }

    public DingTalkSyncPlan plan(DingTalkDirectory directory, DingTalkSyncDecisions decisions) {
        List<Item> items = new ArrayList<>();
        Set<Long> claimedTeachers = new LinkedHashSet<>();
        Set<String> plannedEmployeeNumbers = new HashSet<>();
        List<DingUser> unmatched = new ArrayList<>();

        for (DingUser user : directory.users()) {
            if (isBlank(user.userId()) || isBlank(user.name())) {
                items.add(skip(user, "无有效字段", List.of()));
                continue;
            }
            if (isExcluded(user, decisions)) {
                items.add(skip(user, "已排除", List.of()));
                continue;
            }
            Optional<Teacher> matched = matcher.match(user);
            if (matched.isPresent()) {
                Teacher teacher = matched.get();
                claimedTeachers.add(teacher.getId());
                items.add(matchedItem(user, teacher));
            } else {
                unmatched.add(user);
            }
        }

        // Second pass: same-name candidates are only considered after every
        // key-matched teacher is claimed, so two remote users cannot propose the
        // same local teacher.
        for (DingUser user : unmatched) {
            List<Teacher> candidates = candidatesFor(user, claimedTeachers);
            if (candidates.isEmpty()) {
                items.add(createItem(user, plannedEmployeeNumbers));
                continue;
            }
            DingTalkSyncDecisions.SuspectedDecision decision = decisions.suspectedDecisions().get(user.userId());
            if (decision == null) {
                items.add(suspectedItem(user, candidates));
                continue;
            }
            switch (decision.action()) {
                case MERGE -> {
                    Teacher target = candidates.stream()
                            .filter(candidate -> candidate.getId().equals(decision.teacherId()))
                            .findFirst()
                            .orElseThrow(() -> ApiException.badRequest(
                                    "合并目标教师 " + decision.teacherId() + " 不是「" + user.name() + "」的同名候选"));
                    claimedTeachers.add(target.getId());
                    items.add(confirmedMergeItem(user, target, candidates));
                }
                case CREATE -> items.add(createItem(user, plannedEmployeeNumbers));
                case SKIP -> items.add(skip(user, "疑似同名，本次跳过", List.of()));
            }
        }
        return new DingTalkSyncPlan(items, directory.skipped(), directory.failedDepartments());
    }

    private Item matchedItem(DingUser user, Teacher teacher) {
        List<FieldChange> fills = rules.fills(teacher, user);
        List<FieldChange> conflicts = rules.conflicts(teacher, user);
        if (!fills.isEmpty()) {
            return new Item(
                    user, Category.MERGE, "补齐 " + fills.size() + " 项",
                    teacher.getId(), teacher.getName(), fills, conflicts, List.of());
        }
        if (!conflicts.isEmpty()) {
            return new Item(
                    user, Category.SKIP, "本地字段与钉钉不一致，未覆盖",
                    teacher.getId(), teacher.getName(), List.of(), conflicts, List.of());
        }
        return new Item(
                user, Category.SKIP, "本地信息已一致",
                teacher.getId(), teacher.getName(), List.of(), List.of(), List.of());
    }

    private Item confirmedMergeItem(DingUser user, Teacher teacher, List<Teacher> candidates) {
        List<FieldChange> fills = rules.fills(teacher, user);
        List<FieldChange> conflicts = rules.conflicts(teacher, user);
        return new Item(
                user, Category.MERGE, "疑似同名，已确认合并到「" + teacher.getName() + "」",
                teacher.getId(), teacher.getName(), fills, conflicts, candidates.stream().map(this::candidate).toList());
    }

    private Item suspectedItem(DingUser user, List<Teacher> candidates) {
        return new Item(
                user, Category.SUSPECTED, "姓名相同但工号/手机未命中，请确认是否合并",
                null, null, List.of(), List.of(), candidates.stream().map(this::candidate).toList());
    }

    private Item createItem(DingUser user, Set<String> plannedEmployeeNumbers) {
        if (notBlank(user.jobNumber()) && !plannedEmployeeNumbers.add(user.jobNumber())) {
            return skip(user, "工号与本次另一用户重复，未导入", List.of());
        }
        return new Item(user, Category.CREATE, "新增教师", null, null, List.of(), List.of(), List.of());
    }

    private Item skip(DingUser user, String reason, List<FieldChange> conflicts) {
        return new Item(user, Category.SKIP, reason, null, null, List.of(), conflicts, List.of());
    }

    private List<Teacher> candidatesFor(DingUser user, Set<Long> claimedTeachers) {
        return teachers.findByName(user.name()).stream()
                .filter(teacher -> !claimedTeachers.contains(teacher.getId()))
                .toList();
    }

    private TeacherCandidate candidate(Teacher teacher) {
        return new TeacherCandidate(
                teacher.getId(), teacher.getName(), teacher.getEmployeeNo(), teacher.getPhone());
    }

    private boolean isExcluded(DingUser user, DingTalkSyncDecisions decisions) {
        if (decisions.excludedUserIds().contains(user.userId())) {
            return true;
        }
        return user.departmentNames().stream().anyMatch(decisions.excludedDepartments()::contains);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
