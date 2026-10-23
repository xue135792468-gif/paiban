package com.paiban.integration.dingtalk;

import com.paiban.common.settings.SettingsService;
import com.paiban.integration.domain.DingDepartment;
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
 *
 * <p>{@code user/list} only returns the users directly under one department, so
 * the directory loader drives this client department by department.
 */
@Component
@ConditionalOnProperty(name = "paiban.integration.dingtalk.fake", havingValue = "false", matchIfMissing = true)
public class HttpDingTalkClient implements DingTalkClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDingTalkClient.class);
    private static final String API = "https://api.dingtalk.com";
    private static final String OAPI = "https://oapi.dingtalk.com";

    private final SettingsService settings;
    private final RestClient restClient = RestClient.create();
    private volatile String cachedToken;
    private volatile String cachedCredentials;
    private volatile long tokenExpiresAt;

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
    public List<DingDepartment> listSubDepartments(long departmentId) {
        Map<?, ?> response = restClient.post()
                .uri(OAPI + "/topapi/v2/department/listsub?access_token=" + accessToken())
                .body(Map.of("dept_id", departmentId))
                .retrieve()
                .body(Map.class);
        DingTalkApiException.check(response, "列出子部门 (dept_id=" + departmentId + ")");
        List<DingDepartment> departments = new ArrayList<>();
        Object result = response == null ? null : response.get("result");
        if (result instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> department && department.get("dept_id") instanceof Number id) {
                    Object parent = department.get("parent_id");
                    departments.add(new DingDepartment(
                            id.longValue(),
                            string(department.get("name")),
                            parent instanceof Number parentId ? parentId.longValue() : null));
                }
            }
        }
        return departments;
    }

    @Override
    public DingUserPage listUsers(long departmentId, long cursor, int size) {
        Map<?, ?> response = restClient.post()
                .uri(OAPI + "/topapi/v2/user/list?access_token=" + accessToken())
                .body(Map.of("dept_id", departmentId, "cursor", cursor, "size", size))
                .retrieve()
                .body(Map.class);
        DingTalkApiException.check(response, "拉取钉钉部门用户 (dept_id=" + departmentId + ")");
        Object result = response == null ? null : response.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            return DingUserPage.last(List.of());
        }
        List<DingUser> users = new ArrayList<>();
        Object list = resultMap.get("list");
        if (list instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> user) {
                    users.add(new DingUser(
                            string(user.get("userid")),
                            string(user.get("name")),
                            string(user.get("mobile")),
                            // user/list does not return email; field customisation is backlog.
                            null,
                            string(user.get("job_number")),
                            isActive(user.get("active")),
                            List.of()));
                }
            }
        }
        boolean hasMore = Boolean.TRUE.equals(resultMap.get("has_more"));
        long nextCursor = resultMap.get("next_cursor") instanceof Number next
                ? next.longValue()
                : cursor + size;
        return new DingUserPage(users, hasMore, nextCursor);
    }

    @Override
    public boolean sendWorkNotification(String dingtalkUserId, String title, String markdown, String link) {
        try {
            String absolute = link == null || link.startsWith("http")
                    ? link
                    : settings.publicBaseUrl() + link;
            restClient.post()
                    .uri(OAPI + "/topapi/message/corpconversation/asyncsend_v2?access_token=" + accessToken())
                    .body(Map.of(
                            "agent_id", settings.dingtalkAgentId(),
                            "userid_list", dingtalkUserId,
                            "msg", Map.of(
                                    "msgtype", "actionCard",
                                    "actionCard", Map.of(
                                            "title", title,
                                            "text", markdown,
                                            "singleTitle", "查看详情",
                                            "singleURL", absolute == null ? "" : absolute))))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException ex) {
            log.warn("DingTalk notification failed for {}: {}", dingtalkUserId, ex.getMessage());
            return false;
        }
    }

    /**
     * The recursive directory walk calls this once per department and page; DingTalk
     * tokens live for two hours, so cache one instead of asking on every request.
     */
    private synchronized String accessToken() {
        String credentials = settings.dingtalkAppKey() + ":" + settings.dingtalkAppSecret();
        long now = System.currentTimeMillis();
        if (cachedToken != null && credentials.equals(cachedCredentials) && now < tokenExpiresAt) {
            return cachedToken;
        }
        Map<?, ?> response = restClient.post()
                .uri(API + "/v1.0/oauth2/accessToken")
                .body(Map.of("appKey", settings.dingtalkAppKey(), "appSecret", settings.dingtalkAppSecret()))
                .retrieve()
                .body(Map.class);
        Object token = response == null ? null : response.get("accessToken");
        if (token == null) {
            throw new IllegalStateException("无法获取钉钉 access token");
        }
        long expiresInSeconds = response.get("expireIn") instanceof Number seconds ? seconds.longValue() : 7200;
        cachedToken = token.toString();
        cachedCredentials = credentials;
        // Refresh a minute early so a long sync never races the expiry.
        tokenExpiresAt = now + Math.max(60, expiresInSeconds - 60) * 1000;
        return cachedToken;
    }

    private boolean isActive(Object value) {
        // An absent flag means the tenant did not mark the account inactive.
        return !(value instanceof Boolean active) || active;
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }
}
