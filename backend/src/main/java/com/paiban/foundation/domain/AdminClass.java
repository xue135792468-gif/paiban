package com.paiban.foundation.domain;

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
@Table(name = "admin_class")
@Getter
@Setter
@NoArgsConstructor
public class AdminClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(name = "grade_id", nullable = false)
    private Long gradeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "head_teacher_id")
    private Long headTeacherId;

    @Column(name = "student_count", nullable = false)
    private int studentCount = 45;
}
