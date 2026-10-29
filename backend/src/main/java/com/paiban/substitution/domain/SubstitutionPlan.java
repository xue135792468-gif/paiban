package com.paiban.substitution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One generated candidate swap cycle for a request. */
@Entity
@Table(name = "substitution_plan")
@Getter
@Setter
@NoArgsConstructor
public class SubstitutionPlan {

    public static final String PROPOSED = "PROPOSED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 管理端预览方案（票 05）在选定前没有申请；提交审批/亲自确认时才关联。
     * 普通教师路径生成方案时申请已存在，该列不会为空。
     */
    @Column(name = "request_id")
    private Long requestId;

    /**
     * 管理端试算方案的操作者（评审修复 P2b）：重新生成候选只清理本人上一批，
     * 并发管理员的候选互不干扰。教师自助方案与历史方案该列为空。
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(name = "snapshot_hash", nullable = false, length = 64)
    private String snapshotHash;

    @Column(name = "ranking_preset", nullable = false, length = 30)
    private String rankingPreset;

    @Column(nullable = false, length = 20)
    private String status = PROPOSED;

    @Column(name = "impact_days", nullable = false)
    private int impactDays;

    @Column(name = "impact_teachers", nullable = false)
    private int impactTeachers;

    @Column(name = "soft_penalty", nullable = false)
    private int softPenalty;

    @Column(name = "cycle_length", nullable = false)
    private int cycleLength;

    @Column(name = "same_week", nullable = false)
    private boolean sameWeek = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt = Instant.now();

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by", length = 64)
    private String approvedBy;
}
