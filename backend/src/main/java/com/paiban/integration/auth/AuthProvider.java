package com.paiban.integration.auth;

import java.util.Optional;

/** Pluggable authentication source: local password or DingTalk免登. */
public interface AuthProvider {

    String code();

    Optional<String> authenticate(AuthCredentials credentials);
}
