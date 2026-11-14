package com.paiban.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class HttpDingTalkProbeClientTest {

    private static final String PROBE_TOKEN = "probe-token-value";
    private static final String TOKEN_URL = "https://api.dingtalk.com/v1.0/oauth2/accessToken";
    private static final String ROOT_URL =
            "https://oapi.dingtalk.com/topapi/v2/department/get?access_token=" + PROBE_TOKEN;

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void probeUsesUnsavedCredentialsThenTheFreshTokenForRootDepartment() {
        expectFreshToken();
        expectRootRequest()
                .andRespond(withSuccess("""
                        {"errcode":0,"result":{"dept_id":1,"name":"根部门","parent_id":null}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client().probe("unsaved-key", "unsaved-secret"))
                .isEqualTo(DingTalkProbeCode.OK);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"accessToken\":\"\"}", "{\"accessToken\":\"   \"}"})
    void missingOrBlankTokenIsAnInvalidResponse(String tokenResponse) {
        expectToken(tokenResponse);

        assertThat(client().probe("unsaved-key", "unsaved-secret"))
                .isEqualTo(DingTalkProbeCode.INVALID_RESPONSE);
        server.verify();
    }

    @Test
    void vendorTokenRejectionIsClassifiedWithoutGuessingWhy() {
        expectToken("""
                {"code":"invalidClientIdOrSecret","message":"vendor detail"}""");

        assertThat(client().probe("unsaved-key", "unsaved-secret"))
                .isEqualTo(DingTalkProbeCode.TOKEN_REJECTED);
        server.verify();
    }

    @Test
    void tokenEndpointClientErrorIsATokenRejection() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"vendor body\"}"));

        assertThat(client().probe("unsaved-key", "unsaved-secret"))
                .isEqualTo(DingTalkProbeCode.TOKEN_REJECTED);
        server.verify();
    }

    @Test
    void tokenEndpointServerErrorIsNotReportedAsOK() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client().probe("unsaved-key", "unsaved-secret"))
                .isEqualTo(DingTalkProbeCode.INVALID_RESPONSE);
        server.verify();
    }

    @Test
    void departmentErrorCodeIsADepartmentRejection() {
        expectTokenAndRoot("""
                {"errcode":60011,"errmsg":"no permission"}""");

        assertThat(client().probe("unsaved-key", "unsaved-secret"))
                .isEqualTo(DingTalkProbeCode.DEPARTMENT_REJECTED);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"errcode\":0}",
            "{\"errcode\":0,\"result\":{}}",
            "{\"errcode\":0.5,\"result\":{\"dept_id\":1}}",
            "{\"errcode\":0,\"result\":{\"dept_id\":1.5}}",
            "{\"errcode\":0,\"result\":{\"dept_id\":2,\"name\":\"子部门\"}}",
            "{\"errcode\":0,\"result\":{\"dept_id\":\"1\",\"name\":\"根部门\"}}",
            "{\"result\":{\"dept_id\":1,\"name\":\"根部门\"}}"
    })
    void successfulErrcodeAloneCannotPassOrMalformedRootFail(String rootResponse) {
        expectTokenAndRoot(rootResponse);

        assertThat(client().probe("unsaved-key", "unsaved-secret"))
                .isEqualTo(DingTalkProbeCode.INVALID_RESPONSE);
        server.verify();
    }

    @Test
    void transportFailureAtTokenExchangeIsANetworkError() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withException(new SocketTimeoutException("connect timed out")));

        assertThat(client().probe("unsaved-key", "unsaved-secret"))
                .isEqualTo(DingTalkProbeCode.NETWORK_ERROR);
        server.verify();
    }

    @Test
    void transportFailureAtDepartmentReadIsANetworkError() {
        expectFreshToken();
        expectRootRequest()
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThat(client().probe("unsaved-key", "unsaved-secret"))
                .isEqualTo(DingTalkProbeCode.NETWORK_ERROR);
        server.verify();
    }

    @Test
    void vendorSecretsNeverEnterApplicationLogs(CapturedOutput output) {
        expectToken("""
                {"code":"invalidClientIdOrSecret","message":"unsaved-secret extra detail"}""");

        assertThat(client().probe("unsaved-key", "unsaved-secret"))
                .isEqualTo(DingTalkProbeCode.TOKEN_REJECTED);
        assertThat(output).doesNotContain("unsaved-secret");
        assertThat(output).doesNotContain("unsaved-key");
        server.verify();
    }

    private void expectToken(String response) {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"appKey":"unsaved-key","appSecret":"unsaved-secret"}""", false))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private void expectTokenAndRoot(String rootResponse) {
        expectFreshToken();
        expectRootRequest()
                .andRespond(withSuccess(rootResponse, MediaType.APPLICATION_JSON));
    }

    private void expectFreshToken() {
        expectToken("{\"accessToken\":\"" + PROBE_TOKEN + "\",\"expireIn\":7200}");
    }

    private org.springframework.test.web.client.ResponseActions expectRootRequest() {
        return server.expect(requestTo(ROOT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form(Map.of("dept_id", "1"))));
    }

    private LinkedMultiValueMap<String, String> form(Map<String, String> values) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        values.forEach(form::add);
        return form;
    }

    private HttpDingTalkProbeClient client() {
        return new HttpDingTalkProbeClient(builder.build());
    }
}
