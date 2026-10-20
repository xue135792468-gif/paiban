package com.paiban.integration.dingtalk;

import com.paiban.common.settings.SettingsService;
import com.paiban.integration.domain.DingUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Real DingTalk open-platform client. Requires a reachable DingTalk tenant and
 * valid credentials; when integration is disabled the callers never invoke it.
 */
@Component
@ConditionalOnProperty(name = "paiban.integration.dingtalk.fake", havingValue = "false", matchIfMissing = true)
public class HttpDingTalkClient implements DingTalkClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDingTalkClient.class);
    private static final String API = "https://api.dingtalk.com";
    private static final String OAPI = "https://oapi.dingtalk.com";

    private final SettingsService settings;
    private final RestClient restClient = RestClient.create();

    public HttpDingTalkClient(SettingsService settings) {
        this.settings = settings;
    }

    @Override
    public String exchangeAuthCode(String authCode) {
        Map<?, ?> response = restClient.post()
                .uri(API + "/v1.0/oauth/users/auth")
                .header("x-acs-dingtalk-access-token", accessToken())
                .body(Map.of("authCode", authCode))
                .retrieve()
                .body(Map.class);
        Object userId = response == null ? null : response.get("userId");
        if (userId == null) {
            throw new IllegalStateException("钉钉未返回 userId");
        }
        return userId.toString();
    }

    @Override
    public List<DingUser> listUsers() {
        List<DingUser> users = new ArrayList<>();
        long cursor = 0;
        while (true) {
            Map<?, ?> response = restClient.post()
                    .uri(OAPI + "/topapi/v2/user/list?access_token=" + accessToken())
                    .body(Map.of("dept_id", 1, "cursor", cursor, "size", 100))
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                break;
            }
            Object result = response.get("result");
            if (!(result instanceof Map<?, ?> resultMap)) {
                break;
            }
            Object list = resultMap.get("list");
            if (list instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> user) {
                        users.add(new DingUser(
                                string(user.get("userid")),
                                string(user.get("name")),
                                string(user.get("mobile")),
                                string(user.get("email")),
                                string(user.get("job_number"))));
                    }
                }
            }
            Object hasMore = resultMap.get("has_more");
            if (!Boolean.TRUE.equals(hasMore)) {
                break;
            }
            Object next = resultMap.get("next_cursor");
            cursor = next instanceof Number number ? number.longValue() : cursor + 100;
        }
        return users;
    }

    @Override
    public boolean sendWorkNotification(String dingtalkUserId, String title, String markdown, String link) {
        try {
            restClient.post()
                    .uri(OAPI + "/topapi/message/corpconversation/asyncsend_v2?access_token=" + accessToken())
                    .body(Map.of(
                            "agent_id", settings.dingtalkAgentId(),
                            "userid_list", dingtalkUserId,
                            "msg", Map.of(
                                    "msgtype", "markdown",
                                    "markdown", Map.of(
                                            "title", title,
                                            "text", markdown + "\n\n[查看详情](" + link + ")"))))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException ex) {
            log.warn("DingTalk notification failed for {}: {}", dingtalkUserId, ex.getMessage());
            return false;
        }
    }

    private String accessToken() {
        Map<?, ?> response = restClient.post()
                .uri(API + "/v1.0/oauth2/accessToken")
                .body(Map.of("appKey", settings.dingtalkAppKey(), "appSecret", settings.dingtalkAppSecret()))
                .retrieve()
                .body(Map.class);
        Object token = response == null ? null : response.get("accessToken");
        if (token == null) {
            throw new IllegalStateException("无法获取钉钉 access token");
        }
        return token.toString();
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }
}
