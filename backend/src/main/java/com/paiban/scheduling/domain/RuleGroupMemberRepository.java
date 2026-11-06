package com.paiban.scheduling.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RuleGroupMemberRepository extends JpaRepository<RuleGroupMember, Long> {

    List<RuleGroupMember> findByGroupIdOrderByIdAsc(Long groupId);

    List<RuleGroupMember> findByGroupIdInOrderByIdAsc(Collection<Long> groupIds);

    /**
     * Replaces the whole member list of a group. A direct delete (instead of a derived
     * load-and-remove) keeps the (group_id, member_key) unique index safe when the same
     * member is re-inserted within one transaction.
     */
    @Modifying
    @Query("delete from RuleGroupMember m where m.groupId = :groupId")
    void deleteByGroupId(@Param("groupId") Long groupId);
}
