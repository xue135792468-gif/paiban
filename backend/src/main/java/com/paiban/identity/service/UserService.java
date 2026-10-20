package com.paiban.identity.service;

import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import com.paiban.identity.web.IdentityDtos.CreateUserRequest;
import com.paiban.identity.web.IdentityDtos.RoleDto;
import com.paiban.identity.web.IdentityDtos.UpdateUserRequest;
import com.paiban.identity.web.IdentityDtos.UserSummary;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;

    public UserService(AppUserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserSummary> list() {
        return users.findAll().stream()
                .sorted(Comparator.comparing(AppUser::getId))
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public UserSummary create(CreateUserRequest request) {
        if (users.existsByUsername(request.username())) {
            throw ApiException.conflict("用户名已存在: " + request.username());
        }
        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setDisplayName(request.displayName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setTeacherId(request.teacherId());
        user.setCreatedAt(Instant.now());
        user.setRoles(resolveRoles(request.roleCodes()));
        return toSummary(users.save(user));
    }

    @Transactional
    public UserSummary update(Long id, UpdateUserRequest request) {
        AppUser user = get(id);
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        if (request.roleCodes() != null) {
            user.setRoles(resolveRoles(request.roleCodes()));
        }
        if (request.teacherId() != null) {
            user.setTeacherId(request.teacherId());
        }
        return toSummary(user);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        AppUser user = get(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
    }

    @Transactional(readOnly = true)
    public AppUser get(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("用户不存在: " + id));
    }

    @Transactional(readOnly = true)
    public AppUser getByUsername(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> ApiException.notFound("用户不存在: " + username));
    }

    @Transactional(readOnly = true)
    public List<RoleDto> listRoles() {
        return roles.findAll().stream()
                .sorted(Comparator.comparing(Role::getId))
                .map(role -> new RoleDto(
                        role.getId(),
                        role.getCode(),
                        role.getName(),
                        role.getPermissions().stream().map(p -> p.getCode()).sorted().toList()))
                .toList();
    }

    public UserSummary toSummary(AppUser user) {
        List<String> roleCodes = user.getRoles().stream().map(Role::getCode).sorted().toList();
        List<String> permissions = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(p -> p.getCode())
                .distinct()
                .sorted()
                .toList();
        return new UserSummary(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.isEnabled(),
                user.getTeacherId(),
                roleCodes,
                permissions);
    }

    private Set<Role> resolveRoles(List<String> roleCodes) {
        Set<Role> result = new HashSet<>();
        if (roleCodes == null || roleCodes.isEmpty()) {
            result.add(roles.findByCode("teacher").orElseThrow(() -> ApiException.badRequest("默认角色缺失")));
            return result;
        }
        for (String code : roleCodes) {
            result.add(roles.findByCode(code)
                    .orElseThrow(() -> ApiException.badRequest("角色不存在: " + code)));
        }
        return result;
    }
}
