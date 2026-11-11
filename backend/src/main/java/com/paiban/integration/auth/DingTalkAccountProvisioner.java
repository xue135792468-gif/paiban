package com.paiban.integration.auth;

import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AccountAuditAction;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.LoginType;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.identity.service.AccountAuditService;
import com.paiban.identity.service.AccountMutationGuard;
import com.paiban.identity.service.DingTalkBindingPolicy;
import com.paiban.identity.web.AccountAuditDtos.AccountSnapshot;
import com.paiban.integration.domain.DingUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Local identity resolution only: the caller finishes vendor I/O before entering this transaction. */
@Service
public class DingTalkAccountProvisioner {
    private final TeacherRepository teachers;
    private final AppUserRepository accounts;
    private final RoleRepository roles;
    private final AccountMutationGuard guard;
    private final DingTalkBindingPolicy bindings;
    private final AccountAuditService audits;
    private final EntityManager entityManager;

    public DingTalkAccountProvisioner(
            TeacherRepository teachers, AppUserRepository accounts, RoleRepository roles,
            AccountMutationGuard guard, DingTalkBindingPolicy bindings,
            AccountAuditService audits, EntityManager entityManager) {
        this.teachers = teachers;
        this.accounts = accounts;
        this.roles = roles;
        this.guard = guard;
        this.bindings = bindings;
        this.audits = audits;
        this.entityManager = entityManager;
    }

    @Transactional
    public String resolveAccount(DingUser identity) {
        if (identity == null || !StringUtils.hasText(identity.userId()) || !StringUtils.hasText(identity.name())) {
            throw DingTalkLoginFailure.DINGTALK_CALL_FAILED.exception();
        }
        guard.lock();
        Teacher teacher = resolveTeacher(identity);
        boolean needsBinding = !StringUtils.hasText(teacher.getDingtalkUserId());
        if (needsBinding && bindings.requiresExplicitFirstBinding(teacher)) {
            throw DingTalkLoginFailure.ADMIN_BINDING_REQUIRED.exception();
        }

        List<AppUser> linked = accounts.findAllByTeacherId(teacher.getId());
        linked.forEach(entityManager::refresh);
        AppUser account = linked.stream().filter(AppUser::isEnabled)
                .min(Comparator.comparing(AppUser::getId)).orElse(null);
        if (account == null && !linked.isEmpty()) {
            // Reject before touching the UID; disabled accounts are never shadowed or enabled here.
            throw DingTalkLoginFailure.ACCOUNT_DISABLED.exception();
        }
        if (account == null) {
            account = new AppUser();
            account.setUsername(unusedAccountName(teacher.getId()));
            account.setDisplayName(teacher.getName());
            account.setTeacherId(teacher.getId());
            account.setLoginType(LoginType.DINGTALK);
            account.setPasswordHash(null);
            account.getRoles().add(roles.findByCode("teacher")
                    .orElseThrow(() -> DingTalkLoginFailure.DINGTALK_CALL_FAILED.exception()));
            accounts.saveAndFlush(account);
            audits.record(AccountAuditService.SYSTEM_ACTOR, AccountAuditAction.DINGTALK_AUTO_PROVISION,
                    account.getId(), null, audits.snapshotOf(account));
        }
        if (needsBinding) {
            AccountSnapshot before = audits.snapshotOf(account);
            teacher.setDingtalkUserId(identity.userId());
            audits.record(AccountAuditService.SYSTEM_ACTOR, AccountAuditAction.DINGTALK_LINK,
                    account.getId(), before, audits.snapshotOf(account));
        }
        return account.getUsername();
    }

    private Teacher resolveTeacher(DingUser identity) {
        Set<Long> possibleIds = new TreeSet<>();
        teachers.findAllByDingtalkUserId(identity.userId()).forEach(teacher -> possibleIds.add(teacher.getId()));
        teachers.findByName(identity.name()).forEach(teacher -> possibleIds.add(teacher.getId()));
        Map<Long, Teacher> current = new LinkedHashMap<>();
        for (Long id : possibleIds) {
            Teacher teacher = entityManager.find(Teacher.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (teacher != null) {
                entityManager.refresh(teacher, LockModeType.PESSIMISTIC_WRITE);
                if (teacher.getDeletedAt() == null) {
                    current.put(id, teacher);
                }
            }
        }
        List<Teacher> byUid = current.values().stream()
                .filter(teacher -> identity.userId().equals(teacher.getDingtalkUserId())).toList();
        if (byUid.size() > 1) {
            throw DingTalkLoginFailure.AMBIGUOUS_IDENTITY.exception();
        }
        if (byUid.size() == 1) {
            return byUid.get(0);
        }
        List<Teacher> byName = current.values().stream()
                .filter(teacher -> identity.name().equals(teacher.getName()))
                .filter(teacher -> !StringUtils.hasText(teacher.getDingtalkUserId())).toList();
        if (byName.isEmpty()) {
            throw DingTalkLoginFailure.NOT_PROVISIONED.exception();
        }
        if (byName.size() > 1) {
            throw DingTalkLoginFailure.AMBIGUOUS_IDENTITY.exception();
        }
        return byName.get(0);
    }

    private String unusedAccountName(Long teacherId) {
        // Teacher IDs fit in 20 decimal characters; even the collision suffix stays below 64.
        String base = "dt_" + teacherId;
        String candidate = base;
        while (accounts.existsByUsername(candidate)) {
            candidate = base + "_" + UUID.randomUUID().toString().replace("-", "");
        }
        return candidate;
    }
}
