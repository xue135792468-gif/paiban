package com.paiban.foundation.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminClassRepository extends JpaRepository<AdminClass, Long> {

    List<AdminClass> findBySemesterIdOrderByGradeIdAscNameAsc(Long semesterId);

    /** Management list and option lookups: soft-deleted rows disappear. */
    List<AdminClass> findBySemesterIdAndDeletedAtIsNullOrderByGradeIdAscNameAsc(Long semesterId);

    List<AdminClass> findByGradeIdAndDeletedAtIsNull(Long gradeId);

    Optional<AdminClass> findBySemesterIdAndName(Long semesterId, String name);

    boolean existsBySemesterIdAndName(Long semesterId, String name);

    long countBySemesterId(Long semesterId);
}
