package com.paiban.integration.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FakeFeishuClientTest {

    private final FakeFeishuClient client = new FakeFeishuClient();

    @Test
    void exchangesKnownAuthCodeForUser() {
        FeishuUser user = client.exchangeAuthCode("fake:EMP001");

        assertThat(user.userId()).isEqualTo("fs-001");
        assertThat(user.openId()).isEqualTo("ou-001");
        assertThat(user.name()).isEqualTo("张三");
    }

    @Test
    void rejectsUnknownAuthCode() {
        assertThatThrownBy(() -> client.exchangeAuthCode("fake:UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsSentMessages() {
        FeishuMessage message = new FeishuMessage(
                "ou-001", "fs-001", "标题", "内容", "/link", true, "SUBSTITUTION_EFFECTIVE_ORIGINAL");

        assertThat(client.sendMessage(message)).isTrue();
        assertThat(client.sentMessages()).containsExactly(message);

        client.clearMessages();
        assertThat(client.sentMessages()).isEmpty();
    }

    @Test
    void failAllSimulatesAnUnreachableTenant() {
        FeishuMessage message = new FeishuMessage(
                "ou-001", "fs-001", "标题", "内容", "/link", true, "SUBSTITUTION_EFFECTIVE_ORIGINAL");
        client.failAll(true);

        assertThat(client.sendMessage(message)).isFalse();
        assertThat(client.sentMessages()).isEmpty();

        client.failAll(false);
        assertThat(client.sendMessage(message)).isTrue();
        assertThat(client.sentMessages()).containsExactly(message);
    }
}
