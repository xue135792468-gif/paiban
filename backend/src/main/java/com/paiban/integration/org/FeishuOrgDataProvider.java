package com.paiban.integration.org;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.integration.feishu.FeishuClient;
import com.paiban.integration.feishu.FeishuUser;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pulls the staff directory from Feishu; supports feishu-wins and local-wins
 * strategies. Matching order is feishuUserId -> feishuOpenId -> employeeNo.
 */
@Component
public class FeishuOrgDataProvider implements OrgDataProvider {

    private final SettingsService settings;
    private final FeishuClient client;
    private final TeacherRepository teachers;
    private final FoundationService foundation;

    public FeishuOrgDataProvider(
            SettingsService settings,
            FeishuClient client,
            TeacherRepository teachers,
            FoundationService foundation) {
        this.settings = settings;
        this.client = client;
        this.teachers = teachers;
        this.foundation = foundation;
    }

    @Override
    public String code() {
        return "feishu";
    }

    @Override
    @Transactional
    public OrgSyncResult sync() {
        if (!settings.feishuEnabled()) {
            return new OrgSyncResult(0, 0, 0, "飞书集成未启用");
        }
        List<FeishuUser> remote;
        try {
            // Departments are only used for validation/logging; users are the source of truth.
            client.listDepartments();
            remote = client.listUsers();
        } catch (RuntimeException ex) {
            return new OrgSyncResult(0, 0, 0, "拉取飞书通讯录失败: " + ex.getMessage());
        }
        boolean feishuWins = "feishu".equalsIgnoreCase(settings.feishuSyncStrategy());
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
            Teacher teacher = findTeacher(user).orElse(null);
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
                    teacher.setPhone(user.mobile());
                    teacher.setEmail(user.email());
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
        return new OrgSyncResult(created, updated, deactivated, message);
    }

    /** Applies a single contact event (created/updated/deleted) from the Stream callback. */
    @Transactional
    public void applyEvent(String type, FeishuUser user) {
        if (user == null || (user.userId() == null && user.openId() == null)) {
            return;
        }
        Teacher teacher = findTeacher(user).orElse(null);
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
        if (!isBlank(user.name())) {
            teacher.setName(user.name());
        }
        if (user.userId() != null) {
            teacher.setFeishuUserId(user.userId());
        }
        if (user.openId() != null) {
            teacher.setFeishuOpenId(user.openId());
        }
        teacher.setEmployeeNo(user.employeeNo());
        teacher.setPhone(user.mobile());
        teacher.setEmail(user.email());
        teacher.setActive(true);
        teachers.save(teacher);
    }

    private Optional<Teacher> findTeacher(FeishuUser user) {
        if (user.userId() != null) {
            Optional<Teacher> byUserId = teachers.findByFeishuUserId(user.userId());
            if (byUserId.isPresent()) {
                return byUserId;
            }
        }
        if (user.openId() != null) {
            Optional<Teacher> byOpenId = teachers.findByFeishuOpenId(user.openId());
            if (byOpenId.isPresent()) {
                return byOpenId;
            }
        }
        if (user.employeeNo() != null) {
            return teachers.findByEmployeeNo(user.employeeNo());
        }
        return Optional.empty();
    }

    private boolean isDeleted(String type) {
        return type != null
                && (type.endsWith("user.deleted_v3") || type.endsWith("user_deleted_v3"));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
