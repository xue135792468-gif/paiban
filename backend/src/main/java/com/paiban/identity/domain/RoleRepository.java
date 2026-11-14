package com.paiban.identity.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);

    /**
     * 票 01：降级 / 停用的并发锚点。
     *
     * <p>超级管理员角色行在升级后一定存在，先锁这一行再读持有者，所有「可能让最后一个启用超管消失」
     * 的写入就串行化了；只锁 app_user 行不够，因为空集上没有行可锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.code = :code")
    Optional<Role> findByCodeForUpdate(@Param("code") String code);
}
