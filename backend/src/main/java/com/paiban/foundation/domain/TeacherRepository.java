package com.paiban.foundation.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeacherRepository extends JpaRepository<Teacher, Long> {

    List<Teacher> findAllByOrderByNameAsc();

    /** Management list: soft-deleted rows disappear, disabled ones stay visible. */
    List<Teacher> findAllByDeletedAtIsNullOrderByNameAsc();

    /** Dropdown options: deleted and disabled teachers are both hidden. */
    List<Teacher> findAllByDeletedAtIsNullAndActiveTrueOrderByNameAsc();

    List<Teacher> findByName(String name);

    Optional<Teacher> findByEmployeeNo(String employeeNo);

    Optional<Teacher> findByPhone(String phone);

    Optional<Teacher> findByDingtalkUserId(String dingtalkUserId);

    /** Historical duplicates are possible; authentication must reject ambiguity explicitly. */
    List<Teacher> findAllByDingtalkUserId(String dingtalkUserId);

    boolean existsByEmployeeNo(String employeeNo);
}
