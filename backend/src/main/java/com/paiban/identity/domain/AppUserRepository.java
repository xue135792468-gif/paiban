package com.paiban.identity.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<AppUser> findByTeacherId(Long teacherId);

    List<AppUser> findAllByTeacherId(Long teacherId);

    List<AppUser> findAllByRolesCode(String roleCode);

    /**
     * 03-C 绑定保护：某个教师档案是否挂在持有任一指定<b>权限点</b>（不限启用状态）的账号上。
     *
     * <p>按能力问、不按角色名问：预置角色只是初始化产物，库里可能还有遗留自定义角色
     * （ADR-0006 不允许把角色名写进权限判断）。
     *
     * <p>只用标量存在性查询：调用方在同步 / 事件里已经加载或即将改动教师实体，
     * 这里不能再把账号实体拉进持久化上下文。启用与停用的管理账号同样算数——
     * 先给停用管理员弱绑定、等日后启用再冒用，正是这条要挡的。
     */
    boolean existsByTeacherIdAndRolesPermissionsCodeIn(
            Long teacherId, Collection<String> permissionCodes);

    /**
     * Counts the <b>other</b> enabled holders of a role straight from the database.
     *
     * <p>票 01：这个数字决定「最后一个启用超管」能否被降级 / 停用，所以不能用实体查询：
     * 调用方可能已经把目标和管理员预加载进持久化上下文，实体查询会返回一级缓存里的旧
     * {@code enabled = true}，两个并发动作就会各自以为还有别人。标量 count 不进缓存。
     */
    @Query("select count(u) from AppUser u join u.roles role "
            + "where role.code = :roleCode and u.enabled = true and u.id <> :excludeUserId")
    long countEnabledRoleHoldersExcluding(
            @Param("roleCode") String roleCode, @Param("excludeUserId") Long excludeUserId);
}
