package com.paiban.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * JWT secret validation (deploy-friendly review finding P1).
 *
 * <p>Copying {@code .env.example} to {@code .env} leaves non-empty placeholder secrets such as
 * {@code replace-with-at-least-32-random-characters}. They are long enough to pass a bare length
 * check but are public, so the service must refuse them outside the development profiles.
 */
class JwtServiceTest {

    private static final String HEX_64 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static JwtService service(String secret, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new JwtService(secret, 480L, environment);
    }

    @Test
    void acceptsAStrongSecretInTheProductionProfile() {
        JwtService jwtService = service(HEX_64, "postgres");

        String token = jwtService.issue("admin", java.util.List.of("admin"), java.util.List.of());

        assertThat(jwtService.parse(token).getSubject()).isEqualTo("admin");
    }

    @Test
    void rejectsTheReplaceWithPlaceholderInTheProductionProfile() {
        assertThatThrownBy(() -> service("replace-with-at-least-32-random-characters", "postgres"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT 密钥不安全");
    }

    @Test
    void rejectsTheReplaceWithUnderscorePlaceholderInTheProductionProfile() {
        assertThatThrownBy(() -> service("replace_with_at_least_32_random_characters", "postgres"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT 密钥不安全");
    }

    @Test
    void rejectsAShortSecretInTheProductionProfile() {
        assertThatThrownBy(() -> service("short-secret", "postgres"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT 密钥不安全");
    }

    @Test
    void rejectsTheDevelopmentDefaultInTheProductionProfile() {
        assertThatThrownBy(() -> service(
                        "paiban-local-development-secret-key-change-me-please-0123456789", "postgres"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT 密钥不安全");
    }

    @Test
    void acceptsTheDevelopmentDefaultInTheLocalProfile() {
        JwtService jwtService = service(
                "paiban-local-development-secret-key-change-me-please-0123456789", "local");

        String token = jwtService.issue("admin", java.util.List.of("admin"), java.util.List.of());

        assertThat(jwtService.parse(token).getSubject()).isEqualTo("admin");
    }
}
