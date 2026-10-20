package com.paiban.integration.org;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.integration.dingtalk.DingTalkClient;
import com.paiban.integration.domain.DingUser;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Pulls the staff directory from DingTalk; supports dingtalk-wins and local-wins strategies. */
@Component
public class DingTalkOrgDataProvider implements OrgDataProvider {

    private final SettingsService settings;
    private final DingTalkClient client;
    private final TeacherRepository teachers;
    private final FoundationService foundation;

    public DingTalkOrgDataProvider(
            SettingsService settings,
            DingTalkClient client,
            TeacherRepository teachers,
            FoundationService foundation) {
        this.settings = settings;
        this.client = client;
        this.teachers = teachers;
        this.foundation = foundation;
    }

    @Override
    public String code() {
        return "dingtalk";
    }

    @Override
    @Transactional
    public OrgSyncResult sync() {
        if (!settings.dingtalkEnabled()) {
            return new OrgSyncResult(0, 0, 0, "钉钉集成未启用");
        }
        List<DingUser> remote;
        try {
            remote = client.listUsers();
        } catch (RuntimeException ex) {
            return new OrgSyncResult(0, 0, 0, "拉取钉钉通讯录失败: " + ex.getMessage());
        }
        boolean dingtalkWins = "dingtalk".equalsIgnoreCase(settings.dingtalkSyncStrategy());
        int created = 0;
        int updated = 0;
        Set<String> remoteIds = new HashSet<>();
        for (DingUser user : remote) {
            remoteIds.add(user.userId());
            Teacher teacher = teachers.findByDingtalkUserId(user.userId())
                    .or(() -> user.jobNumber() == null
                            ? java.util.Optional.empty()
                            : teachers.findByEmployeeNo(user.jobNumber()))
                    .orElse(null);
            if (teacher == null) {
                Teacher createdTeacher = new Teacher();
                createdTeacher.setName(user.name());
                createdTeacher.setEmployeeNo(user.jobNumber());
                createdTeacher.setPhone(user.mobile());
                createdTeacher.setEmail(user.email());
                createdTeacher.setDingtalkUserId(user.userId());
                createdTeacher.setActive(true);
                createdTeacher.setCreatedAt(Instant.now());
                teachers.save(createdTeacher);
                created++;
            } else {
                if (dingtalkWins) {
                    teacher.setName(user.name());
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
                        teacher.setEmployeeNo(user.jobNumber());
                    }
                }
                teacher.setDingtalkUserId(user.userId());
                teacher.setActive(true);
                updated++;
            }
        }
        int deactivated = 0;
        if (dingtalkWins) {
            for (Teacher teacher : teachers.findAll()) {
                if (teacher.getDingtalkUserId() != null
                        && !remoteIds.contains(teacher.getDingtalkUserId())
                        && teacher.isActive()) {
                    teacher.setActive(false);
                    deactivated++;
                }
            }
        }
        String message = "同步完成：新增 " + created + "，更新 " + updated + "，停用 " + deactivated;
        foundation.audit("system", "DINGTALK_SYNC", "teacher", message, created + updated, 0);
        return new OrgSyncResult(created, updated, deactivated, message);
    }

    @Transactional
    public void applyEvent(String type, DingUser user) {
        if (user == null || user.userId() == null) {
            return;
        }
        Teacher teacher = teachers.findByDingtalkUserId(user.userId()).orElse(null);
        if ("user_leave".equalsIgnoreCase(type)) {
            if (teacher != null) {
                teacher.setActive(false);
            }
            return;
        }
        if (teacher == null) {
            teacher = new Teacher();
            teacher.setCreatedAt(Instant.now());
            teacher.setDingtalkUserId(user.userId());
        }
        teacher.setName(user.name());
        teacher.setEmployeeNo(user.jobNumber());
        teacher.setPhone(user.mobile());
        teacher.setEmail(user.email());
        teacher.setActive(true);
        teachers.save(teacher);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
