package com.paiban.scheduling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.scheduling.service.TeachingMatrixPlanner.Plan;
import com.paiban.scheduling.service.TeachingMatrixPlanner.RowPlan;
import com.paiban.scheduling.web.TeachingMatrixDtos.Options;
import com.paiban.scheduling.web.TeachingMatrixDtos.Report;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class TeachingMatrixPreviewKey {

    private record Snapshot(Long semesterId, Long gradeId, Map<String, Integer> overrides, boolean saveAsDefaults,
                            Report report, List<Long> teacherIds, List<Long> courseIds, List<RowState> rows) {}

    private record RowState(Long classId, Long teachingClassId, Integer studentCount, Long previousHeadTeacherId,
                            Long headTeacherId, List<AssignmentState> assignments) {}

    private record AssignmentState(Long id, Long teacherId, int weeklyLessons, boolean active) {}

    private TeachingMatrixPreviewKey() {}

    static String of(ObjectMapper json, Long semesterId, Long gradeId, Options options, Plan plan) {
        // The report contains the proposed values. IDs and current row values also distinguish
        // plans whose visible labels and action types stay the same after a concurrent edit.
        var snapshot = new Snapshot(semesterId, gradeId,
                options == null ? Map.of() : new TreeMap<>(options.weeklyHours()),
                options != null && options.saveAsDefaults(), plan.report(),
                plan.teachers().stream().map(teacher -> teacher.existing() == null ? null : teacher.existing().getId()).toList(),
                plan.courses().stream().map(course -> course.existing() == null ? null : course.existing().getId()).toList(),
                plan.rows().stream().map(TeachingMatrixPreviewKey::rowState).toList());
        try {
            byte[] encoded = json.writeValueAsBytes(snapshot);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法生成任课表预演校验码", ex);
        }
    }

    private static RowState rowState(RowPlan row) {
        var adminClass = row.existing();
        var headTeacher = row.headTeacher();
        return new RowState(adminClass == null ? null : adminClass.getId(), row.teachingClassId(),
                adminClass == null ? null : adminClass.getStudentCount(),
                adminClass == null ? null : adminClass.getHeadTeacherId(),
                headTeacher == null || headTeacher.existing() == null ? null : headTeacher.existing().getId(),
                row.cells().stream().map(cell -> {
                    var assignment = cell.existing();
                    return assignment == null ? null : new AssignmentState(assignment.getId(),
                            assignment.getTeacherId(), assignment.getWeeklyLessons(), assignment.isActive());
                }).toList());
    }
}
