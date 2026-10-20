package com.paiban.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiban.common.settings.SettingsService;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Real Feishu open-platform client. Requires a reachable tenant and valid
 * credentials; when integration is disabled the callers never invoke it.
 * Failures are logged (method, status, error code only) and reported to the
 * adapters as empty/false results, never as exceptions.
 */
@Component
@ConditionalOnProperty(name = "paiban.integration.feishu.fake", havingValue = "false", matchIfMissing = true)
public class HttpFeishuClient implements FeishuClient {

    private static final Logger log = LoggerFactory.getLogger(HttpFeishuClient.class);
    private static final String BASE = "https://open.feishu.cn/open-apis";
    private static final int MAX_ATTEMPTS = 3;
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 300L;

    private final SettingsService settings;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    private volatile CachedToken tenantToken;
    private volatile CachedToken appToken;

    public HttpFeishuClient(SettingsService settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    @Override
    public FeishuUser exchangeAuthCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Map<?, ?> response = withRetry("authen.access_token", () -> restClient.post()
                .uri(BASE + "/authen/v1/access_token")
                .header("Authorization", "Bearer " + appAccessToken())
                .body(Map.of("grant_type", "authorization_code", "code", code))
                .retrieve()
                .body(Map.class));
        if (response == null) {
            return null;
        }
        Map<?, ?> data = asMap(response.get("data"));
        if (data == null) {
            log.warn("Feishu authen.access_token returned no data, code={}", response.get("code"));
            return null;
        }
        return new FeishuUser(
                string(data.get("user_id")),
                string(data.get("open_id")),
                string(data.get("name")),
                string(data.get("mobile")),
                string(data.get("email")),
                string(data.get("employee_no")));
    }

    @Override
    public List<FeishuDepartment> listDepartments() {
        List<FeishuDepartment> departments = new ArrayList<>();
        String pageToken = null;
        do {
            String currentPage = pageToken;
            URI uri = UriComponentsBuilder.fromHttpUrl(BASE + "/contact/v3/departments")
                    .queryParam("parent_department_id", "0")
                    .queryParam("fetch_child", "true")
                    .queryParam("page_size", "50")
                    .queryParamIfPresent("page_token", java.util.Optional.ofNullable(currentPage))
                    .build()
                    .toUri();
            Map<?, ?> response = withRetry("contact.departments", () -> get(uri));
            Map<?, ?> data = response == null ? null : asMap(response.get("data"));
            if (data == null) {
                break;
            }
            for (Object item : asList(data.get("items"))) {
                Map<?, ?> department = asMap(item);
                if (department != null) {
                    departments.add(new FeishuDepartment(
                            string(department.get("department_id")),
                            string(department.get("name")),
                            string(department.get("parent_department_id"))));
                }
            }
            pageToken = Boolean.TRUE.equals(data.get("has_more")) ? string(data.get("page_token")) : null;
        } while (pageToken != null && !pageToken.isBlank());
        return departments;
    }

    @Override
    public List<FeishuUser> listUsers() {
        List<FeishuUser> users = new ArrayList<>();
        String pageToken = null;
        do {
            String currentPage = pageToken;
            URI uri = UriComponentsBuilder.fromHttpUrl(BASE + "/contact/v3/users/find_by_department")
                    .queryParam("department_id", "0")
                    .queryParam("page_size", "50")
                    .queryParam("user_id_type", "open_id")
                    .queryParamIfPresent("page_token", java.util.Optional.ofNullable(currentPage))
                    .build()
                    .toUri();
            Map<?, ?> response = withRetry("contact.users", () -> get(uri));
            Map<?, ?> data = response == null ? null : asMap(response.get("data"));
            if (data == null) {
                break;
            }
            for (Object item : asList(data.get("items"))) {
                Map<?, ?> user = asMap(item);
                if (user != null) {
                    users.add(new FeishuUser(
                            string(user.get("user_id")),
                            string(user.get("open_id")),
                            string(user.get("name")),
                            string(user.get("mobile")),
                            string(user.get("email")),
                            string(user.get("employee_no"))));
                }
            }
            pageToken = Boolean.TRUE.equals(data.get("has_more")) ? string(data.get("page_token")) : null;
        } while (pageToken != null && !pageToken.isBlank());
        return users;
    }

