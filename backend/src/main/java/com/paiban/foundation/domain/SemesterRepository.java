package com.paiban.foundation.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemesterRepository extends JpaRepository<Semester, Long> {

    Optional<Semester> findByCurrentTrue();

    List<Semester> findAllByOrderByStartDateDesc();

    boolean existsByName(String name);
}
