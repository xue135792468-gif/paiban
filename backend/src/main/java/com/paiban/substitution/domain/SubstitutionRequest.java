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
    public static final String EFFECTIVE = "EFFECTIVE";
    /** 票 05 恢复审批链后的终态：管理员驳回（理由必填）。 */
    public static final String REJECTED = "REJECTED";
    /** 申请人自己在批准前撤回。 */
    public static final String WITHDRAWN = "WITHDRAWN";
    /** 开课后仍无人处理，惰性作废；不会在课后突然生效。 */
    public static final String EXPIRED = "EXPIRED";
    /** 票 15 及以前的旧终态，仅用于历史展示，不等同于撤回申请。 */
    public static final String CANCELLED = "CANCELLED";

    public static final String TYPE_SWAP = "SWAP";
    public static final String TYPE_COVER = "COVER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    /**
     * 基线条目引用。发布撤回删除 timetable_entry 后由外键置空；申请自身保留为历史，
     * 班级/课程/节次等快照字段仍可展示。
     */
    @Column(name = "entry_id")
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

    /** 调整后教师：本次变更后实际代课的教师。 */
    @Column(name = "final_teacher_id")
    private Long finalTeacherId;

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

    /** 票 05：管理端调课的明确目标实际日期；旧路径为空，按目标课节的星期推算。 */
    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(nullable = false, length = 30)
    private String status;

    /** 票 05：真实批准（或驳回）的管理员账号；待审批与撤回时为空。 */
    @Column(name = "approver_user_id")
    private Long approverUserId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** 票 05：审批意见；驳回时即驳回理由（必填）。 */
    @Column(name = "approval_comment", length = 500)
    private String approvalComment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
