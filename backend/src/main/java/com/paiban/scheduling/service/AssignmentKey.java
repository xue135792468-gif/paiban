package com.paiban.scheduling.service;

import com.paiban.scheduling.domain.Assignment;

/** The identity a lesson row shares with its teaching assignment. */
public record AssignmentKey(Long classId, Long courseId, Long teacherId) {

    public static AssignmentKey of(Assignment assignment) {
        return new AssignmentKey(
                assignment.getAdminClassId(), assignment.getCourseId(), assignment.getTeacherId());
    }
}
