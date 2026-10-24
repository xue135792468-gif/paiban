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

@Entity
@Table(name = "substitution_request")
@Getter
@Setter
@NoArgsConstructor
public class SubstitutionRequest {

    public static final String SUBMITTED = "SUBMITTED";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String EFFECTIVE = "EFFECTIVE";
    public static final String CANCELLED = "CANCELLED";

    public static final String TYPE_SWAP = "SWAP";
    public static final String TYPE_COVER = "COVER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    @Column(name = "admin_class_id", nullable = false)
    private Long adminClassId;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "original_teacher_id", nullable = false)
    private Long originalTeacherId;

    @Column(name = "requester_teacher_id")
    private Long requesterTeacherId;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "period_id", nullable = false)
    private Long periodId;

    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "period_no", nullable = false)
    private int periodNo;

    @Column(length = 500)
    private String reason;

    @Column(name = "expected_substitute_teacher_id")
    private Long expectedSubstituteTeacherId;

    @Column(nullable = false, length = 20)
    private String type = TYPE_COVER;

    @Column(name = "target_period_id")
    private Long targetPeriodId;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "approval_mode", nullable = false, length = 20)
    private String approvalMode;

    @Column(name = "approver_id")
    private Long approverId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_comment", length = 500)
    private String approvalComment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
