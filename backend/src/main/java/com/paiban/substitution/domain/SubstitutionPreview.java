package com.paiban.substitution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 管理调代工作台的预览凭证（规格第 4/5 节）。
 *
 * <p>凭证绑定操作者、来源实例、整份快照哈希、选定代课人与过期时间，是短期的
 * 「可保存试算结果」，不是待审批申请：创建与消费它都不改变课表、不发通知。
 * 生存期 ≤30 分钟且不晚于开课截止（服务端返回权威截止时间）。
 *
 * <p>幂等语义：同一凭证的相同动作 + 相同参数重试返回第一次结果；
 * 参数改变不复用已消费凭证。
 */
@Entity
@Table(name = "substitution_preview")
@Getter
@Setter
@NoArgsConstructor
public class SubstitutionPreview {

    public static final String ACTIVE = "ACTIVE";
    public static final String CONSUMED = "CONSUMED";
    public static final String EXPIRED = "EXPIRED";

    public static final String ACTION_SUBMIT_APPROVAL = "SUBMIT_APPROVAL";
    public static final String ACTION_CONFIRM = "CONFIRM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    /** 来源实例的授课日期（实例身份 = 学期 + 基线条目 + 该日期）。 */
    @Column(name = "lesson_date", nullable = false)
    private LocalDate lessonDate;

    @Column(name = "substitute_teacher_id", nullable = false)
    private Long substituteTeacherId;

    /** 预览发起时的当前实际教师（快照计算），确认后写入申请的调整前教师。 */
    @Column(name = "previous_teacher_id", nullable = false)
    private Long previousTeacherId;

    /**
     * 修订链（票 04）：预览针对的已生效基准申请；为空表示首次代课。
     * 凭证绑定「快照哈希 + 基准申请」，确认时复核——基准被别的修订替代时明确冲突。
     */
    @Column(name = "revises_request_id")
    private Long revisesRequestId;

    /** 修订类型（票 04）：CHANGE_SUBSTITUTE / RESTORE_BASELINE；首次代课为空。 */
    @Column(name = "revision_kind", length = 30)
    private String revisionKind;

    /** 绑定操作者账号；确认时校验是同一操作者，管理员绑定的教师身份不参与判定。 */
    @Column(name = "operator_user_id", nullable = false)
    private Long operatorUserId;

    @Column(name = "snapshot_hash", nullable = false, length = 64)
    private String snapshotHash;

    @Column(nullable = false, length = 20)
    private String status = ACTIVE;

    /** 消费方式：SUBMIT_APPROVAL 或 CONFIRM。 */
    @Column(name = "action_type", length = 20)
    private String actionType;

    /** 消费时的动作参数哈希，用于「参数改变不复用已消费凭证」。 */
    @Column(name = "action_hash", length = 64)
    private String actionHash;

    /** 第一次成功操作的结果 JSON（requestId + status），幂等重放时原样返回。 */
    @Column(name = "result_json", length = 2000)
    private String resultJson;

    /** 预览时录入的代课原因；提交审批/亲自确认创建申请时沿用。 */
    @Column(length = 500)
    private String reason;

    /** 开课截止（服务端权威计算）：确认不得晚于它，生存期也被它截断。 */
    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    /** 凭证过期时间 = min(创建 + 30 分钟, 开课截止)。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /**
     * 幂等重放：凭证已按「相同动作 + 相同参数」消费过时返回第一次结果。
     * 重放不受 30 分钟过期限制——过期只约束消费未用凭证；网络超时后的迟来重试
     * 同样要拿到第一次结果。未消费或参数不同都不重放。
     */
    public Optional<String> replay(String actionType, String actionHash) {
        if (!CONSUMED.equals(status)) {
            return Optional.empty();
        }
        if (actionType != null && actionType.equals(this.actionType)
                && actionHash != null && actionHash.equals(this.actionHash)) {
            return Optional.ofNullable(resultJson);
        }
        return Optional.empty();
    }

    /** 已消费的凭证收到不同参数时返回 true：调用方应收到明确冲突而非第一次结果。 */
    public boolean conflictsWith(String actionType, String actionHash) {
        return CONSUMED.equals(status)
                && !(actionType != null && actionType.equals(this.actionType)
                        && actionHash != null && actionHash.equals(this.actionHash));
    }

    /** 消费凭证；过期凭证拒绝消费（ IllegalState，服务层转成用户可见的过期提示）。 */
    public void consume(String actionType, String actionHash, String resultJson, Instant now) {
        if (expiresAt != null && now.isAfter(expiresAt)) {
            throw new IllegalStateException("预览凭证已过期，请重新预览");
        }
        this.status = CONSUMED;
        this.actionType = actionType;
        this.actionHash = actionHash;
        this.resultJson = resultJson;
        this.consumedAt = now;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
