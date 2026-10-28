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

/**
 * 历史调代日期转换的批次记录（票 02，ADR-0030）。
 *
 * <p>一次经确认的转换生成一条 {@link Status#CONVERTED} 批次，与其对
 * {@code substitution} 行的写入同处一个原子事务——中途失败则整批回滚、
 * 不留痕也不留部分切换；意外失败由独立事务补写 {@link Status#FAILED}
 * 批次与失败原因。阻塞记录不在这里，预检报告实时推导。
 */
@Entity
@Table(name = "substitution_conversion")
@Getter
@Setter
@NoArgsConstructor
public class SubstitutionConversion {

    public static final String CONVERTED = "CONVERTED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "semester_id", nullable = false)
    private Long semesterId;

    @Column(nullable = false, length = 30)
    private String status;

    /** 本批确认时发现的旧格式（lesson_date 为空）生效行总数。 */
    @Column(name = "legacy_total", nullable = false)
    private int legacyTotal;

    @Column(name = "converted_count", nullable = false)
    private int convertedCount;

    @Column(name = "blocked_count", nullable = false)
    private int blockedCount;

    /** 确认门操作者：谁的账号批准了这次数据切换。 */
    @Column(name = "confirmed_by", nullable = false, length = 64)
    private String confirmedBy;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    @Column(name = "confirmation_comment", length = 500)
    private String confirmationComment;

    /** 仅 FAILED 批次：转换失败的原因（独立事务写入，可查）。 */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
