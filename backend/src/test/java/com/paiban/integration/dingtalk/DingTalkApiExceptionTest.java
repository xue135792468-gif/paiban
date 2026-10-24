package com.paiban.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DingTalkApiExceptionTest {

    @Test
    void carriesErrcodeAndSubCodeInTheMessage() {
        DingTalkApiException error = new DingTalkApiException("拉取部门用户", 60003, 0, "部门不存在");

        assertThat(error.errorCode()).isEqualTo(60003);
        assertThat(error.subCode()).isZero();
        assertThat(error.getMessage()).contains("errcode=60003").contains("部门不存在");
    }

    @Test
    void explainsAnIpWhitelistRejection() {
        DingTalkApiException error = new DingTalkApiException("拉取部门用户", 88, 60020, "ip not in whitelist");

        assertThat(error.isIpWhitelist()).isTrue();
        assertThat(error.getMessage())
                .contains("errcode=88")
                .contains("sub_code=60020")
                .contains("IP 白名单");
    }

    @Test
    void recognisesAnUnavailableDepartment() {
        assertThat(new DingTalkApiException("拉取部门用户", 41, 0, null).isDepartmentUnavailable()).isTrue();
        assertThat(new DingTalkApiException("拉取部门用户", 60003, 0, null).isDepartmentUnavailable()).isFalse();
    }

    @Test
    void checkIgnoresSuccessfulResponses() {
        DingTalkApiException.check(Map.of("errcode", 0, "errmsg", "ok"), "测试");
        DingTalkApiException.check(null, "测试");
    }

    @Test
    void checkTurnsAnErrorBodyIntoAStructuredException() {
        assertThatThrownBy(() -> DingTalkApiException.check(
                        Map.of("errcode", 88, "sub_code", "60020", "errmsg", "ip not in whitelist"), "拉取部门用户"))
                .isInstanceOfSatisfying(DingTalkApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(88);
                    assertThat(error.subCode()).isEqualTo(60020);
                    assertThat(error.isIpWhitelist()).isTrue();
                });
    }

    @Test
    void redactsAccessTokensFromTransportErrors() {
        assertThat(DingTalkApiException.redact(
                        "I/O error on POST request for \"https://oapi.dingtalk.com/topapi/v2/user/list?access_token=abc123&x=1\": timeout"))
                .isEqualTo("I/O error on POST request for \"https://oapi.dingtalk.com/topapi/v2/user/list?access_token=***&x=1\": timeout");
        assertThat(DingTalkApiException.redact(null)).isNull();
        assertThat(DingTalkApiException.redact("no token here")).isEqualTo("no token here");
    }
}
