package com.paiban.integration.auth;

import com.paiban.common.settings.SettingsService;
import com.paiban.integration.dingtalk.DingTalkApiException;
import com.paiban.integration.dingtalk.DingTalkClient;
import com.paiban.integration.domain.DingUser;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * DingTalk免登: authCode -> DingTalk userId -> local teacher -> local account.
 * Verified vendor identity is resolved or provisioned in a separate local transaction.
 */
@Component
public class DingTalkAuthProvider implements AuthProvider {

    private static final Logger log = LoggerFactory.getLogger(DingTalkAuthProvider.class);

    private final SettingsService settings;
    private final DingTalkClient client;
    private final DingTalkAccountProvisioner accounts;

    public DingTalkAuthProvider(
            SettingsService settings,
            DingTalkClient client,
            DingTalkAccountProvisioner accounts) {
        this.settings = settings;
        this.client = client;
        this.accounts = accounts;
    }

    @Override
    public String code() {
        return "dingtalk";
    }

    @Override
    public Optional<String> authenticate(AuthCredentials credentials) {
        if (!settings.dingtalkEnabled()) {
            throw DingTalkLoginFailure.DINGTALK_DISABLED.exception();
        }
        if (credentials == null || !StringUtils.hasText(credentials.authCode())) {
            throw DingTalkLoginFailure.DINGTALK_CALL_FAILED.exception();
        }
        DingUser identity;
        try {
            String userId = client.exchangeAuthCode(credentials.authCode());
            identity = client.getUser(userId);
            if (identity == null || !userId.equals(identity.userId())) {
                throw DingTalkLoginFailure.DINGTALK_CALL_FAILED.exception();
            }
        } catch (DingTalkApiException ex) {
            // The adapter has already stripped vendor text and credentials; keep only the
            // two numeric diagnostics so a real tenant failure is diagnosable server-side.
            log.warn("钉钉免登外部调用失败：errcode={}，sub_code={}", ex.errorCode(), ex.subCode());
            throw DingTalkLoginFailure.DINGTALK_CALL_FAILED.exception();
        } catch (RuntimeException ex) {
            throw DingTalkLoginFailure.DINGTALK_CALL_FAILED.exception();
        }
        return Optional.of(accounts.resolveAccount(identity));
    }
}
