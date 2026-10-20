package com.paiban.foundation.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeacherRepository extends JpaRepository<Teacher, Long> {

    List<Teacher> findAllByOrderByNameAsc();

    List<Teacher> findByName(String name);

    Optional<Teacher> findByEmployeeNo(String employeeNo);

    boolean existsByEmployeeNo(String employeeNo);
}