    @Override
    public boolean sendMessage(FeishuMessage message) {
        if (message == null) {
            return false;
        }
        String receiveIdType;
        String receiveId;
        if (message.openId() != null && !message.openId().isBlank()) {
            receiveIdType = "open_id";
            receiveId = message.openId();
        } else if (message.userId() != null && !message.userId().isBlank()) {
            receiveIdType = "user_id";
            receiveId = message.userId();
        } else {
            return false;
        }
        String content;
        try {
            content = new FeishuCardBuilder(objectMapper, settings.publicBaseUrl()).build(message);
        } catch (IllegalStateException ex) {
            log.warn("Feishu im.messages payload build failed: {}", ex.getClass().getSimpleName());
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receive_id", receiveId);
        body.put("msg_type", message.card() ? "interactive" : "text");
        body.put("content", content);
        String uri = BASE + "/im/v1/messages?receive_id_type=" + receiveIdType;
        Boolean sent = withRetry("im.messages", () -> {
            restClient.post()
                    .uri(uri)
                    .header("Authorization", "Bearer " + tenantAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(sent);
    }

    private Map<?, ?> get(URI uri) {
        return restClient.get()
                .uri(uri)
                .header("Authorization", "Bearer " + tenantAccessToken())
                .retrieve()
                .body(Map.class);
    }

    private String tenantAccessToken() {
        CachedToken cached = tenantToken;
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.value();
        }
        synchronized (this) {
            if (tenantToken != null && Instant.now().isBefore(tenantToken.expiresAt())) {
                return tenantToken.value();
            }
            CachedToken fetched = withRetry("tenant_access_token", () -> fetchToken(
                    "/auth/v3/tenant_access_token/internal", "tenant_access_token"));
            if (fetched == null) {
                throw new IllegalStateException("无法获取飞书 tenant_access_token");
            }
            tenantToken = fetched;
            return fetched.value();
        }
    }

    private String appAccessToken() {
        CachedToken cached = appToken;
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.value();
        }
        synchronized (this) {
            if (appToken != null && Instant.now().isBefore(appToken.expiresAt())) {
                return appToken.value();
            }
            CachedToken fetched = withRetry("app_access_token", () -> fetchToken(
                    "/auth/v3/app_access_token/internal", "app_access_token"));
            if (fetched == null) {
                throw new IllegalStateException("无法获取飞书 app_access_token");
            }
            appToken = fetched;
            return fetched.value();
        }
    }

    private CachedToken fetchToken(String path, String tokenField) {
        Map<?, ?> response = restClient.post()
                .uri(BASE + path)
                .body(Map.of("app_id", settings.feishuAppId(), "app_secret", settings.feishuAppSecret()))
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("飞书 token 响应为空");
        }
        Object code = response.get("code");
        if (code instanceof Number number && number.intValue() != 0) {
            throw new IllegalStateException("飞书 token 请求失败，错误码 " + number.intValue());
        }
        Object token = response.get(tokenField);
        if (token == null || token.toString().isBlank()) {
            throw new IllegalStateException("飞书未返回 " + tokenField);
        }
        long seconds = response.get("expire") instanceof Number number ? number.longValue() : 7200L;
        Instant expiresAt = Instant.now().plusSeconds(Math.max(60L, seconds - TOKEN_REFRESH_MARGIN_SECONDS));
        return new CachedToken(token.toString(), expiresAt);
    }

    /** Retries 5xx/429/IO up to three times with exponential backoff; other failures stop immediately. */
    private <T> T withRetry(String operation, Supplier<T> call) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status != 429 && status < 500) {
                    log.warn("Feishu {} failed with status {}", operation, status);
                    return null;
                }
                log.warn("Feishu {} retryable status {}", operation, status);
            } catch (ResourceAccessException ignored) {
                log.warn("Feishu {} I/O failure", operation);
            } catch (RuntimeException ex) {
                log.warn("Feishu {} failed: {}", operation, ex.getClass().getSimpleName());
                return null;
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        log.warn("Feishu {} exhausted retries after {} attempts", operation, MAX_ATTEMPTS);
        return null;
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record CachedToken(String value, Instant expiresAt) {}
}
