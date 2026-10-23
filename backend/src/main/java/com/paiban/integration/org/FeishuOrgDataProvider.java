package com.paiban.integration.org;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.integration.feishu.FeishuClient;
import com.paiban.integration.feishu.FeishuTeacherResolver;
import com.paiban.integration.feishu.FeishuUser;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pulls the staff directory from Feishu; supports feishu-wins and local-wins
 * strategies. Matching order is feishuUserId -> feishuOpenId -> employeeNo.
 *
 * <p>The directory fetch is a network call and therefore stays outside any
 * transaction; only the teacher writes and the audit row run in one.
 */
@Component
public class FeishuOrgDataProvider implements OrgDataProvider {

    private final SettingsService settings;
    private final FeishuClient client;
    private final TeacherRepository teachers;
    private final FoundationService foundation;
    private final FeishuTeacherResolver teacherResolver;
    private final TransactionTemplate transactionTemplate;

    public FeishuOrgDataProvider(
            SettingsService settings,
            FeishuClient client,
            TeacherRepository teachers,
            FoundationService foundation,
            FeishuTeacherResolver teacherResolver,
            PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.client = client;
        this.teachers = teachers;
        this.foundation = foundation;
        this.teacherResolver = teacherResolver;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public String code() {
        return "feishu";
    }

    @Override
    public OrgSyncResult sync() {
        if (!settings.feishuEnabled()) {
            return OrgSyncResult.failed("飞书集成未启用");
        }
        List<FeishuUser> remote;
        try {
            // Departments are only used for validation/logging; users are the source of truth.
            client.listDepartments();
            remote = client.listUsers();
        } catch (RuntimeException ex) {
            String message = "拉取飞书通讯录失败: " + ex.getMessage();
            // Never let a failed fetch look like an empty directory: that would deactivate everyone.
            transactionTemplate.executeWithoutResult(status ->
                    foundation.audit("system", "FEISHU_SYNC", "teacher", message, 0, 0));
            return OrgSyncResult.failed(message);
        }
        boolean feishuWins = SettingsService.PLATFORM_FEISHU.equalsIgnoreCase(settings.feishuSyncStrategy());
        OrgSyncResult result = transactionTemplate.execute(status -> applyDirectory(remote, feishuWins));
        return result == null ? OrgSyncResult.failed("同步未执行") : result;
    }

    /** Applies a fetched directory inside the caller's transaction. */
    private OrgSyncResult applyDirectory(List<FeishuUser> remote, boolean feishuWins) {
        int created = 0;
        int updated = 0;
        Set<String> remoteUserIds = new HashSet<>();
        Set<String> remoteOpenIds = new HashSet<>();
        for (FeishuUser user : remote) {
            if (user.userId() != null) {
                remoteUserIds.add(user.userId());
            }
            if (user.openId() != null) {
                remoteOpenIds.add(user.openId());
            }
            Teacher teacher = teacherResolver.resolveForSync(user).orElse(null);
            if (teacher == null) {
                Teacher createdTeacher = new Teacher();
                createdTeacher.setName(user.name());
                createdTeacher.setEmployeeNo(user.employeeNo());
                createdTeacher.setPhone(user.mobile());
                createdTeacher.setEmail(user.email());
                createdTeacher.setFeishuUserId(user.userId());
                createdTeacher.setFeishuOpenId(user.openId());
                createdTeacher.setActive(true);
                createdTeacher.setCreatedAt(Instant.now());
                teachers.save(createdTeacher);
                created++;
            } else {
                if (feishuWins) {
                    if (!isBlank(user.name())) {
                        teacher.setName(user.name());
                    }
                    if (!isBlank(user.mobile())) {
                        teacher.setPhone(user.mobile());
                    }
                    if (!isBlank(user.email())) {
                        teacher.setEmail(user.email());
                    }
                } else {
                    if (isBlank(teacher.getPhone())) {
                        teacher.setPhone(user.mobile());
                    }
                    if (isBlank(teacher.getEmail())) {
                        teacher.setEmail(user.email());
                    }
                    if (isBlank(teacher.getEmployeeNo())) {
                        teacher.setEmployeeNo(user.employeeNo());
                    }
                }
                if (user.userId() != null) {
                    teacher.setFeishuUserId(user.userId());
                }
                if (user.openId() != null) {
                    teacher.setFeishuOpenId(user.openId());
                }
                teacher.setActive(true);
                updated++;
            }
        }
        int deactivated = 0;
        if (feishuWins) {
            for (Teacher teacher : teachers.findAll()) {
                boolean bound = teacher.getFeishuUserId() != null || teacher.getFeishuOpenId() != null;
                boolean present = (teacher.getFeishuUserId() != null
                                && remoteUserIds.contains(teacher.getFeishuUserId()))
                        || (teacher.getFeishuOpenId() != null
                                && remoteOpenIds.contains(teacher.getFeishuOpenId()));
                if (bound && !present && teacher.isActive()) {
                    teacher.setActive(false);
                    deactivated++;
                }
            }
        }
        String message = "同步完成：新增 " + created + "，更新 " + updated + "，停用 " + deactivated;
        foundation.audit("system", "FEISHU_SYNC", "teacher", message, created + updated, 0);
        return OrgSyncResult.ok(created, updated, deactivated, message);
    }

    /**
     * Applies a single contact event (created/updated/deleted) from the Stream callback.
     * Partial payloads must not wipe locally maintained fields.
     */
    @Transactional
    public void applyEvent(String type, FeishuUser user) {
        if (user == null || (user.userId() == null && user.openId() == null)) {
            return;
        }
        Teacher teacher = teacherResolver.resolveForSync(user).orElse(null);
        if (isDeleted(type)) {
            if (teacher != null) {
                teacher.setActive(false);
            }
            return;
        }
        if (teacher == null) {
            if (isBlank(user.name())) {
                return;
            }
            teacher = new Teacher();
            teacher.setCreatedAt(Instant.now());
        }
        boolean feishuWins = SettingsService.PLATFORM_FEISHU.equalsIgnoreCase(settings.feishuSyncStrategy());
        applyFields(teacher, user, feishuWins);
        if (user.userId() != null) {
            teacher.setFeishuUserId(user.userId());
        }
        if (user.openId() != null) {
            teacher.setFeishuOpenId(user.openId());
        }
        teacher.setActive(true);
        teachers.save(teacher);
    }

    private void applyFields(Teacher teacher, FeishuUser user, boolean feishuWins) {
        if (feishuWins) {
            setIfPresent(user.name(), teacher::setName);
            setIfPresent(user.employeeNo(), teacher::setEmployeeNo);
            setIfPresent(user.mobile(), teacher::setPhone);
            setIfPresent(user.email(), teacher::setEmail);
            return;
        }
        if (isBlank(teacher.getName())) {
            setIfPresent(user.name(), teacher::setName);
        }
        if (isBlank(teacher.getEmployeeNo())) {
            setIfPresent(user.employeeNo(), teacher::setEmployeeNo);
        }
        if (isBlank(teacher.getPhone())) {
            setIfPresent(user.mobile(), teacher::setPhone);
        }
        if (isBlank(teacher.getEmail())) {
            setIfPresent(user.email(), teacher::setEmail);
        }
    }

    private void setIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (!isBlank(value)) {
            setter.accept(value);
        }
    }

    private boolean isDeleted(String type) {
        return type != null
                && (type.endsWith("user.deleted_v3") || type.endsWith("user_deleted_v3"));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
