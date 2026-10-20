package com.paiban.scheduling.service;

import com.paiban.common.web.ApiException;
import com.paiban.foundation.domain.AdminClass;
import com.paiban.foundation.domain.AdminClassRepository;
import com.paiban.foundation.domain.Grade;
import com.paiban.foundation.domain.GradeRepository;
import com.paiban.foundation.domain.Period;
import com.paiban.foundation.domain.PeriodRepository;
import com.paiban.foundation.service.FoundationService;
import com.paiban.foundation.service.SemesterService;
import com.paiban.foundation.web.FoundationDtos.ClassDto;
import com.paiban.foundation.web.FoundationDtos.GradeDto;
import com.paiban.foundation.web.FoundationDtos.SaveClassRequest;
import com.paiban.foundation.web.FoundationDtos.SaveGradeRequest;
import com.paiban.foundation.web.SemesterDtos.CopyResultDto;
import com.paiban.foundation.web.SemesterDtos.PeriodSlot;
import com.paiban.scheduling.domain.Assignment;
import com.paiban.scheduling.domain.AssignmentRepository;
import com.paiban.scheduling.web.AssignmentDtos.SaveAssignmentRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copies the structural setup of a semester (schedule, grades, classes, assignments)
 * into another semester, so a new term starts from last term's shape (user story 9).
 */
@Service
public class SemesterCopyService {

    private final SemesterService semesters;
    private final FoundationService foundation;
    private final AssignmentService assignments;
    private final PeriodRepository periods;
    private final GradeRepository grades;
    private final AdminClassRepository adminClasses;
    private final AssignmentRepository assignmentRepository;

    public SemesterCopyService(
            SemesterService semesters,
            FoundationService foundation,
            AssignmentService assignments,
            PeriodRepository periods,
            GradeRepository grades,
            AdminClassRepository adminClasses,
            AssignmentRepository assignmentRepository) {
        this.semesters = semesters;
        this.foundation = foundation;
        this.assignments = assignments;
        this.periods = periods;
        this.grades = grades;
        this.adminClasses = adminClasses;
        this.assignmentRepository = assignmentRepository;
    }

    @Transactional
    public CopyResultDto copy(Long sourceId, Long targetId) {
        if (sourceId.equals(targetId)) {
            throw ApiException.badRequest("不能从同一学期复制");
        }
        semesters.require(sourceId);
        semesters.require(targetId);

        int copiedPeriods = copyPeriods(sourceId, targetId);
        Map<Long, Long> gradeMapping = copyGrades(sourceId, targetId);
        Map<Long, Long> classMapping = copyClasses(sourceId, targetId, gradeMapping);
        int copiedAssignments = copyAssignments(sourceId, targetId, classMapping);
        return new CopyResultDto(copiedPeriods, gradeMapping.size(), classMapping.size(), copiedAssignments);
    }

    private int copyPeriods(Long sourceId, Long targetId) {
        List<Period> source = periods.findBySemesterIdOrderByDayOfWeekAscPeriodNoAsc(sourceId);
        if (source.isEmpty()) {
            return 0;
        }
        Map<Integer, PeriodSlot> slots = new LinkedHashMap<>();
        Map<Integer, List<Integer>> days = new HashMap<>();
        for (Period period : source) {
            slots.putIfAbsent(
                    period.getPeriodNo(),
                    new PeriodSlot(
                            period.getPeriodNo(),
                            period.getLabel(),
                            period.getSection(),
                            period.getStartTime(),
                            period.getEndTime(),
                            new ArrayList<>()));
            days.computeIfAbsent(period.getPeriodNo(), key -> new ArrayList<>()).add(period.getDayOfWeek());
        }
        List<PeriodSlot> result = slots.values().stream()
                .map(slot -> new PeriodSlot(
                        slot.periodNo(),
                        slot.label(),
                        slot.section(),
                        slot.startTime(),
                        slot.endTime(),
                        days.get(slot.periodNo()).stream().sorted().toList()))
                .toList();
        return semesters.replacePeriods(targetId, result).size();
    }

    private Map<Long, Long> copyGrades(Long sourceId, Long targetId) {
        Map<Long, Long> mapping = new HashMap<>();
        for (Grade grade : grades.findBySemesterIdOrderBySortOrderAscNameAsc(sourceId)) {
            GradeDto created = foundation.createGrade(
                    targetId, new SaveGradeRequest(grade.getName(), grade.getSortOrder()));
            mapping.put(grade.getId(), created.id());
        }
        return mapping;
    }

    private Map<Long, Long> copyClasses(Long sourceId, Long targetId, Map<Long, Long> gradeMapping) {
        Map<Long, Long> mapping = new HashMap<>();
        for (AdminClass adminClass : adminClasses.findBySemesterIdOrderByGradeIdAscNameAsc(sourceId)) {
            Long gradeId = gradeMapping.get(adminClass.getGradeId());
            if (gradeId == null) {
                continue;
            }
            ClassDto created = foundation.createClass(
                    targetId,
                    new SaveClassRequest(
                            gradeId,
                            adminClass.getName(),
                            adminClass.getHeadTeacherId(),
                            adminClass.getStudentCount()));
            mapping.put(adminClass.getId(), created.id());
        }
        return mapping;
    }

    private int copyAssignments(Long sourceId, Long targetId, Map<Long, Long> classMapping) {
        int count = 0;
        for (Assignment assignment : assignmentRepository.findBySemesterId(sourceId)) {
            Long classId = classMapping.get(assignment.getAdminClassId());
            if (classId == null) {
                continue;
            }
            assignments.create(
                    targetId,
                    new SaveAssignmentRequest(
                            classId,
                            assignment.getCourseId(),
                            assignment.getTeacherId(),
                            assignment.getWeeklyLessons()));
            count++;
        }
        return count;
    }
}
