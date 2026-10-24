package com.paiban.substitution.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstitutionPlanMoveRepository extends JpaRepository<SubstitutionPlanMove, Long> {

    List<SubstitutionPlanMove> findByPlanIdOrderByMoveOrderAsc(Long planId);

    void deleteByPlanId(Long planId);
}
