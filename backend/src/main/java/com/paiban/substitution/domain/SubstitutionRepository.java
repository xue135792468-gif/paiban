package com.paiban.substitution.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstitutionRepository extends JpaRepository<Substitution, Long> {

    List<Substitution> findBySemesterIdOrderByEffectiveDateDesc(Long semesterId);

    List<Substitution> findByRequestId(Long requestId);

    List<Substitution> findBySemesterIdAndEffectiveDateAndStatus(
            Long semesterId, LocalDate effectiveDate, String status);

    List<Substitution> findBySemesterIdAndStatus(Long semesterId, String status);

    List<Substitution> findBySemesterIdAndStatusAndType(Long semesterId, String status, String type);

    long countBySemesterIdAndOriginalTeacherIdAndTypeAndStatus(
            Long semesterId, Long originalTeacherId, String type, String status);

    long countBySemesterIdAndSubstituteTeacherIdAndTypeAndStatus(
            Long semesterId, Long substituteTeacherId, String type, String status);

    /** Distinct swap plans a teacher took part in; a three-person swap counts once. */
    @org.springframework.data.jpa.repository.Query(
            "select count(distinct s.planId) from Substitution s "
                    + "where s.semesterId = :semesterId and s.originalTeacherId = :teacherId "
                    + "and s.type = 'SWAP' and s.status = 'EFFECTIVE' and s.planId is not null")
    long countSwapPlans(
            @org.springframework.data.repository.query.Param("semesterId") Long semesterId,
            @org.springframework.data.repository.query.Param("teacherId") Long teacherId);

    List<Substitution> findBySubstituteTeacherIdAndEffectiveDateAndStatus(
            Long substituteTeacherId, LocalDate effectiveDate, String status);

    Optional<Substitution> findFirstByEntryIdAndEffectiveDateAndStatus(
            Long entryId, LocalDate effectiveDate, String status);

    /**
     * 旧格式（lesson_date 为空）的生效行：按「自 effective_date 起持续覆盖」解释，
     * 管理写入前用它判断实例是否被历史数据锁定（数据级检查，不做转换）。
     */
    List<Substitution> findByEntryIdAndStatusAndLessonDateIsNull(Long entryId, String status);

    List<Substitution> findBySemesterIdAndEffectiveDateBetweenAndStatus(
            Long semesterId, LocalDate from, LocalDate to, String status);

    long countBySubstituteTeacherIdAndStatus(Long substituteTeacherId, String status);

    long countByOriginalTeacherIdAndStatus(Long originalTeacherId, String status);

    long countBySemesterIdAndSubstituteTeacherIdAndStatus(
            Long semesterId, Long substituteTeacherId, String status);

    long countBySemesterIdAndOriginalTeacherIdAndStatus(
            Long semesterId, Long originalTeacherId, String status);

    long countBySemesterId(Long semesterId);
}
