package com.paiban.identity.service;

import com.paiban.identity.domain.AppUser;
import com.paiban.identity.security.JwtService;
import com.paiban.identity.web.IdentityDtos.LoginResponse;
import com.paiban.identity.web.IdentityDtos.UserSummary;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final JwtService jwtService;
    private final UserService users;

    public AuthService(JwtService jwtService, UserService users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    public LoginResponse issueFor(String username) {
        AppUser user = users.getByUsername(username);
        UserSummary summary = users.toSummary(user);
        String token = jwtService.issue(summary.username(), summary.roles(), summary.permissions());
        return new LoginResponse(token, jwtService.getExpirationMinutes(), summary);
    }
}
