package com.rssolplan.edu.global.config;

import com.rssolplan.edu.domain.auth.passkey.PasskeyRealm;
import com.rssolplan.edu.global.fordevToken.LocalDevLoginEnabled;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

@Component
public class JwtTokenProvider {
    private final SecretKey key;
    private final JdbcTemplate db;
    private final PasskeyRealm realm;
    private final Environment environment;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            JdbcTemplate db,
            PasskeyRealm realm,
            Environment environment) {
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.db = db;
        this.realm = realm;
        this.environment = environment;
    }

    /** Used only by the conditional local dev controller. */
    public String generateAccess(Long userId) {
        if (!LocalDevLoginEnabled.enabled(environment))
            throw new IllegalStateException("Local login disabled");
        return issue(userId, "access", "dev", null);
    }

    public String issue(long userId, String use, String method, String credentialId) {
        return issue(userId, use, method, credentialId, null);
    }

    public String issueKakao(long userId, String use, Long linkedUser) {
        return issue(userId, use, "kakao", null, linkedUser);
    }

    private String issue(
            long userId, String use, String method, String credentialId, Long linkedUser) {
        Instant now = Instant.now();
        Duration lifetime = "refresh".equals(use) ? Duration.ofDays(14) : Duration.ofHours(1);
        return Jwts.builder()
                .setIssuer(realm.origin())
                .setSubject(Long.toString(userId))
                .setId(UUID.randomUUID().toString())
                .claim("use", use)
                .claim("amr", method)
                .claim("credential", credentialId)
                .claim("linkedUser", linkedUser == null ? null : linkedUser.toString())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(lifetime)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean enrolled(long user) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM passkey_credential WHERE rp_id=? AND"
                                + " user_id=?)",
                        Boolean.class,
                        realm.id(),
                        user));
    }

    private Claims claims(String token, String use) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .requireIssuer(realm.origin())
                .require("use", use)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private boolean active(Claims claims) {
        long user = Long.parseLong(claims.getSubject());
        String method = claims.get("amr", String.class);
        if ("dev".equals(method)) return LocalDevLoginEnabled.enabled(environment);
        if ("kakao".equals(method)) {
            String linkedUser = claims.get("linkedUser", String.class);
            return !enrolled(user) && (linkedUser == null || !enrolled(Long.parseLong(linkedUser)));
        }
        if (!"passkey".equals(method)) return false;
        String id = claims.get("credential", String.class);
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
SELECT EXISTS(SELECT 1 FROM passkey_credential WHERE rp_id=? AND user_id=? AND SHA2(credential_id,256)=?)
""",
                        Boolean.class,
                        realm.id(),
                        user,
                        id));
    }

    public boolean validate(String token) {
        try {
            return active(claims(token, "access"));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String renewAccess(String refreshToken, long user) {
        try {
            Claims refresh = claims(refreshToken, "refresh");
            if (Long.parseLong(refresh.getSubject()) == user && active(refresh)) {
                if ("kakao".equals(refresh.get("amr", String.class))) {
                    String linked = refresh.get("linkedUser", String.class);
                    return issueKakao(user, "access", linked == null ? null : Long.valueOf(linked));
                }
                return issue(
                        user,
                        "access",
                        refresh.get("amr", String.class),
                        refresh.get("credential", String.class));
            }
        } catch (JwtException | IllegalArgumentException ignored) {
            // Legacy, expired and wrong-purpose tokens require a fresh login.
        }
        throw new com.rssolplan.edu.global.exception.UnauthorizedException("다시 로그인해 주세요.");
    }

    public Long getUserId(String token) {
        return Long.valueOf(claims(token, "access").getSubject());
    }
}
