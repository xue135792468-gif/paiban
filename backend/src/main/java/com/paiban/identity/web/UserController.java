package com.paiban.identity.web;

import com.paiban.identity.domain.Permissions;
import com.paiban.identity.service.UserService;
import com.paiban.identity.web.IdentityDtos.CreateUserRequest;
import com.paiban.identity.web.IdentityDtos.ResetPasswordRequest;
import com.paiban.identity.web.IdentityDtos.RoleDto;
import com.paiban.identity.web.IdentityDtos.UpdateUserRequest;
import com.paiban.identity.web.IdentityDtos.UserSummary;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public List<UserSummary> list() {
        return userService.list();
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public UserSummary create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public UserSummary update(@PathVariable Long id, @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.newPassword());
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    public List<RoleDto> roles() {
        return userService.listRoles();
    }
}
