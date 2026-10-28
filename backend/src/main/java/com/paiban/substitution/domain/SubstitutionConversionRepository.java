package com.paiban.substitution.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstitutionConversionRepository extends JpaRepository<SubstitutionConversion, Long> {

    List<SubstitutionConversion> findBySemesterIdOrderByCreatedAtDesc(Long semesterId);
}
