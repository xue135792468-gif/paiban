package com.paiban.substitution.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstitutionPlanRepository extends JpaRepository<SubstitutionPlan, Long> {

    List<SubstitutionPlan> findByRequestIdOrderByIdAsc(Long requestId);

    List<SubstitutionPlan> findByRequestIdAndStatusOrderByIdAsc(Long requestId, String status);

    List<SubstitutionPlan> findBySemesterIdAndStatus(Long semesterId, String status);
}
