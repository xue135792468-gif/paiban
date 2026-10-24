package com.paiban.substitution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One lesson moved by a {@link SubstitutionPlan}. */
@Entity
@Table(name = "substitution_plan_move")
@Getter
@Setter
@NoArgsConstructor
public class SubstitutionPlanMove {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "from_period_id", nullable = false)
    private Long fromPeriodId;

    @Column(name = "from_day_of_week", nullable = false)
    private int fromDayOfWeek;

    @Column(name = "from_period_no", nullable = false)
    private int fromPeriodNo;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_period_id", nullable = false)
    private Long toPeriodId;

    @Column(name = "to_day_of_week", nullable = false)
    private int toDayOfWeek;

    @Column(name = "to_period_no", nullable = false)
    private int toPeriodNo;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "move_order", nullable = false)
    private int moveOrder;
}
