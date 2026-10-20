package com.paiban.integration.auth;

import com.paiban.common.settings.SettingsService;
import com.paiban.foundation.domain.Teacher;
import com.paiban.foundation.domain.TeacherRepository;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.identity.service.UserService;
import com.paiban.integration.dingtalk.DingTalkClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** DingTalk免登: authCode -> DingTalk userId -> local teacher -> local account. */
@Component
public class DingTalkAuthProvider implements AuthProvider {

    private final SettingsService settings;
    private final DingTalkClient client;
    private final TeacherRepository teachers;
    private final UserService users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;

    public DingTalkAuthProvider(
            SettingsService settings,
            DingTalkClient client,
            TeacherRepository teachers,
            UserService users,
            RoleRepository roles,
            PasswordEncoder passwordEncoder) {
        this.settings = settings;
        this.client = client;
        this.teachers = teachers;
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public String code() {
        return "dingtalk";
    }

    @Override
    @Transactional
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
        if (!accounts.isEmpty()) {
            return accounts.stream()
                    .filter(AppUser::isEnabled)
                    .map(AppUser::getUsername)
                    .findFirst();
        }
        AppUser created = provision(teacher, dingtalkUserId);
        return Optional.of(created.getUsername());
    }

    private AppUser provision(Teacher teacher, String dingtalkUserId) {
        AppUser user = new AppUser();
        String username = teacher.getEmployeeNo() == null || teacher.getEmployeeNo().isBlank()
                ? "dd_" + dingtalkUserId
                : teacher.getEmployeeNo();
        user.setUsername(username);
        user.setDisplayName(teacher.getName());
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setTeacherId(teacher.getId());
        user.setCreatedAt(Instant.now());
        Role role = roles.findByCode("teacher").orElseThrow();
        user.getRoles().add(role);
        return users.save(user);
    }
}
