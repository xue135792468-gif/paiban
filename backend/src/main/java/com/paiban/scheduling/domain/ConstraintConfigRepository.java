package com.paiban.scheduling.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConstraintConfigRepository extends JpaRepository<ConstraintConfig, Long> {

    List<ConstraintConfig> findBySemesterId(Long semesterId);

    Optional<ConstraintConfig> findBySemesterIdAndType(Long semesterId, String type);

    void deleteBySemesterId(Long semesterId);
}
