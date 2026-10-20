package com.paiban.substitution.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstitutionRepository extends JpaRepository<Substitution, Long> {

    List<Substitution> findBySemesterIdOrderByEffectiveDateDesc(Long semesterId);

    List<Substitution> findBySemesterIdAndEffectiveDateAndStatus(
            Long semesterId, LocalDate effectiveDate, String status);

    List<Substitution> findBySemesterIdAndStatus(Long semesterId, String status);

    List<Substitution> findBySubstituteTeacherIdAndEffectiveDateAndStatus(
            Long substituteTeacherId, LocalDate effectiveDate, String status);

    Optional<Substitution> findFirstByEntryIdAndEffectiveDateAndStatus(
            Long entryId, LocalDate effectiveDate, String status);

    List<Substitution> findBySemesterIdAndEffectiveDateBetweenAndStatus(
            Long semesterId, LocalDate from, LocalDate to, String status);

    long countBySubstituteTeacherIdAndStatus(Long substituteTeacherId, String status);

    long countByOriginalTeacherIdAndStatus(Long originalTeacherId, String status);

    long countBySemesterId(Long semesterId);
}
