package com.paiban.scheduling.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConstraintTemplateRepository extends JpaRepository<ConstraintTemplate, Long> {

    Optional<ConstraintTemplate> findByName(String name);

    List<ConstraintTemplate> findAllByOrderByCreatedAtDesc();
}
