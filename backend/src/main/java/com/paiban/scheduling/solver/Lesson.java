package com.paiban.scheduling.solver;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One lesson instance of an assignment: the unit the solver schedules. */
@PlanningEntity
@Getter
@Setter
@NoArgsConstructor
public class Lesson {

    @PlanningId
    private Long id;

    private Long assignmentId;
    private Long courseId;
    private String courseName;
    private Long teacherId;
    private Long adminClassId;
    private Long headTeacherId;
    private String teacherSubject;
    private boolean allowConsecutive;
    private int studentCount;
    private int lessonIndex;

    @PlanningVariable(valueRangeProviderRefs = "periodRange", allowsUnassigned = true)
    private PeriodSlot period;

    /**
     * The period this lesson must keep because the source draft marked it locked, or
     * {@code null} when it is free to move. It is enforced by a hard constraint, so it
     * is not merely a warm-start hint. The teacher is a fact (not a planning variable),
     * so locking the teacher is done by building the lesson with the source draft's
     * current teacher.
     */
    private Long lockedPeriodId;

    /** Whether the new draft must inherit the lock marker of this lesson. */
    private boolean locked;

    public Lesson(Long id, Long assignmentId, Long courseId, String courseName, Long teacherId,
                  Long adminClassId, Long headTeacherId, String teacherSubject,
                  boolean allowConsecutive, int studentCount, int lessonIndex) {
        this.id = id;
        this.assignmentId = assignmentId;
        this.courseId = courseId;
        this.courseName = courseName;
        this.teacherId = teacherId;
        this.adminClassId = adminClassId;
        this.headTeacherId = headTeacherId;
        this.teacherSubject = teacherSubject;
        this.allowConsecutive = allowConsecutive;
        this.studentCount = studentCount;
        this.lessonIndex = lessonIndex;
    }
}
