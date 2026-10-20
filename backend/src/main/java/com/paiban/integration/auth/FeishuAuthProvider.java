package com.paiban.integration.auth;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.integration.feishu.FeishuClient;
import com.paiban.integration.feishu.FeishuTeacherResolver;
import com.paiban.integration.feishu.FeishuUser;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feishu免登: auth code -> Feishu user -> local teacher -> existing local account.
 * Accounts are created by the administrator, never auto-provisioned (ADR-0005).
 */
@Component
public class FeishuAuthProvider implements AuthProvider {

    private final SettingsService settings;
    private final FeishuClient client;
    private final FeishuTeacherResolver teacherResolver;
    private final UserService users;

    public FeishuAuthProvider(
            SettingsService settings,
            FeishuClient client,
            FeishuTeacherResolver teacherResolver,
            UserService users) {
        this.settings = settings;
        this.client = client;
        this.teacherResolver = teacherResolver;
        this.users = users;
    }

    @Override
    public String code() {
        return "feishu";
    }

    /** The code exchange is a network call, so this method deliberately has no transaction. */
    @Override
    public Optional<String> authenticate(AuthCredentials credentials) {
        if (!settings.feishuEnabled() || credentials.authCode() == null) {
            return Optional.empty();
        }
        FeishuUser user;
        try {
            user = client.exchangeAuthCode(credentials.authCode());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        return authenticate(user);
    }

    /**
     * Resolves an already-exchanged Feishu user to a local username. Feishu auth codes
     * are single-use, so callers that need the user (H5免登, binding) exchange once and
     * delegate here instead of calling {@link #authenticate(AuthCredentials)} again.
     */
    @Transactional(readOnly = true)
    public Optional<String> authenticate(FeishuUser user) {
        if (user == null) {
            return Optional.empty();
        }
        Teacher teacher = teacherResolver.resolveBound(user).orElse(null);
        if (teacher == null) {
            return Optional.empty();
        }
        List<AppUser> accounts = users.usersByTeacherId(teacher.getId());
        return accounts.stream()
                .filter(AppUser::isEnabled)
                .map(AppUser::getUsername)
                .findFirst();
    }
}
