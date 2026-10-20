package com.paiban.identity.web;

import com.paiban.common.web.ApiException;
import com.paiban.identity.service.AuthService;
import com.paiban.identity.service.UserService;
import com.paiban.identity.web.IdentityDtos.LoginRequest;
import com.paiban.identity.web.IdentityDtos.LoginResponse;
import com.paiban.identity.web.IdentityDtos.UserSummary;
import com.paiban.integration.auth.AuthCredentials;
import com.paiban.integration.auth.LocalAuthProvider;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LocalAuthProvider localAuthProvider;
    private final AuthService authService;
    private final UserService userService;

    public AuthController(
            LocalAuthProvider localAuthProvider, AuthService authService, UserService userService) {
        this.localAuthProvider = localAuthProvider;
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        String username = localAuthProvider
                .authenticate(new AuthCredentials(request.username(), request.password(), null))
                .orElseThrow(() -> ApiException.unauthorized("用户名或密码错误"));
        return authService.issueFor(username);
    }

    @GetMapping("/me")
    public UserSummary me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return userService.toSummary(userService.getByUsername(authentication.getName()));
    }
}
