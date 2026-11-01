package com.paiban.scheduling.service;

import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.solver.ConstraintSettings;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Reads the raw data a capacity warning needs and hands it to the pure
 * {@link ClassCapacityPlanner}.
 *
 * <p>The weekly rule snapshot is always passed in by the caller. This service never
 * depends on {@code ConstraintService}/{@code ConstraintConfigService}, so the save,
 * preview, scheduling start and scheduling run paths can all call it without creating
 * a dependency cycle. It reads and never writes: a shortage never changes an
 * assignment, a draft or a published baseline.
 */
@Service
public class ClassCapacityService {

    private final AssignmentRepository assignments;
    private final AdminClassRepository adminClasses;
    private final PeriodRepository periods;

    public ClassCapacityService(
            AssignmentRepository assignments,
            AdminClassRepository adminClasses,
            PeriodRepository periods) {
        this.assignments = assignments;
        this.adminClasses = adminClasses;
        this.periods = periods;
    }

    /** Classes whose summed weekly lessons exceed their distinct usable positions. */
    public List<ClassCapacityPlanner.Load> shortages(
            Long semesterId, ConstraintSettings settings) {
        return ClassCapacityPlanner.shortages(
                slots(semesterId),
                weeklyLessonsByClass(semesterId),
                classNames(semesterId),
                settings);
    }

    /** The user-facing message, empty when every class still fits. */
    public String message(Long semesterId, ConstraintSettings settings) {
        return ClassCapacityPlanner.message(shortages(semesterId, settings));
    }

    private List<ClassCapacityPlanner.Slot> slots(Long semesterId) {
        return periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(semesterId).stream()
                .map(ClassCapacityService::slot)
                .toList();
    }

    private static ClassCapacityPlanner.Slot slot(Period period) {
        return new ClassCapacityPlanner.Slot(period.getDayOfWeek(), period.getPeriodNo());
    }

    private Map<Long, Integer> weeklyLessonsByClass(Long semesterId) {
        Map<Long, Integer> totals = new LinkedHashMap<>();
        for (Assignment assignment : assignments.findBySemesterId(semesterId)) {
            if (assignment.getAdminClassId() == null) {
                continue;
            }
            totals.merge(assignment.getAdminClassId(), assignment.getWeeklyLessons(), Integer::sum);
        }
        return totals;
    }

    private Map<Long, String> classNames(Long semesterId) {
        Map<Long, String> names = new HashMap<>();
        for (AdminClass adminClass : adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(semesterId)) {
            names.put(adminClass.getId(), adminClass.getName());
        }
        return names;
    }
}
