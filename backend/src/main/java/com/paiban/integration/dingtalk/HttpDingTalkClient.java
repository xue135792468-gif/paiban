package com.paiban.integration.dingtalk;

import com.paiban.common.settings.SettingsService;
import com.paiban.integration.domain.DingDepartment;
import com.paiban.integration.domain.DingUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
    private final RestClient restClient;
    private volatile String cachedToken;
    private volatile String cachedCredentials;
    private volatile long tokenExpiresAt;

    @Autowired
    public HttpDingTalkClient(SettingsService settings) {
        this(settings, boundedRestClient());
    }

    private static RestClient boundedRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** 测试 seam：允许注入带有本地 stub 的 RestClient，绝不访问真实钉钉。 */
    HttpDingTalkClient(SettingsService settings, RestClient restClient) {
        this.settings = settings;
        this.restClient = restClient;
    }

    @Override
    public String exchangeAuthCode(String authCode) {
        Map<?, ?> result = identityResult(
                "/topapi/v2/user/getuserinfo", Map.of("code", authCode), "获取钉钉免登身份");
        if (!(result.get("userid") instanceof String userId) || !StringUtils.hasText(userId)) {
            throw DingTalkApiException.incomplete("获取钉钉免登身份");
        }
        return userId;
    }

    private LinkedMultiValueMap<String, String> identityForm(Map<String, String> parameters) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("access_token", accessToken());
        parameters.forEach(form::add);
        return form;
    }

    @Override
    public DingUser getUser(String userId) {
        Map<?, ?> result = identityResult(
                "/topapi/v2/user/get", Map.of("userid", userId, "language", "zh_CN"), "获取钉钉用户详情");
        if (!(result.get("userid") instanceof String verifiedId) || !userId.equals(verifiedId)
                || !(result.get("name") instanceof String name) || !StringUtils.hasText(name)) {
            throw DingTalkApiException.incomplete("获取钉钉用户详情");
        }
        return new DingUser(verifiedId, name, null, null, null);
    }

    /** Both identity endpoints require an explicit successful vendor status and result. */
    private Map<?, ?> identityResult(String path, Map<String, String> parameters, String action) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri(OAPI + path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(identityForm(parameters))
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("errcode") instanceof Number code)) {
                throw DingTalkApiException.incomplete(action);
            }
            if (code.doubleValue() != 0) {
                int subCode = response.get("sub_code") instanceof Number sub ? sub.intValue() : 0;
                throw new DingTalkApiException(action, code.intValue(), subCode, null);
            }
            if (!(response.get("result") instanceof Map<?, ?> result)) {
                throw DingTalkApiException.incomplete(action);
            }
            return result;
        } catch (DingTalkApiException ex) {
            // Keep numeric diagnostics, never forward a vendor error body or a cause
            // that could contain the one-use code, application secret or token.
            throw new DingTalkApiException(action, ex.errorCode(), ex.subCode(), null);
        } catch (RuntimeException ex) {
            throw new DingTalkApiException(action, -1, 0, "钉钉身份调用未完成，请重试或联系超级管理员");
        }
    }

    @Override
    public List<DingDepartment> listSubDepartments(long departmentId) {
        Map<?, ?> response = restClient.post()
                .uri(OAPI + "/topapi/v2/department/listsub?access_token=" + accessToken())
                .body(Map.of("dept_id", departmentId))
                .retrieve()
                .body(Map.class);
        DingTalkApiException.check(response, "列出子部门 (dept_id=" + departmentId + ")");
        if (response == null) {
            throw DingTalkApiException.incomplete("列出子部门 (dept_id=" + departmentId + ")");
        }
        List<DingDepartment> departments = new ArrayList<>();
        Object result = response.get("result");
        if (!(result instanceof List<?> items)) {
            throw DingTalkApiException.incomplete("列出子部门 (dept_id=" + departmentId + ")");
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> department) || !(department.get("dept_id") instanceof Number id)) {
                throw DingTalkApiException.incomplete("列出子部门 (dept_id=" + departmentId + ")");
            }
            Object parent = department.get("parent_id");
            departments.add(new DingDepartment(
                    id.longValue(),
                    string(department.get("name")),
                    parent instanceof Number parentId ? parentId.longValue() : null));
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
        if (response == null) {
            throw DingTalkApiException.incomplete("拉取钉钉部门用户 (dept_id=" + departmentId + ")");
        }
        Object result = response.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw DingTalkApiException.incomplete("拉取钉钉部门用户 (dept_id=" + departmentId + ")");
        }
        List<DingUser> users = new ArrayList<>();
        Object list = resultMap.get("list");
        if (!(list instanceof List<?> items)) {
            throw DingTalkApiException.incomplete("拉取钉钉部门用户 (dept_id=" + departmentId + ")");
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> user) || !(user.get("userid") instanceof String userId)
                    || !StringUtils.hasText(userId)) {
                throw DingTalkApiException.incomplete("拉取钉钉部门用户 (dept_id=" + departmentId + ")");
            }
            users.add(new DingUser(
                    userId,
                    string(user.get("name")),
                    string(user.get("mobile")),
                    // Email enrichment remains deferred.
                    null,
                    string(user.get("job_number")),
                    isActive(user.get("active")),
                    List.of()));
        }
        if (!(resultMap.get("has_more") instanceof Boolean hasMore)) {
            throw DingTalkApiException.incomplete("拉取钉钉部门用户 (dept_id=" + departmentId + ")");
        }
        long nextCursor;
        if (resultMap.get("next_cursor") instanceof Number next) {
            nextCursor = next.longValue();
        } else if (hasMore) {
            // 还有下一页却没有游标：这是协议结构缺失，不能当成末页把目录判成完整。
            throw DingTalkApiException.incomplete("拉取钉钉部门用户 (dept_id=" + departmentId + ")");
        } else {
            nextCursor = cursor + size;
        }
        return new DingUserPage(users, hasMore, nextCursor);
    }

    @Override
    public boolean sendWorkNotification(String dingtalkUserId, String title, String markdown, String link) {
        try {
            String absolute = link == null || link.startsWith("http")
                    ? link
                    : settings.publicBaseUrl() + link;
            Map<?, ?> response = restClient.post()
                    .uri(OAPI + "/topapi/message/corpconversation/asyncsend_v2?access_token=" + accessToken())
                    .body(Map.of(
                            "agent_id", settings.dingtalkAgentId(),
                            "userid_list", dingtalkUserId,
                            "msg", Map.of(
                                    "msgtype", "action_card",
                                    "action_card", Map.of(
                                            "title", title,
                                            "markdown", markdown,
                                            "single_title", "查看详情",
                                            "single_url", absolute == null ? "" : absolute))))
                    .retrieve()
                    .body(Map.class);
            return response != null
                    && response.get("errcode") instanceof Number code
                    && code.doubleValue() == 0;
        } catch (RuntimeException ex) {
            log.warn("DingTalk work notification delivery failed");
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
