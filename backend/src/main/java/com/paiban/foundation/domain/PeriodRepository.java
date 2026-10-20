package com.paiban.foundation.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PeriodRepository extends JpaRepository<Period, Long> {

    List<Period> findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(Long semesterId);

    List<Period> findBySemesterIdAndDayOfWeekOrderByPeriodNoAsc(Long semesterId, int dayOfWeek);

    void deleteBySemesterId(Long semesterId);

    long countBySemesterId(Long semesterId);
}
