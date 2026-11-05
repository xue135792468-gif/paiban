package com.paiban.scheduling.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    /**
     * Full semester view, including soft-deleted and disabled rows. Only callers that
     * must account for every row (delete impact, recycle bin) should use this; the
     * solver/import/snapshot paths want {@link #findBySemesterIdAndDeletedAtIsNull}.
     */
    List<Assignment> findBySemesterId(Long semesterId);

    /**
     * Solver / import / capacity / snapshot: soft-deleted assignments and deliberately
     * disabled ones never reach the timetable.
     */
    @Query("select a from Assignment a where a.semesterId = :semesterId "
            + "and a.deletedAt is null and a.active = true")
    List<Assignment> findBySemesterIdAndDeletedAtIsNull(@Param("semesterId") Long semesterId);

    /** Management list: disabled assignments stay visible so they can be re-enabled. */
    @Query("select a from Assignment a where a.semesterId = :semesterId and a.deletedAt is null")
    List<Assignment> findBySemesterIdForList(@Param("semesterId") Long semesterId);

    @Query("select a from Assignment a where a.semesterId = :semesterId and a.teacherId = :teacherId "
            + "and a.deletedAt is null and a.active = true")
    List<Assignment> findBySemesterIdAndTeacherId(
            @Param("semesterId") Long semesterId, @Param("teacherId") Long teacherId);

    @Query("select a from Assignment a where a.semesterId = :semesterId and a.adminClassId = :adminClassId "
            + "and a.deletedAt is null and a.active = true")
    List<Assignment> findBySemesterIdAndAdminClassId(
            @Param("semesterId") Long semesterId, @Param("adminClassId") Long adminClassId);

    /**
     * Duplicate check for create/update. Deliberately includes soft-deleted rows: the
     * database unique key still covers them, so excluding them here would turn the
     * duplicate into a raw constraint violation instead of a 409.
     */
    Optional<Assignment> findBySemesterIdAndTeachingClassIdAndCourseId(
            Long semesterId, Long teachingClassId, Long courseId);

    @Query("select count(a) from Assignment a where a.semesterId = :semesterId "
            + "and a.deletedAt is null and a.active = true")
    long countBySemesterId(@Param("semesterId") Long semesterId);

    @Query("select count(a) from Assignment a where a.teacherId = :teacherId and a.deletedAt is null")
    long countByTeacherId(@Param("teacherId") Long teacherId);

    List<Assignment> findByTeacherIdAndDeletedAtIsNull(Long teacherId);

    List<Assignment> findByCourseIdAndDeletedAtIsNull(Long courseId);

    List<Assignment> findByAdminClassIdAndDeletedAtIsNull(Long adminClassId);
}
