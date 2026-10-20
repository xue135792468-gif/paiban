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
    private String requiredRoomType;
    private boolean allowConsecutive;
    private int studentCount;
    private int lessonIndex;

    @PlanningVariable(valueRangeProviderRefs = "periodRange")
    private PeriodSlot period;

    @PlanningVariable(valueRangeProviderRefs = "roomRange")
    private RoomSlot room;

    public Lesson(Long id, Long assignmentId, Long courseId, String courseName, Long teacherId,
                  Long adminClassId, Long headTeacherId, String teacherSubject, String requiredRoomType,
                  boolean allowConsecutive, int studentCount, int lessonIndex) {
        this.id = id;
        this.assignmentId = assignmentId;
        this.courseId = courseId;
        this.courseName = courseName;
        this.teacherId = teacherId;
        this.adminClassId = adminClassId;
        this.headTeacherId = headTeacherId;
        this.teacherSubject = teacherSubject;
        this.requiredRoomType = requiredRoomType;
        this.allowConsecutive = allowConsecutive;
        this.studentCount = studentCount;
        this.lessonIndex = lessonIndex;
    }
}
