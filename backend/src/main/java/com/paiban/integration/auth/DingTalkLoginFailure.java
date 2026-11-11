package com.paiban.integration.auth;

import com.paiban.common.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Stable public reasons; vendor response bodies never become login explanations. */
enum DingTalkLoginFailure {
    NOT_PROVISIONED("你还没有开通，请联系教务处"),
    AMBIGUOUS_IDENTITY("系统无法确认你是哪位老师，请联系教务处"),
    ACCOUNT_DISABLED("账号已停用，请联系超级管理员"),
    DINGTALK_DISABLED("学校尚未启用钉钉登录，请联系超级管理员"),
    DINGTALK_CALL_FAILED("钉钉登录暂时不可用，请稍后重试或联系超级管理员"),
    ADMIN_BINDING_REQUIRED("管理员身份需要超级管理员先在教师档案中确认钉钉绑定");

    private final String message;

    DingTalkLoginFailure(String message) {
        this.message = message;
    }

    ApiException exception() {
        HttpStatus status = this == DINGTALK_DISABLED ? HttpStatus.BAD_REQUEST : HttpStatus.UNAUTHORIZED;
        return new ApiException(status, message, Map.of("code", name()));
    }
}
