package com.paiban.timetable.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimetableEntryRepository extends JpaRepository<TimetableEntry, Long> {

    List<TimetableEntry> findBySemesterId(Long semesterId);

    List<TimetableEntry> findBySemesterIdAndAdminClassId(Long semesterId, Long adminClassId);

    List<TimetableEntry> findBySemesterIdAndTeacherId(Long semesterId, Long teacherId);

    List<TimetableEntry> findBySemesterIdAndRoomId(Long semesterId, Long roomId);

    void deleteBySemesterId(Long semesterId);

    long countBySemesterId(Long semesterId);
}
