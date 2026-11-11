package com.paiban.identity.domain;

/**
 * 票 03：账号的登录类型。
 *
 * <p>PASSWORD 是密码账号（含 {@code admin} 总钥匙）；DINGTALK 是钉钉免登账号，
 * 没有密码、绑定教师档案，密码登录与重置密码都必须按类型拒绝。
 */
public enum LoginType {
    PASSWORD,
    DINGTALK
}
