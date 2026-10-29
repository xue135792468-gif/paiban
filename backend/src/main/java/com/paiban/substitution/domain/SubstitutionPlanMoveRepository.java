package com.paiban.substitution.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstitutionPlanMoveRepository extends JpaRepository<SubstitutionPlanMove, Long> {

    List<SubstitutionPlanMove> findByPlanIdOrderByMoveOrderAsc(Long planId);

    /** 历史转换预检用：一次取回多个方案的全部移动。 */
    List<SubstitutionPlanMove> findByPlanIdIn(Collection<Long> planIds);

    void deleteByPlanId(Long planId);
}
