package com.paiban.identity.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final String DEV_MARKER = "paiban-local-development-secret";

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(
            @Value("${paiban.security.jwt-secret}") String secret,
            @Value("${paiban.security.jwt-expiration-minutes:480}") long expirationMinutes,
            Environment environment) {
        boolean devProfile = false;
        for (String profile : environment.getActiveProfiles()) {
            String value = profile.toLowerCase(Locale.ROOT);
            if (value.equals("local") || value.equals("test") || value.equals("e2e")) {
                devProfile = true;
            }
        }
        if (!devProfile
                && (secret.contains(DEV_MARKER)
                || secret.contains("change-me")
                || secret.length() < 32)) {
            throw new IllegalStateException(
                    "JWT 密钥不安全。非本地环境必须设置 PAIBAN_JWT_SECRET（至少 32 个字符，且不能使用示例值）。");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
        if (secret.contains(DEV_MARKER)) {
            org.slf4j.LoggerFactory.getLogger(JwtService.class)
                    .warn("正在使用默认 JWT 密钥，仅限本地开发；生产环境请设置 PAIBAN_JWT_SECRET");
        }
    }

    public String issue(String username, List<String> roles, List<String> permissions) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("roles", roles)
                .claim("perms", permissions)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationMinutes() {
        return expirationMinutes;
    }
}
