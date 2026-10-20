package com.paiban.substitution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Append-only overlay event: the baseline entry is never modified. */
@Entity
@Table(name = "substitution")
@Getter
@Setter
@NoArgsConstructor
public class Substitution {

    public static final String EFFECTIVE = "EFFECTIVE";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "original_teacher_id", nullable = false)
    private Long originalTeacherId;

    @Column(name = "substitute_teacher_id", nullable = false)
    private Long substituteTeacherId;

    @Column(name = "new_room_id")
    private Long newRoomId;

    @Column(name = "new_period_id")
    private Long newPeriodId;

    @Column(name = "new_day_of_week")
    private Integer newDayOfWeek;

    @Column(name = "new_period_no")
    private Integer newPeriodNo;

    @Column(nullable = false, length = 20)
    private String status = EFFECTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
