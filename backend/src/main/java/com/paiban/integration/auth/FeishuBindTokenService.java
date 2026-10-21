package com.paiban.integration.auth;

import com.paiban.integration.feishu.FeishuUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Short-lived proof that a Feishu user authenticated but has no local account yet.
 * The signing key is derived from the login secret with a purpose suffix, so a bind
 * token can never be replayed as a session token (and vice versa).
 */
@Service
public class FeishuBindTokenService {

    static final String PURPOSE = "feishu-bind";
    private static final long TTL_MINUTES = 10;

    private final SecretKey key;

    public FeishuBindTokenService(@Value("${paiban.security.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(derive(secret));
    }

    public String issue(FeishuUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.userId() != null ? user.userId() : user.openId())
                .claim("purpose", PURPOSE)
                .claim("openId", user.openId())
                .claim("userId", user.userId())
                .claim("name", user.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TTL_MINUTES, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /** Returns the Feishu identity when the token is valid, unexpired and a bind token. */
    public Optional<FeishuUser> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            if (!PURPOSE.equals(claims.get("purpose", String.class))) {
                return Optional.empty();
            }
            String openId = claims.get("openId", String.class);
            String userId = claims.get("userId", String.class);
            if (openId == null && userId == null) {
                return Optional.empty();
            }
            return Optional.of(new FeishuUser(
                    userId, openId, claims.get("name", String.class), null, null, null));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public long ttlMinutes() {
        return TTL_MINUTES;
    }

    private static byte[] derive(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest((secret + ":" + PURPOSE).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
