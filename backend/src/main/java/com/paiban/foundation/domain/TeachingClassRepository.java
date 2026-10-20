package com.paiban.foundation.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeachingClassRepository extends JpaRepository<TeachingClass, Long> {

    Optional<TeachingClass> findByAdminClassId(Long adminClassId);

    List<TeachingClass> findBySemesterId(Long semesterId);

    void deleteByAdminClassId(Long adminClassId);
}
