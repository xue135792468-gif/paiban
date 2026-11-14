package com.paiban.integration.dingtalk;

/**
 * Stable outcomes of a stateless DingTalk credential probe. Messages are fixed
 * application text; vendor bodies and exception details never reach the caller.
 */
public enum DingTalkProbeCode {
    OK("本次凭据可获取访问令牌并读取根部门"),
    TOKEN_REJECTED("钉钉未能使用本次 AppKey / AppSecret 获取访问令牌，请检查凭据和应用权限"),
    DEPARTMENT_REJECTED("已取得访问令牌，但无法读取根部门，请检查通讯录权限和应用可见范围"),
    NETWORK_ERROR("无法连接钉钉或请求超时，请检查网络后重试"),
    INVALID_RESPONSE("钉钉返回了无法识别的响应，请检查应用配置或稍后重试");

    private final String message;

    DingTalkProbeCode(String message) {
        this.message = message;
    }

    public boolean success() {
        return this == OK;
    }

    public String message() {
        return message;
    }
}
