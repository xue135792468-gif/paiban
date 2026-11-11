package com.paiban.foundation.domain;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * 票 02：账号审计页的过滤与分页全部在数据库层完成。
     *
     * <p>{@code action} / {@code targetUserId} 为 null 表示不按该维度过滤；排序固定为
     * 时间倒序 + id 倒序，同一毫秒写入的多条事件也有稳定顺序。
     */
    @Query(value = "select a from AuditLog a"
            + " where a.category = :category"
            + " and (:action is null or a.action = :action or a.action = :legacyAction)"
            + " and (:targetUserId is null or a.targetUserId = :targetUserId)"
            + " order by a.createdAt desc, a.id desc",
            countQuery = "select count(a) from AuditLog a"
                    + " where a.category = :category"
                    + " and (:action is null or a.action = :action or a.action = :legacyAction)"
                    + " and (:targetUserId is null or a.targetUserId = :targetUserId)")
    Page<AuditLog> search(
            @Param("category") String category,
            @Param("action") String action,
            @Param("legacyAction") String legacyAction,
            @Param("targetUserId") Long targetUserId,
            Pageable pageable);

    /**
     * 旧通用日志接口的数据库层视图：账号事件不出现在这里，避免有基础数据权限的
     * 教务管理员从旁路读到账号操作。其余基础日志保持原样可读。
     */
    @Query("select a from AuditLog a"
            + " where a.category is null or a.category <> :category"
            + " order by a.createdAt desc, a.id desc")
    List<AuditLog> findRecentOutsideCategory(@Param("category") String category, Pageable pageable);
}
