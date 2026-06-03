package com.ironside.challengesphere.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class StravaOAuthStateSigner {
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private final String secret;

    public StravaOAuthStateSigner(@Value("${jwt.secret:challenge-sphere-dev-secret}") String secret) {
        this.secret = secret == null || secret.isBlank() ? "challenge-sphere-dev-secret" : secret;
    }

    public String sign(Long userId, Instant now) {
        String payload = userId + ":" + now.getEpochSecond() + ":" + UUID.randomUUID();
        String signature = hmac(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + ":" + signature).getBytes(StandardCharsets.UTF_8));
    }

    public boolean verify(String state, Long expectedUserId, Instant now) {
        if (state == null || state.isBlank() || expectedUserId == null) {
            return false;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            if (parts.length != 4) {
                return false;
            }
            Long userId = Long.valueOf(parts[0]);
            long issuedAtEpochSeconds = Long.parseLong(parts[1]);
            String payload = parts[0] + ":" + parts[1] + ":" + parts[2];
            String expectedSignature = hmac(payload);
            Instant issuedAt = Instant.ofEpochSecond(issuedAtEpochSeconds);
            return expectedUserId.equals(userId)
                    && !issuedAt.plus(STATE_TTL).isBefore(now)
                    && constantTimeEquals(expectedSignature, parts[3]);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign Strava OAuth state", e);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        if (leftBytes.length != rightBytes.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < leftBytes.length; i++) {
            diff |= leftBytes[i] ^ rightBytes[i];
        }
        return diff == 0;
    }
}
