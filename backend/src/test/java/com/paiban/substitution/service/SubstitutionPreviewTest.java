package com.paiban.substitution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paiban.substitution.domain.SubstitutionPreview;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 预览凭证的幂等语义（规格第 5 节）：同一确认凭证的相同重试返回第一次结果；
 * 参数改变不复用已消费凭证。
 */
class SubstitutionPreviewTest {

    private static SubstitutionPreview active() {
        SubstitutionPreview voucher = new SubstitutionPreview();
        voucher.setId(1L);
        voucher.setSemesterId(10L);
        voucher.setEntryId(20L);
        voucher.setLessonDate(LocalDate.of(2025, 9, 5));
        voucher.setSubstituteTeacherId(30L);
        voucher.setOperatorUserId(40L);
        voucher.setPreviousTeacherId(50L);
        voucher.setSnapshotHash("hash-a");
        voucher.setStatus(SubstitutionPreview.ACTIVE);
        // 有效期相对真实当前时间 30 分钟，保证「未过期」用例不随日历漂移。
        voucher.setExpiresAt(Instant.now().plusSeconds(1800));
        return voucher;
    }

    @Test
    void activeVoucherIsReplayable() {
        SubstitutionPreview voucher = active();
        assertThat(voucher.replay("CONFIRM", "body-hash")).isEmpty();
        assertThat(voucher.conflictsWith("CONFIRM", "body-hash")).isFalse();
    }

    @Test
    void consumedVoucherReplaysTheFirstResultForIdenticalParams() {
        SubstitutionPreview voucher = active();
        voucher.consume("CONFIRM", "body-hash", "{\"requestId\":99,\"status\":\"EFFECTIVE\"}",
                Instant.parse("2025-09-05T07:00:00Z"));

        assertThat(voucher.getStatus()).isEqualTo(SubstitutionPreview.CONSUMED);
        assertThat(voucher.replay("CONFIRM", "body-hash"))
                .contains("{\"requestId\":99,\"status\":\"EFFECTIVE\"}");
        assertThat(voucher.getResultJson()).contains("99");
    }

    @Test
    void consumedVoucherRejectsDifferentParams() {
        SubstitutionPreview voucher = active();
        voucher.consume("CONFIRM", "body-hash", "{\"requestId\":99}", Instant.now());

        // 参数改变（不同动作或不同请求体）不复用已消费凭证。
        assertThat(voucher.replay("CONFIRM", "other-hash")).isEmpty();
        assertThat(voucher.conflictsWith("CONFIRM", "other-hash")).isTrue();
        assertThat(voucher.replay("SUBMIT_APPROVAL", "body-hash")).isEmpty();
        assertThat(voucher.conflictsWith("SUBMIT_APPROVAL", "body-hash")).isTrue();
    }

    @Test
    void expiredVoucherCannotBeConsumed() {
        SubstitutionPreview voucher = active();
        voucher.setExpiresAt(Instant.parse("2025-09-05T07:30:00Z"));

        assertThatThrownBy(() -> voucher.consume("CONFIRM", "body-hash", "{}",
                Instant.parse("2025-09-05T07:31:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("过期");
    }

    @Test
    void consumedVoucherReplaysTheFirstResultEvenAfterExpiry() {
        SubstitutionPreview voucher = active();
        voucher.consume("CONFIRM", "body-hash", "{\"requestId\":99,\"status\":\"EFFECTIVE\"}",
                Instant.now());
        // 过期只约束消费未用凭证：网络超时后迟来的重试仍拿到第一次结果。
        voucher.setExpiresAt(Instant.now().minusSeconds(60));

        assertThat(voucher.replay("CONFIRM", "body-hash"))
                .contains("{\"requestId\":99,\"status\":\"EFFECTIVE\"}");
    }
}
