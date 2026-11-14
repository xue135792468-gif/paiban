package com.paiban.integration.service;

import com.paiban.common.web.ApiException;
import com.paiban.integration.dingtalk.DingTalkProbeClient;
import com.paiban.integration.dingtalk.DingTalkProbeCode;
import com.paiban.integration.service.DingTalkStoredCredentialReader.StoredCredentials;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Resolves the credentials to test, then delegates the network work outside any
 * transaction. The probe never saves settings or writes an audit/business row.
 */
@Service
public class DingTalkConnectionProbeService {

    private final DingTalkStoredCredentialReader savedCredentials;
    private final DingTalkProbeClient probeClient;

    public DingTalkConnectionProbeService(
            DingTalkStoredCredentialReader savedCredentials,
            DingTalkProbeClient probeClient) {
        this.savedCredentials = savedCredentials;
        this.probeClient = probeClient;
    }

    public DingTalkProbeCode probe(String appKey, String appSecret, Boolean useSavedSecret) {
        if (!StringUtils.hasText(appKey)) {
            throw ApiException.badRequest("AppKey 不能为空");
        }
        String secret = appSecret;
        if (!StringUtils.hasText(secret)) {
            if (!Boolean.TRUE.equals(useSavedSecret)) {
                throw ApiException.badRequest("请输入本次 AppSecret，或明确使用已保存密钥");
            }
            StoredCredentials saved = savedCredentials.read();
            if (!appKey.equals(saved.appKey()) || !StringUtils.hasText(saved.appSecret())) {
                throw ApiException.badRequest("当前 AppKey 与已保存密钥不匹配，请重新输入 AppSecret");
            }
            secret = saved.appSecret();
        }
        return probeClient.probe(appKey, secret);
    }
}
