package com.paiban.identity.web;

import com.paiban.common.web.ApiException;
import com.paiban.identity.domain.AppUser;
import com.paiban.identity.security.JwtService;
import com.paiban.identity.service.UserService;
import com.paiban.identity.web.IdentityDtos.LoginRequest;
import com.paiban.identity.web.IdentityDtos.LoginResponse;
import com.paiban.identity.web.IdentityDtos.UserSummary;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserService userService;

    public AuthController(
            AuthenticationManager authenticationManager, JwtService jwtService, UserService userService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (BadCredentialsException ex) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        AppUser user = userService.getByUsername(request.username());
        UserSummary summary = userService.toSummary(user);
        String token = jwtService.issue(summary.username(), summary.roles(), summary.permissions());
        return new LoginResponse(token, jwtService.getExpirationMinutes(), summary);
    }

    @GetMapping("/me")
    public UserSummary me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return userService.toSummary(userService.getByUsername(authentication.getName()));
    }
}
