package com.paiban.identity.domain;

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
