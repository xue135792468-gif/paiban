package com.paiban.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paiban.integration.domain.DingUser;
import org.junit.jupiter.api.Test;

class FakeDingTalkAcceptanceIdentityTest {
    @Test
    void isolatedAcceptanceIdentityRoundTripsWithoutChangingTheDirectory() {
        FakeDingTalkClient client = new FakeDingTalkClient();
        String uid = client.exchangeAuthCode("fake:acceptance:chain_08-A");
        assertThat(uid).isEqualTo("dd-acceptance-chain_08-A");
        assertThat(client.getUser(uid)).isEqualTo(new DingUser(
                uid, "验收申请人-chain_08-A", null, null, "ACC-chain_08-A"));
        assertThat(client.listUsers(1, 0, 100).users())
                .extracting(DingUser::userId).containsExactly("dd-001", "dd-002");
        assertThat(client.exchangeAuthCode("fake:EMP001")).isEqualTo("dd-001");
        assertThat(client.exchangeAuthCode("fake:EMP002")).isEqualTo("dd-002");
        assertThatThrownBy(() -> client.exchangeAuthCode("fake:UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedSyntheticIdentitiesAreRejectedAtBothBoundaries() {
        FakeDingTalkClient client = new FakeDingTalkClient();
        for (String suffix : new String[] {"", "with space", "中文", "x".repeat(41), "a/b"}) {
            assertThatThrownBy(() -> client.exchangeAuthCode("fake:acceptance:" + suffix))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.getUser("dd-acceptance-" + suffix))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
