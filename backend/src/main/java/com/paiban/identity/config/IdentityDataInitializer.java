package com.paiban.identity.config;

import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.Permission;
import com.paiban.identity.domain.PermissionRepository;
import com.paiban.identity.domain.Permissions;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the permission catalogue, the three phase-1 roles and the bootstrap
 * administrator. Idempotent: safe to run on every startup.
 */
@Configuration
public class IdentityDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(IdentityDataInitializer.class);

    private static final Map<String, String> PERMISSION_NAMES = new LinkedHashMap<>();

    static {
        PERMISSION_NAMES.put(Permissions.USER_MANAGE, "用户与角色管理");
        PERMISSION_NAMES.put(Permissions.SCHOOL_MANAGE, "学校信息管理");
        PERMISSION_NAMES.put(Permissions.SEMESTER_MANAGE, "学期与作息表管理");
        PERMISSION_NAMES.put(Permissions.FOUNDATION_MANAGE, "基础数据管理");
        PERMISSION_NAMES.put(Permissions.FOUNDATION_VIEW, "基础数据查看");
        PERMISSION_NAMES.put(Permissions.ASSIGNMENT_MANAGE, "授课任务管理");
        PERMISSION_NAMES.put(Permissions.ASSIGNMENT_VIEW_OWN, "查看本人授课任务");
        PERMISSION_NAMES.put(Permissions.CONSTRAINT_MANAGE, "约束配置管理");
        PERMISSION_NAMES.put(Permissions.SCHEDULING_RUN, "提交排课任务");
        PERMISSION_NAMES.put(Permissions.SCHEDULING_VIEW, "查看排课任务");
        PERMISSION_NAMES.put(Permissions.TIMETABLE_EDIT, "编辑课表草稿");
        PERMISSION_NAMES.put(Permissions.TIMETABLE_PUBLISH, "发布课表");
        PERMISSION_NAMES.put(Permissions.TIMETABLE_VIEW, "查看全部课表");
        PERMISSION_NAMES.put(Permissions.TIMETABLE_VIEW_OWN, "查看个人课表");
        PERMISSION_NAMES.put(Permissions.TIMETABLE_VIEW_CLASS, "查看本班课表");
        PERMISSION_NAMES.put(Permissions.SUBSTITUTION_SUBMIT, "提交调代申请");
        PERMISSION_NAMES.put(Permissions.SUBSTITUTION_APPROVE, "审批调代申请");
        PERMISSION_NAMES.put(Permissions.SUBSTITUTION_VIEW, "查看全部调代记录");
        PERMISSION_NAMES.put(Permissions.SUBSTITUTION_VIEW_OWN, "查看本人调代记录");
        PERMISSION_NAMES.put(Permissions.SUBSTITUTION_VIEW_CLASS, "查看本班调代记录");
        PERMISSION_NAMES.put(Permissions.NOTIFICATION_VIEW, "查看通知");
        PERMISSION_NAMES.put(Permissions.SETTINGS_MANAGE, "设置中心");
        PERMISSION_NAMES.put(Permissions.INTEGRATION_MANAGE, "集成配置");
        PERMISSION_NAMES.put(Permissions.ONBOARDING_MANAGE, "初始化向导");
        PERMISSION_NAMES.put(Permissions.EXPORT_TIMETABLE, "导出课表");
    }

    @Bean
    ApplicationRunner seedIdentityData(
            PermissionRepository permissionRepository,
            RoleRepository roleRepository,
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        return args -> seed(permissionRepository, roleRepository, userRepository, passwordEncoder);
    }

    @Transactional
    void seed(
            PermissionRepository permissionRepository,
            RoleRepository roleRepository,
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        Map<String, Permission> permissions = new LinkedHashMap<>();
        PERMISSION_NAMES.forEach((code, name) -> permissions.put(
                code, permissionRepository.findByCode(code)
                        .orElseGet(() -> permissionRepository.save(new Permission(code, name)))));

        Role admin = upsertRole(roleRepository, "admin", "教务管理员", Set.copyOf(permissions.values()));

        Set<Permission> teacherPermissions = Set.of(
                permissions.get(Permissions.TIMETABLE_VIEW_OWN),
                permissions.get(Permissions.ASSIGNMENT_VIEW_OWN),
                permissions.get(Permissions.SUBSTITUTION_SUBMIT),
                permissions.get(Permissions.SUBSTITUTION_VIEW_OWN),
                permissions.get(Permissions.NOTIFICATION_VIEW),
                permissions.get(Permissions.ONBOARDING_MANAGE));
        Role teacher = upsertRole(roleRepository, "teacher", "教师", teacherPermissions);

        Set<Permission> headTeacherPermissions = new java.util.HashSet<>(teacherPermissions);
        headTeacherPermissions.add(permissions.get(Permissions.TIMETABLE_VIEW_CLASS));
        headTeacherPermissions.add(permissions.get(Permissions.SUBSTITUTION_VIEW_CLASS));
        upsertRole(roleRepository, "head_teacher", "班主任", headTeacherPermissions);

        if (!userRepository.existsByUsername("admin")) {
            AppUser user = new AppUser();
            user.setUsername("admin");
            user.setDisplayName("教务管理员");
            user.setPasswordHash(passwordEncoder.encode("admin123"));
            user.setCreatedAt(Instant.now());
            user.getRoles().add(admin);
            userRepository.save(user);
            log.info("Bootstrap administrator created: admin / admin123");
        }
        log.info("Identity catalogue ready: {} permissions, roles admin/teacher/head_teacher", permissions.size());
    }

    private Role upsertRole(RoleRepository roleRepository, String code, String name, Set<Permission> permissions) {
        Role role = roleRepository.findByCode(code).orElseGet(() -> new Role(code, name));
        role.setName(name);
        role.getPermissions().clear();
        role.getPermissions().addAll(permissions);
        return roleRepository.save(role);
    }
}
