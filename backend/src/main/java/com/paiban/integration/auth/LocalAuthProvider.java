package com.paiban.integration.auth;

import java.util.Optional;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

@Component
public class LocalAuthProvider implements AuthProvider {

    private final AuthenticationManager authenticationManager;

    public LocalAuthProvider(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
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
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(credentials.username(), credentials.password()));
            return Optional.of(credentials.username());
        } catch (AuthenticationException ex) {
            return Optional.empty();
        }
    }
}
