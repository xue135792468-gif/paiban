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

    /** 票 01：超级管理员是这个系统里唯一能救回账号的身份，最后一个不能被降级或停用。 */
    private static final String SUPER_ADMIN_ROLE = "super_admin";

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogRepository auditLogs;
    /** 用于在锚点锁之后刷新目标账号，丢掉调用方预加载的旧快照。 */
    private final jakarta.persistence.EntityManager entityManager;

    public UserService(
            AppUserRepository users,
            RoleRepository roles,
            PasswordEncoder passwordEncoder,
            AuditLogRepository auditLogs,
            jakarta.persistence.EntityManager entityManager) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.auditLogs = auditLogs;
        this.entityManager = entityManager;
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
        // 改身份和改启用状态都可能拿掉「最后一个启用超管」，所以先拿锚点锁再读实体：
        // 反过来（先改 managed entity 再查锁）会触发自动 flush，判断用的是已经被改过的快照。
        lockSuperAdminAnchor();
        AppUser user = getFresh(id);
        Set<Role> nextRoles = request.roleCodes() == null ? null : resolveRoles(request.roleCodes());
        AppUser current = currentOrNull();

        // 先判定、后落笔：两处改动（降级、停用）都可能让启用中的超管归零，共用同一个判断。
        boolean losesSuperAdmin = holdsSuperAdmin(user) && nextRoles != null
                && nextRoles.stream().noneMatch(this::isSuperAdmin);
        boolean willDisable = Boolean.FALSE.equals(request.enabled()) && user.isEnabled();
        if (willDisable && current != null && current.getId().equals(id)) {
            throw ApiException.conflict("不能停用当前登录的账号");
        }
        if ((losesSuperAdmin || willDisable) && holdsSuperAdmin(user)
                && !hasAnotherEnabledSuperAdmin(user)) {
            throw ApiException.conflict(losesSuperAdmin
                    ? "不能降级最后一个启用的超级管理员，请先提升其他超级管理员"
                    : "不能停用最后一个启用的超级管理员，请先启用其他超级管理员");
        }

        // 校验通过后，所有字段都落在同一个 managed 实体上：不要在中途再 refresh，
        // 否则本次尚未 flush 的姓名 / 教师 / 角色改动会被数据库旧值覆盖。
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.teacherId() != null) {
            user.setTeacherId(request.teacherId());
        }
        if (nextRoles != null) {
            user.setRoles(nextRoles);
        }
        // An enabled flag sent with the edit form is not a free write: it goes through
        // the same guards and audit as the dedicated disable/enable endpoints, so this
        // route cannot pause yourself or the last administrator silently.
        if (request.enabled() != null && request.enabled() != user.isEnabled()) {
            if (request.enabled()) {
                applyEnable(user);
            } else {
                applyDisable(user);
            }
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
        lockSuperAdminAnchor();
        AppUser user = getFresh(id);
        AppUser current = currentOrNull();
        if (current != null && current.getId().equals(id)) {
            throw ApiException.conflict("不能停用当前登录的账号");
        }
        if (!user.isEnabled()) {
            return;
        }
        if (holdsSuperAdmin(user) && !hasAnotherEnabledSuperAdmin(user)) {
            throw ApiException.conflict("不能停用最后一个启用的超级管理员，请先启用其他超级管理员");
        }
        applyDisable(user);
    }

    @Transactional
    public void enable(Long id) {
        AppUser user = get(id);
        if (user.isEnabled()) {
            return;
        }
        applyEnable(user);
    }

    /** 只有一个入口改启用状态：调用方负责锁、刷新与校验，这里只落值与审计。 */
    private void applyDisable(AppUser user) {
        user.setEnabled(false);
        audit("DISABLE_USER", "停用用户「" + user.getDisplayName() + "」(" + user.getUsername() + ")");
    }

    private void applyEnable(AppUser user) {
        user.setEnabled(true);
        audit("ENABLE_USER", "启用用户「" + user.getDisplayName() + "」(" + user.getUsername() + ")");
    }

    /**
     * 串行化所有「可能让最后一个启用超管消失」的写入。锁的是稳定的角色行，
     * 因为当超管集合为空时 app_user 上没有行可锁。
     */
    private void lockSuperAdminAnchor() {
        roles.findByCodeForUpdate(SUPER_ADMIN_ROLE);
    }

    /**
     * 锁内读目标账号，并强制从数据库刷新一次：调用方可能早就把这条记录加载进持久化上下文，
     * 拿一级缓存里的 {@code enabled}/{@code roles} 判断会让并发的停用 / 降级互相看不见。
     */
    private AppUser getFresh(Long id) {
        AppUser user = get(id);
        entityManager.refresh(user);
        return user;
    }

    private boolean holdsSuperAdmin(AppUser user) {
        return user.getRoles().stream().anyMatch(this::isSuperAdmin);
    }

    private boolean isSuperAdmin(Role role) {
        return SUPER_ADMIN_ROLE.equals(role.getCode());
    }

    /**
     * 锁内直接问数据库「除目标之外还有没有启用中的超管」。标量 count 不经过一级缓存，
     * 因此不会把预加载的旧快照当成事实。调用方必须已经持有 {@link #lockSuperAdminAnchor()} 的锁。
     */
    private boolean hasAnotherEnabledSuperAdmin(AppUser target) {
        return users.countEnabledRoleHoldersExcluding(SUPER_ADMIN_ROLE, target.getId()) > 0;
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
        // 票 01：升级后旧 admin 账号都是超级管理员，管理类通知的收件人不能因此消失。
        java.util.LinkedHashSet<AppUser> result = new java.util.LinkedHashSet<>();
        result.addAll(users.findAllByRolesCode("admin"));
        result.addAll(users.findAllByRolesCode(SUPER_ADMIN_ROLE));
        return List.copyOf(result);
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
