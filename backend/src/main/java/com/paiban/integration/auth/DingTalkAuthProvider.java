package com.paiban.integration.auth;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.service.UserService;
import com.paiban.integration.dingtalk.DingTalkClient;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DingTalk免登: authCode -> DingTalk userId -> local teacher -> existing local account.
 * Accounts are created by the administrator (user story 51), never auto-provisioned.
 */
@Component
public class DingTalkAuthProvider implements AuthProvider {

    private final SettingsService settings;
    private final DingTalkClient client;
    private final TeacherRepository teachers;
    private final UserService users;

    public DingTalkAuthProvider(
            SettingsService settings,
            DingTalkClient client,
            TeacherRepository teachers,
            UserService users) {
        this.settings = settings;
        this.client = client;
        this.teachers = teachers;
        this.users = users;
    }

    @Override
    public String code() {
        return "dingtalk";
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> authenticate(AuthCredentials credentials) {
        if (!settings.dingtalkEnabled() || credentials.authCode() == null) {
            return Optional.empty();
        }
        String dingtalkUserId;
        try {
            dingtalkUserId = client.exchangeAuthCode(credentials.authCode());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        Teacher teacher = teachers.findByDingtalkUserId(dingtalkUserId).orElse(null);
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
