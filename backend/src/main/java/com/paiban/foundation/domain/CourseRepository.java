package com.paiban.foundation.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<Course, Long> {

    List<Course> findAllByOrderByNameAsc();

    /** Management list: soft-deleted rows disappear, disabled ones stay visible. */
    List<Course> findAllByDeletedAtIsNullOrderByNameAsc();

    Optional<Course> findByName(String name);
}
