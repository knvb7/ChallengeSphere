package com.ironside.challengesphere.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Service
public class JwtService {

    private static final String LOCAL_DEV_FALLBACK_SECRET =
            "challengesphere-local-dev-secret-change-me-please-256-bits-long";
    private static final int MIN_HMAC_KEY_BYTES = 32;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.access-token.expiration:3600000}")
    private long accessTokenExpiryMs;

    @Value("${jwt.refresh-token.expiration:604800000}")
    private long refreshTokenExpiryMs;

    private final Environment environment;
    private Key signingKey;

    @Autowired
    public JwtService(Environment environment) {
        this.environment = environment;
    }

    public JwtService() {
        this.environment = null;
    }

    @PostConstruct
    void validateSigningSecret() {
        signingKey = createSigningKey();
    }

    public String generateAccessToken(Long userId, String username) {
        return generateToken(userId, username, "access", accessTokenExpiryMs);
    }

    public String generateRefreshToken(Long userId, String username) {
        return generateToken(userId, username, "refresh", refreshTokenExpiryMs);
    }

    public boolean isValidToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(token);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public Long extractUserId(String token) {
        Claims claims = extractClaims(token);
        return claims.get("uid", Long.class);
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    public boolean isRefreshToken(String token) {
        return isTokenType(token, "refresh");
    }

    public boolean isAccessToken(String token) {
        return isTokenType(token, "access");
    }

    public boolean isValidAccessToken(String token) {
        return isValidToken(token) && isAccessToken(token);
    }

    public long getAccessTokenExpiryMs() {
        return accessTokenExpiryMs;
    }

    public long getRefreshTokenExpiryMs() {
        return refreshTokenExpiryMs;
    }

    private String generateToken(Long userId, String username, String type, long ttlMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttlMs);
        return Jwts.builder()
                .setSubject(username)
                .claim("uid", userId)
                .claim("typ", type)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    private Claims extractClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private boolean isTokenType(String token, String expectedType) {
        try {
            String tokenType = extractClaims(token).get("typ", String.class);
            return expectedType.equals(tokenType);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Key getSigningKey() {
        if (signingKey == null) {
            signingKey = createSigningKey();
        }
        return signingKey;
    }

    private Key createSigningKey() {
        if (!StringUtils.hasText(jwtSecret)) {
            throw new IllegalStateException("jwt.secret must be configured");
        }
        if (isProdProfile() && LOCAL_DEV_FALLBACK_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException("Production jwt.secret must not use the local development fallback");
        }

        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(jwtSecret);
        } catch (Exception ignored) {
            keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        }

        if (keyBytes.length < 32) {
            throw new IllegalStateException("jwt.secret must be at least "
                    + MIN_HMAC_KEY_BYTES + " bytes for HS256");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private boolean isProdProfile() {
        return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
    }
}
