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

    /**
     * 真实操作账号（规格第 1 节）：管理员代发时是管理员账号，教师自助时是教师本人账号。
     * 管理员绑定的教师身份不得顶替它；代发对象另见 requester_teacher_id。
     */
    @Column(name = "operator_user_id")
    private Long operatorUserId;

    /** 调整前实际教师：本次变更前该实例的当前安排教师（可能与基线原教师不同）。 */
    @Column(name = "previous_teacher_id")
    private Long previousTeacherId;

    /** 调整后教师：审批决定后的实际代课教师（批准时写入，可与最初期望不同）。 */
    @Column(name = "final_teacher_id")
    private Long finalTeacherId;

    /**
     * 修订链（票 04）：本申请是对哪一笔已生效申请的再次调整（前驱引用）。
     * 为空表示首次代课/调课。批准时只替代该前驱涉及的当前安排，
     * 驳回/过期/校验失败时前驱安排继续生效；历史双方都保留、可追溯。
     */
    @Column(name = "revises_request_id")
    private Long revisesRequestId;

    /** 修订类型（票 04）：CHANGE_SUBSTITUTE 更换代课人 / RESTORE_BASELINE 恢复基线。 */
    @Column(name = "revision_kind", length = 30)
    private String revisionKind;

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
