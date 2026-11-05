package com.paiban.scheduling.constraint;

import com.paiban.scheduling.solver.ConstraintSettings;
import java.util.Map;

/**
 * Immutable reference data for one validation run: periods, courses, teachers,
 * classes and the constraint rules. Both the draft validator and the published-
 * timetable verifier build one of these, so the rule definitions live in exactly
 * one place.
 */
public record ConstraintContext(Map<Long, PeriodView> periods,
                                Map<Long, CourseView> courses,
                                Map<Long, TeacherView> teachers,
                                Map<Long, ClassView> classes,
                                ConstraintSettings settings) {

    public record PeriodView(long id, int dayOfWeek, int periodNo) {}

    public record CourseView(long id, String name) {}

    public record TeacherView(long id, String name, String subject) {}

    public record ClassView(long id, String name, int studentCount, Long headTeacherId) {}
}
