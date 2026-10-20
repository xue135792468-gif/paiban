package com.paiban.scheduling.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    List<Assignment> findBySemesterId(Long semesterId);

    List<Assignment> findBySemesterIdAndTeacherId(Long semesterId, Long teacherId);

    List<Assignment> findBySemesterIdAndAdminClassId(Long semesterId, Long adminClassId);

    Optional<Assignment> findBySemesterIdAndTeachingClassIdAndCourseId(
            Long semesterId, Long teachingClassId, Long courseId);

    long countBySemesterId(Long semesterId);
}
