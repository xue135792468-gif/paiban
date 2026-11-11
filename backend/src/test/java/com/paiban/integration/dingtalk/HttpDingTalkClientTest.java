package com.paiban.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.paiban.common.settings.SettingsService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 票 04-A：真实 HTTP adapter 的坏响应契约。
 *
 * <p>DingTalk 协议失败（缺少 {@code result} / {@code list} / 带 {@code has_more} 却没有游标）
 * 必须抛错关闭，绝不能伪装成合法的空目录；结构完整的空列表仍然合法。
 */
class HttpDingTalkClientTest {

    private static final String TOKEN_JSON = """
            {"accessToken":"token","expireIn":7200}""";
    private static final String NO_RESULT = """
            {"errcode":0}""";
    private static final String NO_LIST = """
            {"errcode":0,"result":{"has_more":false}}""";
    private static final String MORE_WITHOUT_CURSOR = """
            {"errcode":0,"result":{"list":[],"has_more":true}}""";
    private static final String EMPTY_DEPARTMENTS = """
            {"errcode":0,"result":[]}""";
    private static final String EMPTY_PAGE = """
            {"errcode":0,"result":{"list":[],"has_more":false}}""";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private SettingsService settings;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        settings = mock(SettingsService.class);
        when(settings.dingtalkAppKey()).thenReturn("app-key");
        when(settings.dingtalkAppSecret()).thenReturn("app-secret");
    }

    @Test
    void userPageWithoutResultIsAProtocolFailure() {
        expectToken();
        server.expect(requestTo(containsString("/topapi/v2/user/list")))
                .andRespond(withSuccess(NO_RESULT, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().listUsers(1, 0, 100))
                .isInstanceOf(DingTalkApiException.class)
                .hasMessageContaining("结构");
        server.verify();
    }

    @Test
    void userPageWithoutListIsAProtocolFailure() {
        expectToken();
        server.expect(requestTo(containsString("/topapi/v2/user/list")))
                .andRespond(withSuccess(NO_LIST, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().listUsers(1, 0, 100))
                .isInstanceOf(DingTalkApiException.class)
                .hasMessageContaining("结构");
        server.verify();
    }

    @Test
    void userPageWithMoreButNoCursorIsAProtocolFailure() {
        expectToken();
        server.expect(requestTo(containsString("/topapi/v2/user/list")))
                .andRespond(withSuccess(MORE_WITHOUT_CURSOR, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().listUsers(1, 0, 100))
                .isInstanceOf(DingTalkApiException.class)
                .hasMessageContaining("结构");
        server.verify();
    }

    @Test
    void departmentListWithoutResultIsAProtocolFailure() {
        expectToken();
        server.expect(requestTo(containsString("/topapi/v2/department/listsub")))
                .andRespond(withSuccess(NO_RESULT, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().listSubDepartments(1))
                .isInstanceOf(DingTalkApiException.class)
                .hasMessageContaining("结构");
        server.verify();
    }

    @Test
    void wellFormedEmptyPagesStayValid() {
        expectToken();
        server.expect(requestTo(containsString("/topapi/v2/department/listsub")))
                .andRespond(withSuccess(EMPTY_DEPARTMENTS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/topapi/v2/user/list")))
                .andRespond(withSuccess(EMPTY_PAGE, MediaType.APPLICATION_JSON));

        HttpDingTalkClient client = client();
        assertThat(client.listSubDepartments(1)).isEmpty();
        DingUserPage page = client.listUsers(1, 0, 100);
        assertThat(page.users()).isEqualTo(List.of());
        assertThat(page.hasMore()).isFalse();
        server.verify();
    }

    private void expectToken() {
        server.expect(requestTo(containsString("/v1.0/oauth2/accessToken")))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
    }

    private HttpDingTalkClient client() {
        return new HttpDingTalkClient(settings, builder.build());
    }
}
