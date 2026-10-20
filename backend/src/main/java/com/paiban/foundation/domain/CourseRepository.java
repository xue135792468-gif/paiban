package com.paiban.foundation.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<Course, Long> {

    List<Course> findAllByOrderByNameAsc();

    Optional<Course> findByName(String name);
}
