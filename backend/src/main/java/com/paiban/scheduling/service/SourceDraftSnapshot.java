package com.paiban.scheduling.service;

import java.util.List;

/**
 * The frozen arrangement of one source draft at the moment a "continue optimising from
 * this draft" job is submitted.
 *
 * <p>Locks are tied to the stable lesson identity {@code (assignmentId, lessonIndex)}, not
 * to {@code draft_lesson.id}: the new draft gets brand new row ids, and the old draft is
 * never modified. {@code assignmentFingerprint} records the teaching assignments as they
 * were when the snapshot was taken, so a change made while the solver runs is detected
 * instead of silently mixing two different states.
 */
public record SourceDraftSnapshot(
        Long draftId,
        String draftName,
        long draftVersion,
        String assignmentFingerprint,
        List<SourceLesson> lessons) {

    public SourceDraftSnapshot {
        lessons = lessons == null ? List.of() : List.copyOf(lessons);
    }

    /**
     * One concrete lesson of an assignment. {@code teacherId} is the draft's current
     * teacher, which may differ from the assignment's teacher after a manual cross-class
     * exchange (ADR-0022); the snapshot keeps that choice.
     */
    public record SourceLesson(
            Long assignmentId,
            int lessonIndex,
            Long classId,
            Long courseId,
            Long teacherId,
            int studentCount,
            Long periodId,
            Long roomId,
            boolean locked) {}
}
