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
            PasswordEncoder passwordEncoder,
            org.springframework.transaction.support.TransactionTemplate transactionTemplate,
            org.springframework.core.env.Environment environment,
            @org.springframework.beans.factory.annotation.Value("${paiban.security.bootstrap-admin-password:}")
                    String configuredAdminPassword) {
        return args -> transactionTemplate.executeWithoutResult(status -> seed(
                permissionRepository, roleRepository, userRepository, passwordEncoder,
                environment, configuredAdminPassword));
    }

    void seed(
            PermissionRepository permissionRepository,
            RoleRepository roleRepository,
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            org.springframework.core.env.Environment environment,
            String configuredAdminPassword) {
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
                permissions.get(Permissions.NOTIFICATION_VIEW));
        Role teacher = upsertRole(roleRepository, "teacher", "教师", teacherPermissions);

        Set<Permission> headTeacherPermissions = new java.util.HashSet<>(teacherPermissions);
        headTeacherPermissions.add(permissions.get(Permissions.TIMETABLE_VIEW_CLASS));
        headTeacherPermissions.add(permissions.get(Permissions.SUBSTITUTION_VIEW_CLASS));
        upsertRole(roleRepository, "head_teacher", "班主任", headTeacherPermissions);

        if (!userRepository.existsByUsername("admin")) {
            String password = bootstrapPassword(environment, configuredAdminPassword);
            AppUser user = new AppUser();
            user.setUsername("admin");
            user.setDisplayName("教务管理员");
            user.setPasswordHash(passwordEncoder.encode(password));
            user.setCreatedAt(Instant.now());
            user.getRoles().add(admin);
            userRepository.save(user);
            logBootstrapAccount(password);
        } else {
            log.debug("管理员账号已存在，跳过创建（重复启动不会重置密码）");
        }
        log.info("Identity catalogue ready: {} permissions, roles admin/teacher/head_teacher", permissions.size());
    }

    /**
     * Prints the bootstrap credential exactly once, when the account is created. Later starts
     * never log it again, so a restarted container does not spray the password into the logs.
     */
    private void logBootstrapAccount(String password) {
        if (password.equals("admin123")) {
            log.info("已创建默认管理员账号：admin / admin123（仅本地开发使用，请登录后立即修改密码）");
        } else {
            log.info("已创建默认管理员账号：admin / {}（仅显示一次，请立即记录并登录后修改密码）", password);
        }
    }

    /**
     * Local development keeps admin/admin123. Any other profile requires an explicit password
     * or gets a generated one printed once, so the repository never ships a working credential.
     */
    private String bootstrapPassword(
            org.springframework.core.env.Environment environment, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        for (String profile : environment.getActiveProfiles()) {
            String value = profile.toLowerCase(java.util.Locale.ROOT);
            if (value.equals("local") || value.equals("test") || value.equals("e2e")) {
                return "admin123";
            }
        }
        // The caller logs the generated password once, right after the account is saved.
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private Role upsertRole(RoleRepository roleRepository, String code, String name, Set<Permission> permissions) {
        Role role = roleRepository.findByCode(code).orElseGet(() -> new Role(code, name));
        role.setName(name);
        role.getPermissions().clear();
        role.getPermissions().addAll(permissions);
        return roleRepository.save(role);
    }
}
