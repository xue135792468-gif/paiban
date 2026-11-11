package com.paiban.identity.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AuditLogRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.AccountAuditAction;
import com.paiban.identity.domain.LoginType;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.identity.web.IdentityDtos.CreateUserRequest;
import com.paiban.identity.web.IdentityDtos.RoleDto;
import com.paiban.identity.web.IdentityDtos.UpdateUserRequest;
import com.paiban.identity.web.IdentityDtos.UserSummary;
import com.paiban.identity.web.AccountAuditDtos.AccountSnapshot;
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
    private final AccountAuditService accountAudits;
    /** 角色锚点锁 + 锁后重读 + 超管计数（票 04 抽出，供本服务与通讯录同步共用）。 */
    private final AccountMutationGuard accountMutationGuard;

    public UserService(
            AppUserRepository users,
            RoleRepository roles,
            PasswordEncoder passwordEncoder,
            AccountAuditService accountAudits,
            AccountMutationGuard accountMutationGuard) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.accountAudits = accountAudits;
        this.accountMutationGuard = accountMutationGuard;
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
        return create(request, AccountAuditAction.CREATE);
    }

    /**
     * 建号的唯一实现。批量导入用 {@link AccountAuditAction#IMPORT} 记录来源，
     * 这样每个成功账号都可追溯，又不会被误记成手工建号（票 02）。
     */
    @Transactional
    public UserSummary create(CreateUserRequest request, AccountAuditAction source) {
        if (users.existsByUsername(request.username())) {
            throw ApiException.conflict("用户名已存在: " + request.username());
        }
        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setDisplayName(request.displayName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        // 手工建号永远是密码账号；钉钉免登账号只能由免登流程创建（票 03-A3）。
        user.setLoginType(LoginType.PASSWORD);
        user.setTeacherId(request.teacherId());
        user.setCreatedAt(Instant.now());
        user.setRoles(resolveRoles(request.roleCodes()));
        AppUser saved = users.save(user);
        accountAudits.record(
                actorName(), source, saved.getId(), null, accountAudits.snapshotOf(saved));
        return toSummary(saved);
    }

    @Transactional
    public UserSummary update(Long id, UpdateUserRequest request) {
        // 改身份和改启用状态都可能拿掉「最后一个启用超管」，所以先拿锚点锁再读实体：
        // 反过来（先改 managed entity 再查锁）会触发自动 flush，判断用的是已经被改过的快照。
        AppUser user = accountMutationGuard.lockAndRefresh(id);
        AccountSnapshot before = accountAudits.snapshotOf(user);
        Set<Role> nextRoles = request.roleCodes() == null ? null : resolveRoles(request.roleCodes());
        AppUser current = currentOrNull();

        // 先判定、后落笔：两处改动（降级、停用）都可能让启用中的超管归零，共用同一个判断。
        boolean losesSuperAdmin = AccountMutationGuard.holdsSuperAdmin(user) && nextRoles != null
                && nextRoles.stream().noneMatch(this::isSuperAdmin);
        boolean willDisable = Boolean.FALSE.equals(request.enabled()) && user.isEnabled();
        if (willDisable && current != null && current.getId().equals(id)) {
            throw ApiException.conflict("不能停用当前登录的账号");
        }
        if ((losesSuperAdmin || willDisable) && accountMutationGuard.isLastEnabledSuperAdmin(user)) {
            throw ApiException.conflict(losesSuperAdmin
                    ? "不能降级最后一个启用的超级管理员，请先提升其他超级管理员"
                    : "不能停用最后一个启用的超级管理员，请先启用其他超级管理员");
        }

        // 校验通过后，所有字段都落在同一个 managed 实体上：不要在中途再 refresh，
        // 否则本次尚未 flush 的姓名 / 教师 / 角色改动会被数据库旧值覆盖。
        boolean roleChanged = nextRoles != null && !sameRoleCodes(user.getRoles(), nextRoles);
        boolean profileChanged = false;
        if (request.displayName() != null) {
            profileChanged |= !request.displayName().equals(user.getDisplayName());
            user.setDisplayName(request.displayName());
        }
        if (request.teacherId() != null) {
            profileChanged |= !request.teacherId().equals(user.getTeacherId());
            user.setTeacherId(request.teacherId());
        }
        if (nextRoles != null) {
            user.setRoles(nextRoles);
        }
        // 第一条事件只描述身份 / 资料阶段：启用状态仍是旧值。角色真正变化才写「改身份」，
        // 只改姓名或关联教师写「修改资料」；两条都没有就不伪造事件。
        if (roleChanged || profileChanged) {
            accountAudits.record(
                    actorName(),
                    roleChanged ? AccountAuditAction.ROLE_CHANGE : AccountAuditAction.UPDATE,
                    user.getId(),
                    before,
                    accountAudits.snapshotOf(user));
        }
        // An enabled flag sent with the edit form is not a free write: it goes through
        // the same guards and audit as the dedicated disable/enable endpoints, so this
        // route cannot pause yourself or the last administrator silently.
        // 启停阶段从刚写好的资料（或原资料）出发，形成与上一条连续、不重叠的链。
        if (request.enabled() != null && request.enabled() != user.isEnabled()) {
            if (request.enabled()) {
                applyEnable(user);
            } else {
                applyDisable(user, AccountAuditAction.DISABLE);
            }
        }
        return toSummary(user);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        // 与停用共用同一个锚点锁并重读：重置密码只改哈希，但整行更新会把读到的
        // 旧 enabled 一并写回，可能把并发停用的账号「复活」。
        AppUser user = accountMutationGuard.lockAndRefresh(id);
        // 票 03：钉钉免登账号没有密码。拒绝必须发生在写哈希与写审计之前——
        // 否则数据库的组合约束只会给出 500，还可能留下一条「重置成功」的假事件。
        if (user.getLoginType() == LoginType.DINGTALK) {
            throw ApiException.conflict("该账号是钉钉免登账号，没有密码；请在钉钉中打开");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // 密码本身（及其哈希）不进审计：前后快照只描述账号的可见状态。
        accountAudits.record(
                actorName(),
                AccountAuditAction.RESET_PASSWORD,
                user.getId(),
                accountAudits.snapshotOf(user),
                accountAudits.snapshotOf(user));
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
        disable(id, AccountAuditAction.DISABLE);
    }

    /**
     * 票 04：通讯录同步确认的停用入口。守卫、锁与幂等与手工停用完全相同，
     * 审计只写一条「同步停用」——不会再落一条普通停用事件。
     *
     * <p>操作者必须是当前登录的管理员：同步确认是管理员发起的动作，没有登录主体时
     * 明确失败，避免把动作记成 {@code system}。
     */
    @Transactional
    public void disableForSync(Long id) {
        requireCurrentOperator();
        disable(id, AccountAuditAction.SYNC_DISABLE);
    }

    @Transactional
    public void enable(Long id) {
        AppUser user = accountMutationGuard.lockAndRefresh(id);
        if (user.isEnabled()) {
            return;
        }
        applyEnable(user);
    }

    /**
     * 停用的唯一实现：锁内自检「停自己」与「最后一个启用超管」，已停用幂等返回。
     * {@code action} 只允许手工停用或同步停用，由两个公开入口各自固定，不开放任意动作。
     */
    private void disable(Long id, AccountAuditAction action) {
        requireDisableAction(action);
        AppUser user = accountMutationGuard.lockAndRefresh(id);
        AppUser current = currentOrNull();
        if (current != null && current.getId().equals(id)) {
            throw ApiException.conflict("不能停用当前登录的账号");
        }
        if (!user.isEnabled()) {
            return;
        }
        if (accountMutationGuard.isLastEnabledSuperAdmin(user)) {
            throw ApiException.conflict("不能停用最后一个启用的超级管理员，请先启用其他超级管理员");
        }
        applyDisable(user, action);
    }

    private void requireDisableAction(AccountAuditAction action) {
        if (action != AccountAuditAction.DISABLE && action != AccountAuditAction.SYNC_DISABLE) {
            throw new IllegalArgumentException("账号停用只接受手工停用或同步停用动作");
        }
    }

    /** 只有一个入口改启用状态：调用方负责锁、刷新与校验，这里只落值与审计。 */
    private void applyDisable(AppUser user, AccountAuditAction action) {
        AccountSnapshot before = accountAudits.snapshotOf(user);
        user.setEnabled(false);
        accountAudits.record(
                actorName(), action, user.getId(), before, accountAudits.snapshotOf(user));
    }

    private void applyEnable(AppUser user) {
        AccountSnapshot before = accountAudits.snapshotOf(user);
        user.setEnabled(true);
        accountAudits.record(
                actorName(), AccountAuditAction.ENABLE, user.getId(), before, accountAudits.snapshotOf(user));
    }

    private boolean isSuperAdmin(Role role) {
        return AccountMutationGuard.isSuperAdminRole(role);
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

    /** 同步停用要求真实管理员身份：没有登录主体就明确失败，而不是记成 system。 */
    private AppUser requireCurrentOperator() {
        AppUser current = currentOrNull();
        if (current == null) {
            throw ApiException.unauthorized("同步停用需要当前登录的管理员");
        }
        return current;
    }

    /** 审计里的操作者：登录账号的用户名；无登录主体（启动 / 命令行）时写 system。 */
    private String actorName() {
        AppUser current = currentOrNull();
        return current == null ? "system" : current.getUsername();
    }

    private boolean sameRoleCodes(Set<Role> current, Set<Role> next) {
        Set<String> currentCodes = current.stream().map(Role::getCode).collect(java.util.stream.Collectors.toSet());
        Set<String> nextCodes = next.stream().map(Role::getCode).collect(java.util.stream.Collectors.toSet());
        return currentCodes.equals(nextCodes);
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
        result.addAll(users.findAllByRolesCode(AccountMutationGuard.SUPER_ADMIN_ROLE));
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
                permissions,
                user.getLoginType().name());
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
