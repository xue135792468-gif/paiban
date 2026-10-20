package com.paiban.foundation.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GradeRepository extends JpaRepository<Grade, Long> {

    List<Grade> findBySemesterIdOrderBySortOrderAscNameAsc(Long semesterId);

    Optional<Grade> findBySemesterIdAndName(Long semesterId, String name);

    boolean existsBySemesterIdAndName(Long semesterId, String name);
}
