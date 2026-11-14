package com.paiban.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import com.paiban.common.settings.SettingsService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** The actual H5 HTTP protocol, with a local vendor boundary and no real credentials. */
class DingTalkH5IdentityHttpTest {

    private MockRestServiceServer server;
    private HttpDingTalkClient client;

    @BeforeEach
    void configureLocalVendorBoundary() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        SettingsService settings = mock(SettingsService.class);
        when(settings.dingtalkAppKey()).thenReturn("h5-app-key");
        when(settings.dingtalkAppSecret()).thenReturn("h5-app-secret");
        client = new HttpDingTalkClient(settings, builder.build());
    }

    @Test
    void exchangesTheOneUseH5CodeForTheVendorVerifiedUserId() {
        expectToken();
        server.expect(requestTo("https://oapi.dingtalk.com/topapi/v2/user/getuserinfo"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(form(Map.of("code", "one-use-code", "access_token", "token"))))
                .andRespond(withSuccess("""
                        {"errcode":0,"result":{"userid":"verified-user"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.exchangeAuthCode("one-use-code")).isEqualTo("verified-user");
        server.verify();
    }

    private void expectToken() {
        server.expect(requestTo("https://api.dingtalk.com/v1.0/oauth2/accessToken"))
                .andRespond(withSuccess("""
                        {"accessToken":"token","expireIn":7200}
                        """, MediaType.APPLICATION_JSON));
    }

    @Test
    void readsTheVerifiedUserIdAndNameFromTheUserDetailEndpoint() {
        expectToken();
        server.expect(requestTo("https://oapi.dingtalk.com/topapi/v2/user/get"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(form(Map.of(
                        "userid", "verified-user", "language", "zh_CN", "access_token", "token"))))
                .andRespond(withSuccess("""
                        {"errcode":0,"result":{"userid":"verified-user","name":"真实钉钉姓名"}}
                        """, MediaType.APPLICATION_JSON));

        var user = client.getUser("verified-user");
        assertThat(user.userId()).isEqualTo("verified-user");
        assertThat(user.name()).isEqualTo("真实钉钉姓名");
        server.verify();
    }

    private LinkedMultiValueMap<String, String> form(Map<String, String> values) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        values.forEach(form::add);
        return form;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"result\":{\"userid\":\"untrusted-user\"}}",
            "{\"errcode\":\"invalid\",\"result\":{\"userid\":\"untrusted-user\"}}",
            "{\"errcode\":400,\"errmsg\":\"h5-app-secret one-use-code private-vendor-token\"}"
    })
    void invalidVendorStatusCannotAuthenticateOrEchoCredentials(String response) {
        expectToken();
        server.expect(requestTo("https://oapi.dingtalk.com/topapi/v2/user/getuserinfo"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.exchangeAuthCode("one-use-code"))
                .isInstanceOf(DingTalkApiException.class)
                .hasMessageNotContaining("h5-app-secret")
                .hasMessageNotContaining("one-use-code")
                .hasMessageNotContaining("private-vendor-token");
        server.verify();
    }

    @Test
    void transportFailureDoesNotExposeTheVendorResponseBody() {
        expectToken();
        server.expect(requestTo("https://oapi.dingtalk.com/topapi/v2/user/getuserinfo"))
                .andRespond(withServerError().body("h5-app-secret one-use-code private-vendor-token"));

        assertThatThrownBy(() -> client.exchangeAuthCode("one-use-code"))
                .isInstanceOf(DingTalkApiException.class)
                .hasMessageNotContaining("h5-app-secret")
                .hasMessageNotContaining("one-use-code")
                .hasMessageNotContaining("private-vendor-token");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"errcode\":0,\"result\":{}}",
            "{\"errcode\":0,\"result\":{\"userid\":123}}",
            "{\"errcode\":0,\"result\":{\"userid\":\" \"}}"
    })
    void missingOrUntypedVerifiedUserIdsAreRejected(String response) {
        expectToken();
        server.expect(requestTo("https://oapi.dingtalk.com/topapi/v2/user/getuserinfo"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.exchangeAuthCode("one-use-code"))
                .isInstanceOf(DingTalkApiException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"errcode\":0,\"result\":{\"userid\":\"different-user\",\"name\":\"某人\"}}",
            "{\"errcode\":0,\"result\":{\"userid\":\"verified-user\"}}",
            "{\"errcode\":0,\"result\":{\"userid\":\"verified-user\",\"name\":123}}"
    })
    void unconfirmedNamesOrMismatchingUserDetailsCannotBecomeAnIdentity(String response) {
        expectToken();
        server.expect(requestTo("https://oapi.dingtalk.com/topapi/v2/user/get"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getUser("verified-user"))
                .isInstanceOf(DingTalkApiException.class);
        server.verify();
    }
}
