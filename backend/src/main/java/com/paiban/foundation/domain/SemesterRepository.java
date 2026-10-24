package com.paiban.foundation.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SemesterRepository extends JpaRepository<Semester, Long> {

    Optional<Semester> findByCurrentTrue();

    List<Semester> findAllByOrderByStartDateDesc();

    boolean existsByName(String name);

    /**
     * Serialises substitutions that write into the same semester, so two approvals
     * cannot both pass the snapshot-hash check and then interleave their writes.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Semester s where s.id = :id")
    Optional<Semester> findWithLockById(
            @org.springframework.data.repository.query.Param("id") Long id);
}
