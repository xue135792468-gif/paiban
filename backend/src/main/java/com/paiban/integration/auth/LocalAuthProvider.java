package com.paiban.integration.auth;

import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.LoginType;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * 密码登录入口。票 03：密码入口必须在 {@link AuthenticationManager} 之前按账号类型判断——
 * 钉钉免登账号（{@code DINGTALK}）没有密码，任何密码都不得进入校验，也不再靠「空哈希偶然失败」
 * 来拒绝；未知账号仍走原来的 {@code Optional.empty()}（同一句「用户名或密码错误」，不泄露账号是否存在）。
 */
@Component
public class LocalAuthProvider implements AuthProvider {

    /** 规格：钉钉免登账号用密码登录一律拒绝，提示「请在钉钉中打开」。 */
    private static final String DINGTALK_ONLY_MESSAGE = "该账号是钉钉免登账号，请在钉钉中打开";

    private final AuthenticationManager authenticationManager;
    private final AppUserRepository users;

    public LocalAuthProvider(AuthenticationManager authenticationManager, AppUserRepository users) {
        this.authenticationManager = authenticationManager;
        this.users = users;
    }

    @Override
    public String code() {
        return "local";
    }

    @Override
    public Optional<String> authenticate(AuthCredentials credentials) {
        if (credentials.username() == null || credentials.password() == null) {
            return Optional.empty();
        }
        Optional<AppUser> account = users.findByUsername(credentials.username());
        if (account.isEmpty()) {
            return Optional.empty();
        }
        if (account.get().getLoginType() == LoginType.DINGTALK) {
            throw ApiException.unauthorized(DINGTALK_ONLY_MESSAGE);
        }
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(credentials.username(), credentials.password()));
            return Optional.of(credentials.username());
        } catch (AuthenticationException ex) {
            return Optional.empty();
        }
    }
}
