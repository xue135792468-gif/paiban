package com.paiban.substitution.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubstitutionRequestRepository extends JpaRepository<SubstitutionRequest, Long> {

    List<SubstitutionRequest> findBySemesterIdOrderByCreatedAtDesc(Long semesterId);

    List<SubstitutionRequest> findBySemesterIdAndStatusOrderByCreatedAtDesc(Long semesterId, String status);

    List<SubstitutionRequest> findByRequesterTeacherIdOrderByCreatedAtDesc(Long requesterTeacherId);

    List<SubstitutionRequest> findBySemesterIdAndRequesterTeacherIdOrderByCreatedAtDesc(
            Long semesterId, Long requesterTeacherId);

    List<SubstitutionRequest> findByEntryIdAndEffectiveDate(Long entryId, java.time.LocalDate effectiveDate);

    long countBySemesterId(Long semesterId);

    /**
     * 票 05：审批/驳回/撤回都在学期行锁之后锁本行再读状态，
     * 避免「锁前载入的 JPA 一级缓存」掩盖另一位管理员刚刚提交的处理结果。
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SubstitutionRequest r where r.id = :id")
    Optional<SubstitutionRequest> findWithLockById(@Param("id") Long id);
}
