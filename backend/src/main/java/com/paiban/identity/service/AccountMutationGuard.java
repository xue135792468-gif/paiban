package com.paiban.identity.service;

import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号写入的统一并发锚点（票 01 定义，04 抽出复用）：稳定 super_admin 角色行锁 +
 * 锁后重读目标账号 + 「除目标外还有没有启用超管」的数据库计数。
 *
 * <p>使用契约：
 * <ul>
 *   <li><b>调用方必须已经开启事务</b>：本类方法标了 {@link Propagation#MANDATORY}，
 *       没有事务会直接失败，避免在锁外读改账号；</li>
 *   <li><b>先 {@link #lock}（或 {@link #lockAndRefresh}）再判断、再写</b>：不要先改
 *       managed 实体再调锁，那会触发自动 flush，让判断用上已经改过的快照；</li>
 *   <li>还没有账号的目标（如 03 首次免登）只用 {@link #lock} 拿锚点，不要为锁假读账号；</li>
 *   <li><b>网络与第三方 IO 留在事务和角色锁之外</b>：本类不做任何 IO，只碰数据库行；
 *       通讯录扫描、HTTP 交换都必须在调用本类之前完成。</li>
 * </ul>
 *
 * <p>锁的是角色行而不是 {@code app_user} 行：当启用超管集合为空时账号表上没有行可锁。
 * 拿不到这一行说明种子 / 迁移没把 {@code super_admin} 建起来，此时既不能串行化、
 * 也不能用「没有其他超管」的假象放行，所以明确失败（fail closed）。
 */
@Component
public class AccountMutationGuard {

    /** 票 01：超级管理员是系统里唯一能救回账号的身份。 */
    public static final String SUPER_ADMIN_ROLE = "super_admin";

    private final RoleRepository roles;
    private final AppUserRepository users;
    private final EntityManager entityManager;

    public AccountMutationGuard(
            RoleRepository roles, AppUserRepository users, EntityManager entityManager) {
        this.roles = roles;
        this.users = users;
        this.entityManager = entityManager;
    }

    /**
     * 只拿稳定的角色锚点，不读任何账号：03 首次免登时还没有 accountId，不能为了拿锁
     * 假选一个账号。拿不到 {@code super_admin} 行就明确失败（fail closed）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        if (roles.findByCodeForUpdate(SUPER_ADMIN_ROLE).isEmpty()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "超级管理员角色缺失，账号变更已中止");
        }
    }

    /**
     * 先 {@link #lock} 再读目标账号，并强制从数据库刷新一次：调用方可能早已把这条记录
     * 加载进持久化上下文，拿一级缓存里的 {@code enabled}/{@code roles} 判断会让并发的
     * 停用 / 降级互相看不见。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AppUser lockAndRefresh(Long userId) {
        lock();
        AppUser user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("用户不存在: " + userId));
        entityManager.refresh(user);
        return user;
    }

    /**
     * 锁内直接问数据库「除目标之外还有没有启用中的超管」。标量 count 不经过一级缓存，
     * 因此不会把预加载的旧快照当成事实。
     *
     * <p>调用方必须已经持有 {@link #lockAndRefresh} 拿到的角色锁。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isLastEnabledSuperAdmin(AppUser target) {
        return holdsSuperAdmin(target)
                && users.countEnabledRoleHoldersExcluding(SUPER_ADMIN_ROLE, target.getId()) == 0;
    }

    public static boolean holdsSuperAdmin(AppUser user) {
        return user.getRoles().stream().anyMatch(AccountMutationGuard::isSuperAdminRole);
    }

    public static boolean isSuperAdminRole(Role role) {
        return SUPER_ADMIN_ROLE.equals(role.getCode());
    }
}
