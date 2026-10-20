package com.paiban.substitution.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstitutionRequestRepository extends JpaRepository<SubstitutionRequest, Long> {

    List<SubstitutionRequest> findBySemesterIdOrderByCreatedAtDesc(Long semesterId);

    List<SubstitutionRequest> findBySemesterIdAndStatusOrderByCreatedAtDesc(Long semesterId, String status);

    List<SubstitutionRequest> findByRequesterTeacherIdOrderByCreatedAtDesc(Long requesterTeacherId);

    List<SubstitutionRequest> findBySemesterIdAndRequesterTeacherIdOrderByCreatedAtDesc(
            Long semesterId, Long requesterTeacherId);

    long countBySemesterId(Long semesterId);
}
