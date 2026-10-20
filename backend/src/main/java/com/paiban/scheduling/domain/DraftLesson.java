package com.paiban.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "draft_lesson")
@Getter
@Setter
@NoArgsConstructor
public class DraftLesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "draft_id", nullable = false)
    private Long draftId;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "lesson_index", nullable = false)
    private int lessonIndex;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    @Column(name = "admin_class_id", nullable = false)
    private Long adminClassId;

    @Column(name = "student_count", nullable = false)
    private int studentCount;

    @Column(name = "period_id")
    private Long periodId;

    @Column(name = "room_id")
    private Long roomId;
}
