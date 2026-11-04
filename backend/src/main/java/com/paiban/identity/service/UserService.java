package com.paiban.identity.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLog;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.identity.web.IdentityDtos.CreateUserRequest;
import com.paiban.identity.web.IdentityDtos.RoleDto;
import com.paiban.identity.web.IdentityDtos.UpdateUserRequest;
import com.paiban.identity.web.IdentityDtos.UserSummary;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogRepository auditLogs;

    public UserService(
            AppUserRepository users,
            RoleRepository roles,
            PasswordEncoder passwordEncoder,
            AuditLogRepository auditLogs) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.auditLogs = auditLogs;
    }

    @Transactional(readOnly = true)
    public List<UserSummary> list() {
        return users.findAll().stream()
                .sorted(Comparator.comparing(AppUser::getId))
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public UserSummary create(CreateUserRequest request) {
        if (users.existsByUsername(request.username())) {
            throw ApiException.conflict("用户名已存在: " + request.username());
        }
        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setDisplayName(request.displayName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setTeacherId(request.teacherId());
        user.setCreatedAt(Instant.now());
        user.setRoles(resolveRoles(request.roleCodes()));
        return toSummary(users.save(user));
    }

    @Transactional
    public UserSummary update(Long id, UpdateUserRequest request) {
        AppUser user = get(id);
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        if (request.roleCodes() != null) {
            user.setRoles(resolveRoles(request.roleCodes()));
        }
        if (request.teacherId() != null) {
            user.setTeacherId(request.teacherId());
        }
        return toSummary(user);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        AppUser user = get(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
    }

    /**
     * Ticket 04: accounts are never deleted, only paused — a disabled user keeps its
     * history, roles and teacher link, while login and new requests are refused.
     *
     * <p>Two situations are refused instead of silently locking the school out: you
     * cannot pause the account you are signed in with, and the last enabled
     * administrator cannot be paused.
     */
    @Transactional
    public void disable(Long id) {
        AppUser user = get(id);
        AppUser current = currentOrNull();
        if (current != null && current.getId().equals(id)) {
            throw ApiException.conflict("不能停用当前登录的账号");
        }
        if (!user.isEnabled()) {
            return;
        }
        if (isLastEnabledAdmin(user)) {
            throw ApiException.conflict("不能停用最后一个启用的管理员，请先启用其他管理员");
        }
        user.setEnabled(false);
        audit("DISABLE_USER", "停用用户「" + user.getDisplayName() + "」(" + user.getUsername() + ")");
    }

    @Transactional
    public void enable(Long id) {
        AppUser user = get(id);
        if (user.isEnabled()) {
            return;
        }
        user.setEnabled(true);
        audit("ENABLE_USER", "启用用户「" + user.getDisplayName() + "」(" + user.getUsername() + ")");
    }

    private boolean isLastEnabledAdmin(AppUser target) {
        if (target.getRoles().stream().noneMatch(role -> "admin".equals(role.getCode()))) {
            return false;
        }
        return users.findAllByRolesCode("admin").stream()
                .noneMatch(other -> !other.getId().equals(target.getId()) && other.isEnabled());
    }

    /** The caller, or null when there is no usable principal (startup, CLI, tests). */
    private AppUser currentOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            return null;
        }
        return users.findByUsername(authentication.getName()).orElse(null);
    }

    private void audit(String action, String detail) {
        String actor = currentOrNull() == null ? "system" : currentOrNull().getUsername();
        auditLogs.save(new AuditLog(actor, action, "APP_USER", detail, 1, 0));
    }

    @Transactional(readOnly = true)
    public AppUser get(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("用户不存在: " + id));
    }

    /** The signed-in account; an anonymous or expired principal is a 401, never null. */
    @Transactional(readOnly = true)
    public AppUser current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            throw ApiException.unauthorized("未登录或登录已过期");
        }
        return getByUsername(authentication.getName());
    }

    /** The one permission check for services that do not want to read the context themselves. */
    public boolean hasPermission(String permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.equals(authority.getAuthority()));
    }

    @Transactional(readOnly = true)
    public AppUser getByUsername(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> ApiException.notFound("用户不存在: " + username));
    }

    @Transactional(readOnly = true)
    public List<AppUser> usersByTeacherId(Long teacherId) {
        return teacherId == null ? List.of() : users.findAllByTeacherId(teacherId);
    }

    @Transactional(readOnly = true)
    public List<AppUser> adminUsers() {
        return users.findAllByRolesCode("admin");
    }

    @Transactional(readOnly = true)
    public List<AppUser> allUsers() {
        return users.findAll();
    }

    @Transactional
    public AppUser save(AppUser user) {
        return users.save(user);
    }

    /** Authorization must be expressed with permission codes, never with role names (ADR-0006). */
    public boolean hasPermission(AppUser user, String permissionCode) {
        return user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .anyMatch(permission -> permission.getCode().equals(permissionCode));
    }

    /** 全部权限点都具备才返回 true；管理动作按权限常量组合判定（如调代工作台）。 */
    public boolean hasAllPermissions(AppUser user, String... permissionCodes) {
        java.util.Set<String> owned = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(com.paiban.identity.domain.Permission::getCode)
                .collect(java.util.stream.Collectors.toSet());
        for (String code : permissionCodes) {
            if (!owned.contains(code)) {
                return false;
            }
        }
        return true;
    }

    @Transactional(readOnly = true)
    public List<RoleDto> listRoles() {        return roles.findAll().stream()
                .sorted(Comparator.comparing(Role::getId))
                .map(role -> new RoleDto(
                        role.getId(),
                        role.getCode(),
                        role.getName(),
                        role.getPermissions().stream().map(p -> p.getCode()).sorted().toList()))
                .toList();
    }

    public UserSummary toSummary(AppUser user) {
        List<String> roleCodes = user.getRoles().stream().map(Role::getCode).sorted().toList();
        List<String> permissions = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(p -> p.getCode())
                .distinct()
                .sorted()
                .toList();
        return new UserSummary(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.isEnabled(),
                user.getTeacherId(),
                roleCodes,
                permissions);
    }

    private Set<Role> resolveRoles(List<String> roleCodes) {
        Set<Role> result = new HashSet<>();
        if (roleCodes == null || roleCodes.isEmpty()) {
            result.add(roles.findByCode("teacher").orElseThrow(() -> ApiException.badRequest("默认角色缺失")));
            return result;
        }
        for (String code : roleCodes) {
            result.add(roles.findByCode(code)
                    .orElseThrow(() -> ApiException.badRequest("角色不存在: " + code)));
        }
        return result;
    }
}
