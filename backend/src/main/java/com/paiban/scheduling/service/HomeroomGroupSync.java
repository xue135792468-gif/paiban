package com.paiban.scheduling.service;

import com.paiban.common.event.HomeroomChangedEvent;
import com.paiban.common.event.SchoolDataWriteLock;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.service.SemesterService;
import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.scheduling.domain.RuleGroup;
import com.paiban.scheduling.domain.RuleGroupMember;
import com.paiban.scheduling.domain.RuleGroupMemberRepository;
import com.paiban.scheduling.domain.RuleGroupRepository;
import com.paiban.scheduling.domain.RuleGroupType;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class HomeroomGroupSync {

    private static final String HOMEROOM = "班主任";

    private final FoundationService foundation;
    private final SemesterService semesters;
    private final TeacherRepository teachers;
    private final RuleGroupRepository groups;
    private final RuleGroupMemberRepository members;
    private final SchoolDataWriteLock schoolDataLock;
    private final Clock clock;

    public HomeroomGroupSync(FoundationService foundation, SemesterService semesters, TeacherRepository teachers,
            RuleGroupRepository groups, RuleGroupMemberRepository members, SchoolDataWriteLock schoolDataLock,
            Clock clock) {
        this.foundation = foundation;
        this.semesters = semesters;
        this.teachers = teachers;
        this.groups = groups;
        this.members = members;
        this.schoolDataLock = schoolDataLock;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initialize() {
        schoolDataLock.acquire();
        sync();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onHomeroomChanged(HomeroomChangedEvent event) {
        sync();
    }

    private void sync() {
        Map<String, Set<Long>> desired = new LinkedHashMap<>();
        semesters.current().ifPresent(semester -> {
            Set<Long> liveTeachers = teachers.findAllByDeletedAtIsNullOrderByNameAsc().stream()
                    .map(Teacher::getId).collect(Collectors.toSet());
            for (ClassDto adminClass : foundation.listClasses(semester.id())) {
                if (adminClass.gradeName() == null || adminClass.headTeacherId() == null
                        || !liveTeachers.contains(adminClass.headTeacherId())) {
                    continue;
                }
                desired.computeIfAbsent(HOMEROOM, ignored -> new LinkedHashSet<>()).add(adminClass.headTeacherId());
                desired.computeIfAbsent(adminClass.gradeName() + HOMEROOM, ignored -> new LinkedHashSet<>())
                        .add(adminClass.headTeacherId());
            }
        });
        for (RuleGroup group : groups.findByGroupTypeAndDeletedAtIsNullOrderByNameAsc(RuleGroupType.TEACHER)) {
            Set<Long> teacherIds = desired.remove(group.getName());
            if (group.isAutoFilled()) {
                replaceMembers(group, teacherIds == null ? Set.of() : teacherIds);
            }
        }
        desired.forEach(this::create);
    }

    private void create(String name, Set<Long> teacherIds) {
        RuleGroup group = new RuleGroup();
        group.setGroupType(RuleGroupType.TEACHER);
        group.setName(name);
        group.setLiveKey("TEACHER:" + name);
        group.setAutoFilled(true);
        group.setCreatedAt(Instant.now(clock));
        group.setUpdatedAt(group.getCreatedAt());
        groups.save(group);
        replaceMembers(group, teacherIds);
    }

    private void replaceMembers(RuleGroup group, Set<Long> teacherIds) {
        Set<Long> current = members.findByGroupIdOrderByIdAsc(group.getId()).stream()
                .map(RuleGroupMember::getTeacherId).collect(Collectors.toSet());
        if (current.equals(teacherIds)) {
            return;
        }
        members.deleteByGroupId(group.getId());
        members.saveAll(teacherIds.stream().map(id -> {
            RuleGroupMember member = new RuleGroupMember();
            member.setGroupId(group.getId());
            member.setTeacherId(id);
            member.setMemberKey("T:" + id);
            return member;
        }).toList());
        group.setUpdatedAt(Instant.now(clock));
    }
}
