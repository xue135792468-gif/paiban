package com.paiban.scheduling.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConstraintEntryRepository extends JpaRepository<ConstraintEntry, Long> {

    List<ConstraintEntry> findBySemesterId(Long semesterId);

    List<ConstraintEntry> findBySemesterIdAndTypeOrderByIdAsc(Long semesterId, String type);

    void deleteBySemesterId(Long semesterId);
}
