package com.paiban.integration.dingtalk;

import java.util.Map;

/**
 * A structured error returned by the DingTalk open platform. Carries the
 * {@code errcode}/{@code sub_code} pair so callers can tell an IP whitelist
 * rejection or an inaccessible department apart from a transient failure.
 */
public class DingTalkApiException extends RuntimeException {

    private static final int DEPARTMENT_UNAVAILABLE = 41;
    private static final int IP_WHITELIST_SUB_CODE = 60020;

    private final int errorCode;
    private final int subCode;

    public DingTalkApiException(String action, int errorCode, int subCode, String errorMessage) {
        super(describe(action, errorCode, subCode, errorMessage));
        this.errorCode = errorCode;
        this.subCode = subCode;
    }

    public int errorCode() {
        return errorCode;
    }

    public int subCode() {
        return subCode;
    }

    /** The application's IP whitelist rejected the request (errcode 88 / sub_code 60020). */
    public boolean isIpWhitelist() {
        return subCode == IP_WHITELIST_SUB_CODE;
    }

    /** The department does not exist or is not readable; other departments are still usable. */
    public boolean isDepartmentUnavailable() {
        return errorCode == DEPARTMENT_UNAVAILABLE;
    }

    /** Throws when the response carries a non-zero DingTalk error code. */
    public static void check(Map<?, ?> response, String action) {
        if (response == null) {
            return;
        }
        int errorCode = intValue(response.containsKey("errcode") ? response.get("errcode") : response.get("code"));
        if (errorCode == 0) {
            return;
        }
        int subCode = intValue(response.get("sub_code"));
        Object message = response.get("errmsg") != null ? response.get("errmsg") : response.get("message");
        throw new DingTalkApiException(action, errorCode, subCode, message == null ? null : message.toString());
    }

    private static String describe(String action, int errorCode, int subCode, String errorMessage) {
        StringBuilder text = new StringBuilder(action)
                .append("失败：errcode=").append(errorCode);
        if (subCode != 0) {
            text.append("，sub_code=").append(subCode);
        }
        if (errorMessage != null && !errorMessage.isBlank()) {
            text.append("，").append(errorMessage);
        }
        if (subCode == IP_WHITELIST_SUB_CODE) {
            text.append("。钉钉拒绝了服务器出口 IP，请把服务器 IPv4 加入该应用的 IP 白名单后重试");
        }
        return text.toString();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
