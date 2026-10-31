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

    @PlanningVariable(valueRangeProviderRefs = "periodRange", allowsUnassigned = true)
    private PeriodSlot period;

    @PlanningVariable(valueRangeProviderRefs = "roomRange", allowsUnassigned = true)
    private RoomSlot room;

    /**
     * The class's own classroom, resolved from the fixed-room naming convention. Not a
     * planning variable: it is a fact the solver must keep when the course has no special
     * room requirement. Null means "no fixed room known", and the room stays free.
     */
    private RoomSlot fixedRoom;

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
