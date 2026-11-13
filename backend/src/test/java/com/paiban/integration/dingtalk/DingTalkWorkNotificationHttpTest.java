package com.paiban.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paiban.common.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The actual work-notification wire contract behind a local HTTP stub. */
class DingTalkWorkNotificationHttpTest {

    private MockRestServiceServer server;
    private HttpDingTalkClient client;

    @BeforeEach
    void configureLocalVendorBoundary() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        SettingsService settings = mock(SettingsService.class);
        when(settings.dingtalkAppKey()).thenReturn("notification-app-key");
        when(settings.dingtalkAppSecret()).thenReturn("notification-app-secret");
        when(settings.dingtalkAgentId()).thenReturn("agent-42");
        when(settings.publicBaseUrl()).thenReturn("https://paiban.example");
        client = new HttpDingTalkClient(settings, builder.build());
    }

    @Test
    void sendsOfficialActionCardContractAndAcceptsNumericZero() {
        expectToken();
        server.expect(requestTo(
                        "https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2?access_token=token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "agent_id":"agent-42",
                          "userid_list":"user-1",
                          "msg":{
                            "msgtype":"action_card",
                            "action_card":{
                              "title":"调代通知",
                              "markdown":"**周三** 已批准",
                              "single_title":"查看详情",
                              "single_url":"https://paiban.example/timetable/1"
                            }
                          }
                        }
                        """))
                .andRespond(withSuccess("""
                        {"errcode":0,"task_id":987654321}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.sendWorkNotification("user-1", "调代通知", "**周三** 已批准", "/timetable/1"))
                .isTrue();
        server.verify();
    }

    @Test
    void successfulHttpStatusWithNonZeroBusinessCodeIsFailure() {
        expectToken();
        expectNotificationResponse("""
                {"errcode":93000,"errmsg":"vendor rejection"}
                """);

        assertThat(client.sendWorkNotification("user-1", "调代通知", "内容", "/timetable/1"))
                .isFalse();
        server.verify();
    }

    @Test
    void numericZeroIsAcceptedWithoutRequiringAPositiveTaskId() {
        expectToken();
        expectNotificationResponse("""
                {"errcode":0,"task_id":0}
                """);

        assertThat(client.sendWorkNotification("user-1", "调代通知", "内容", "/timetable/1"))
                .isTrue();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not-json",
            "{\"task_id\":987654321}",
            "{\"errcode\":\"0\",\"task_id\":987654321}"
    })
    void missingOrMalformedBusinessResponseIsFailure(String response) {
        expectToken();
        expectNotificationResponse(response);

        assertThat(client.sendWorkNotification("user-1", "调代通知", "内容", "/timetable/1"))
                .isFalse();
        server.verify();
    }

    @Test
    void transportFailureReturnsFalseAndLogsOnlyFixedSafeDiagnostic() {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpDingTalkClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            expectToken();
            server.expect(requestTo(
                            "https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2?access_token=token"))
                    .andRespond(withServerError().body(
                            "notification-app-secret access_token=token private-vendor-token user-1"));

            assertThat(client.sendWorkNotification("user-1", "调代通知", "内容", "/timetable/1"))
                    .isFalse();

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("DingTalk work notification delivery failed");
            server.verify();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private void expectToken() {
        server.expect(requestTo("https://api.dingtalk.com/v1.0/oauth2/accessToken"))
                .andRespond(withSuccess("""
                        {"accessToken":"token","expireIn":7200}
                        """, MediaType.APPLICATION_JSON));
    }

    private void expectNotificationResponse(String response) {
        server.expect(requestTo(
                        "https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2?access_token=token"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
