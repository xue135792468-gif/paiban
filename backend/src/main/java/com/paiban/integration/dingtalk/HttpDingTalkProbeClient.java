package com.paiban.integration.dingtalk;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Stateless real HTTP probe. Unlike {@link HttpDingTalkClient}, this component keeps
 * no token cache and is safe to call with credentials that have not been saved.
 */
@Component
@ConditionalOnProperty(
        name = "paiban.integration.dingtalk.fake", havingValue = "false", matchIfMissing = true)
public class HttpDingTalkProbeClient implements DingTalkProbeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDingTalkProbeClient.class);
    private static final String API = "https://api.dingtalk.com";
    private static final String OAPI = "https://oapi.dingtalk.com";

    private final RestClient restClient;

    @Autowired
    public HttpDingTalkProbeClient() {
        this(boundedRestClient());
    }

    private static RestClient boundedRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** Test seam: inject a RestClient backed by a local stub. */
    HttpDingTalkProbeClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public DingTalkProbeCode probe(String appKey, String appSecret) {
        String token;
        try {
            Map<?, ?> exchange = restClient.post()
                    .uri(API + "/v1.0/oauth2/accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("appKey", appKey, "appSecret", appSecret))
                    .retrieve()
                    .body(Map.class);
            token = accessToken(exchange);
            if (!StringUtils.hasText(token)) {
                return fail("token", hasVendorRejection(exchange)
                        ? DingTalkProbeCode.TOKEN_REJECTED
                        : DingTalkProbeCode.INVALID_RESPONSE);
            }
        } catch (RestClientResponseException ex) {
            return fail("http-status", ex.getStatusCode().is4xxClientError()
                    ? DingTalkProbeCode.TOKEN_REJECTED : DingTalkProbeCode.INVALID_RESPONSE);
        } catch (ResourceAccessException ex) {
            return fail("transport", DingTalkProbeCode.NETWORK_ERROR);
        } catch (RestClientException ex) {
            return fail("response", DingTalkProbeCode.INVALID_RESPONSE);
        } catch (RuntimeException ex) {
            return fail("unexpected", DingTalkProbeCode.INVALID_RESPONSE);
        }

        try {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("dept_id", "1");
            Map<?, ?> root = restClient.post()
                    .uri(OAPI + "/topapi/v2/department/get?access_token=" + token)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            return inspectRoot(root);
        } catch (RestClientResponseException ex) {
            return fail("http-status", ex.getStatusCode().is4xxClientError()
                    ? DingTalkProbeCode.DEPARTMENT_REJECTED
                    : DingTalkProbeCode.INVALID_RESPONSE);
        } catch (ResourceAccessException ex) {
            return fail("transport", DingTalkProbeCode.NETWORK_ERROR);
        } catch (RestClientException ex) {
            return fail("response", DingTalkProbeCode.INVALID_RESPONSE);
        } catch (RuntimeException ex) {
            return fail("unexpected", DingTalkProbeCode.INVALID_RESPONSE);
        }
    }

    private String accessToken(Map<?, ?> response) {
        Object token = response == null ? null : response.get("accessToken");
        return token instanceof String text ? text : null;
    }

    private boolean hasVendorRejection(Map<?, ?> response) {
        if (response == null) {
            return false;
        }
        Object code = response.get("code");
        if (code instanceof String text && StringUtils.hasText(text)) {
            return true;
        }
        Object errcode = response.get("errcode");
        return errcode instanceof Number number && number.doubleValue() != 0d;
    }

    /**
     * A successful-looking errcode is not enough: the root read must also carry the
     * expected detail object for dept_id=1.
     */
    private DingTalkProbeCode inspectRoot(Map<?, ?> response) {
        if (response == null || !(response.get("errcode") instanceof Number errcode)) {
            return fail("root", DingTalkProbeCode.INVALID_RESPONSE);
        }
        double errcodeValue = errcode.doubleValue();
        if (errcodeValue != 0d) {
            // Real vendor codes are integers; a fractional "0.5" is a malformed
            // response, not a rejection we may attribute to a vendor stage.
            return fail("root-errcode", errcodeValue == Math.rint(errcodeValue)
                    ? DingTalkProbeCode.DEPARTMENT_REJECTED
                    : DingTalkProbeCode.INVALID_RESPONSE);
        }
        if (!(response.get("result") instanceof Map<?, ?> result)
                || !(result.get("dept_id") instanceof Number deptId)
                || deptId.doubleValue() != 1d) {
            return fail("root-result", DingTalkProbeCode.INVALID_RESPONSE);
        }
        return DingTalkProbeCode.OK;
    }

    private DingTalkProbeCode fail(String stage, DingTalkProbeCode code) {
        // Stage and stable code only. Vendor bodies, exception text, credentials and
        // access-token-bearing URLs must never enter application logs.
        log.info("DingTalk connection probe failed at {}: {}", stage, code);
        return code;
    }

}
