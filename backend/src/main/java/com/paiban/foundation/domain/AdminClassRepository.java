package com.paiban.foundation.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminClassRepository extends JpaRepository<AdminClass, Long> {

    List<AdminClass> findBySemesterIdOrderByGradeIdAscNameAsc(Long semesterId);

    Optional<AdminClass> findBySemesterIdAndName(Long semesterId, String name);

    boolean existsBySemesterIdAndName(Long semesterId, String name);

    long countBySemesterId(Long semesterId);
}
