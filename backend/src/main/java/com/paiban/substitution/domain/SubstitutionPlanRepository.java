package com.paiban.substitution.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstitutionPlanRepository extends JpaRepository<SubstitutionPlan, Long> {

    List<SubstitutionPlan> findByRequestIdOrderByIdAsc(Long requestId);

    List<SubstitutionPlan> findByRequestIdAndStatusOrderByIdAsc(Long requestId, String status);

    List<SubstitutionPlan> findBySemesterIdAndStatus(Long semesterId, String status);

    /** 票 05：管理端预览方案（选定前没有申请），重新生成时清理旧候选。 */
    List<SubstitutionPlan> findByRequestIdIsNullAndStatus(String status);

    /** 评审修复 P2b：只清理当前操作者自己的上一批试算方案，避免并发互删。 */
    List<SubstitutionPlan> findByOwnerUserIdAndRequestIdIsNullAndStatus(
            Long ownerUserId, String status);
}
