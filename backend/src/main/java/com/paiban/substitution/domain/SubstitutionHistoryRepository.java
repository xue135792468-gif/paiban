package com.paiban.substitution.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstitutionHistoryRepository extends JpaRepository<SubstitutionHistory, Long> {

    List<SubstitutionHistory> findByRequestIdOrderByCreatedAtAsc(Long requestId);
}
