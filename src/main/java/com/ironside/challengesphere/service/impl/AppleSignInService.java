package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.AppleAuthRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AppleSignInService {

    private static final Logger logger = LogManager.getLogger(AppleSignInService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private static final String APPLE_ISSUER = "https://appleid.apple.com";
    private static final URI APPLE_TOKEN_URI = URI.create("https://appleid.apple.com/auth/token");
    private static final URI APPLE_REVOKE_URI = URI.create("https://appleid.apple.com/auth/revoke");
    private static final Duration MAX_IDENTITY_TOKEN_AGE = Duration.ofMinutes(10);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);
    private static final Duration CLIENT_SECRET_LIFETIME = Duration.ofDays(30);
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    @Value("${apple.signin.enabled:false}")
    private boolean enabled;

    @Value("${apple.signin.client-ids:}")
    private String configuredClientIds;

    @Value("${apple.signin.team-id:}")
    private String teamId;

    @Value("${apple.signin.key-id:}")
    private String keyId;

    @Value("${apple.signin.private-key-base64:}")
    private String privateKeyBase64;

    @Value("${apple.signin.token-encryption-key-base64:}")
    private String tokenEncryptionKeyBase64;

    @Value("${apple.signin.jwks-uri:https://appleid.apple.com/auth/keys}")
    private String jwksUri;

    private volatile DefaultJWTProcessor<SecurityContext> jwtProcessor;

    public VerifiedAppleAccount verifyAndExchange(AppleAuthRequest request)
            throws ChallengeSphereException {
        requireEnabledConfiguration();
        JWTClaimsSet claims = verifyIdentityToken(request);
        String subject = claims.getSubject();
        String email = verifiedEmail(claims);
        String encryptedRefreshToken = exchangeAuthorizationCode(request.getAuthorizationCode());
        return new VerifiedAppleAccount(subject, email, encryptedRefreshToken);
    }

    public AppleAccountEvent verifyServerNotification(String signedPayload)
            throws ChallengeSphereException {
        if (!enabled || allowedClientIds().isEmpty()) {
            throw appleConfigurationError();
        }
        if (!StringUtils.hasText(signedPayload)) {
            throw new ChallengeSphereException(
                    "signedPayload is required",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        try {
            JWTClaimsSet claims = getJwtProcessor().process(signedPayload, null);
            if (!APPLE_ISSUER.equals(claims.getIssuer())
                    || claims.getAudience() == null
                    || claims.getAudience().stream().noneMatch(allowedClientIds()::contains)
                    || !StringUtils.hasText(claims.getJWTID())) {
                throw invalidIdentityToken("server notification claims");
            }
            Date issuedAt = claims.getIssueTime();
            if (issuedAt == null || issuedAt.toInstant().isAfter(Instant.now().plus(CLOCK_SKEW))) {
                throw invalidIdentityToken("server notification time");
            }

            Object rawEvents = claims.getClaim("events");
            Map<String, Object> events;
            if (rawEvents instanceof String serializedEvents) {
                events = OBJECT_MAPPER.readValue(serializedEvents, JSON_MAP);
            } else {
                events = OBJECT_MAPPER.convertValue(rawEvents, JSON_MAP);
            }
            String type = string(events.get("type"));
            String subject = string(events.get("sub"));
            if (!Set.of(
                    "email-disabled",
                    "email-enabled",
                    "consent-revoked",
                    "account-delete",
                    "account-deleted"
            ).contains(type) || !StringUtils.hasText(subject)) {
                throw invalidIdentityToken("server notification event");
            }
            Long eventTime = events.get("event_time") instanceof Number number
                    ? number.longValue()
                    : null;
            return new AppleAccountEvent(claims.getJWTID(), type, subject, eventTime);
        } catch (ChallengeSphereException error) {
            throw error;
        } catch (Exception error) {
            logger.warn("Apple server notification verification failed: {}",
                    error.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Invalid Apple server notification",
                    HttpStatus.UNAUTHORIZED,
                    logger
            );
        }
    }

    public void revokeEncryptedRefreshToken(String encryptedToken) throws ChallengeSphereException {
        if (!StringUtils.hasText(encryptedToken)) {
            return;
        }
        requireEnabledConfiguration();
        String refreshToken = decryptToken(encryptedToken);
        String form = form(Map.of(
                "client_id", primaryClientId(),
                "client_secret", createClientSecret(),
                "token", refreshToken,
                "token_type_hint", "refresh_token"
        ));
        HttpResponse<String> response = sendForm(APPLE_REVOKE_URI, form);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            logger.warn("Apple token revocation failed with status={}", response.statusCode());
            throw new ChallengeSphereException(
                    "Apple authorization could not be revoked",
                    HttpStatus.BAD_GATEWAY,
                    logger
            );
        }
    }

    private JWTClaimsSet verifyIdentityToken(AppleAuthRequest request) throws ChallengeSphereException {
        try {
            JWTClaimsSet claims = getJwtProcessor().process(request.getIdentityToken(), null);
            Instant now = Instant.now();
            Date expiration = claims.getExpirationTime();
            Date issuedAt = claims.getIssueTime();
            if (!APPLE_ISSUER.equals(claims.getIssuer())) {
                throw invalidIdentityToken("issuer");
            }
            if (claims.getAudience() == null
                    || claims.getAudience().stream().noneMatch(allowedClientIds()::contains)) {
                throw invalidIdentityToken("audience");
            }
            if (expiration == null || expiration.toInstant().isBefore(now.minus(CLOCK_SKEW))) {
                throw invalidIdentityToken("expiration");
            }
            if (issuedAt == null
                    || issuedAt.toInstant().isAfter(now.plus(CLOCK_SKEW))
                    || issuedAt.toInstant().isBefore(now.minus(MAX_IDENTITY_TOKEN_AGE))) {
                throw invalidIdentityToken("issued-at time");
            }
            if (!StringUtils.hasText(claims.getSubject())
                    || !MessageDigest.isEqual(
                    claims.getSubject().getBytes(StandardCharsets.UTF_8),
                    request.getUser().getBytes(StandardCharsets.UTF_8))) {
                throw invalidIdentityToken("subject");
            }
            String expectedNonce = sha256Hex(request.getRawNonce());
            String tokenNonce = claims.getStringClaim("nonce");
            if (!StringUtils.hasText(tokenNonce)
                    || !MessageDigest.isEqual(
                    expectedNonce.getBytes(StandardCharsets.US_ASCII),
                    tokenNonce.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
                throw invalidIdentityToken("nonce");
            }
            return claims;
        } catch (ChallengeSphereException error) {
            throw error;
        } catch (Exception error) {
            logger.warn("Apple identity token verification failed: {}", error.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Invalid Apple identity token",
                    HttpStatus.UNAUTHORIZED,
                    logger
            );
        }
    }

    private String verifiedEmail(JWTClaimsSet claims) throws ChallengeSphereException {
        try {
            String email = claims.getStringClaim("email");
            if (!StringUtils.hasText(email)) {
                return null;
            }
            Object verifiedClaim = claims.getClaim("email_verified");
            if (verifiedClaim != null && !Boolean.parseBoolean(String.valueOf(verifiedClaim))) {
                throw invalidIdentityToken("email verification");
            }
            return email.trim().toLowerCase(Locale.ROOT);
        } catch (java.text.ParseException error) {
            throw invalidIdentityToken("email");
        }
    }

    private String exchangeAuthorizationCode(String authorizationCode) throws ChallengeSphereException {
        String form = form(Map.of(
                "client_id", primaryClientId(),
                "client_secret", createClientSecret(),
                "code", authorizationCode,
                "grant_type", "authorization_code"
        ));
        HttpResponse<String> response = sendForm(APPLE_TOKEN_URI, form);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            logger.warn("Apple authorization code exchange failed with status={}", response.statusCode());
            throw new ChallengeSphereException(
                    "Apple authorization code is invalid or expired",
                    HttpStatus.UNAUTHORIZED,
                    logger
            );
        }
        try {
            Map<String, Object> body = OBJECT_MAPPER.readValue(response.body(), JSON_MAP);
            String refreshToken = body.get("refresh_token") == null
                    ? null
                    : String.valueOf(body.get("refresh_token"));
            if (!StringUtils.hasText(refreshToken)) {
                throw new ChallengeSphereException(
                        "Apple did not return a revocable authorization",
                        HttpStatus.BAD_GATEWAY,
                        logger
                );
            }
            return encryptToken(refreshToken);
        } catch (ChallengeSphereException error) {
            throw error;
        } catch (Exception error) {
            logger.error("Apple token response could not be processed");
            throw new ChallengeSphereException(
                    "Apple authorization could not be completed",
                    HttpStatus.BAD_GATEWAY,
                    logger
            );
        }
    }

    private HttpResponse<String> sendForm(URI uri, String formBody) throws ChallengeSphereException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw appleUnavailable();
        } catch (Exception error) {
            logger.warn("Apple authorization endpoint unavailable: {}", error.getClass().getSimpleName());
            throw appleUnavailable();
        }
    }

    private DefaultJWTProcessor<SecurityContext> getJwtProcessor() throws Exception {
        DefaultJWTProcessor<SecurityContext> current = jwtProcessor;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (jwtProcessor == null) {
                JWKSource<SecurityContext> jwkSource = new RemoteJWKSet<>(URI.create(jwksUri).toURL());
                DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
                processor.setJWSKeySelector(
                        new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource)
                );
                jwtProcessor = processor;
            }
            return jwtProcessor;
        }
    }

    private String createClientSecret() throws ChallengeSphereException {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(teamId)
                    .subject(primaryClientId())
                    .audience(APPLE_ISSUER)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(CLIENT_SECRET_LIFETIME)))
                    .build();
            SignedJWT clientSecret = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256)
                            .keyID(keyId)
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims
            );
            clientSecret.sign(new ECDSASigner(loadPrivateKey()));
            return clientSecret.serialize();
        } catch (JOSEException error) {
            logger.error("Apple client secret signing failed");
            throw appleConfigurationError();
        }
    }

    private ECPrivateKey loadPrivateKey() throws ChallengeSphereException {
        try {
            String configured = privateKeyBase64.trim().replace("\\n", "\n");
            byte[] keyBytes;
            if (configured.contains("BEGIN PRIVATE KEY")) {
                keyBytes = decodePem(configured);
            } else {
                byte[] decoded = Base64.getDecoder().decode(configured);
                String decodedText = new String(decoded, StandardCharsets.UTF_8);
                keyBytes = decodedText.contains("BEGIN PRIVATE KEY")
                        ? decodePem(decodedText)
                        : decoded;
            }
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception error) {
            logger.error("Apple private key could not be loaded");
            throw appleConfigurationError();
        }
    }

    private byte[] decodePem(String pem) {
        String body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private String encryptToken(String token) throws ChallengeSphereException {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, tokenEncryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return "v1." + encoder.encodeToString(iv) + "." + encoder.encodeToString(encrypted);
        } catch (ChallengeSphereException error) {
            throw error;
        } catch (Exception error) {
            logger.error("Apple refresh token encryption failed");
            throw appleConfigurationError();
        }
    }

    private String decryptToken(String encryptedToken) throws ChallengeSphereException {
        try {
            String[] parts = encryptedToken.split("\\.");
            if (parts.length != 3 || !"v1".equals(parts[0])) {
                throw new IllegalArgumentException("Unsupported token envelope");
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(parts[1]);
            byte[] ciphertext = decoder.decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, tokenEncryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (ChallengeSphereException error) {
            throw error;
        } catch (Exception error) {
            logger.error("Stored Apple refresh token could not be decrypted");
            throw appleConfigurationError();
        }
    }

    private SecretKeySpec tokenEncryptionKey() throws ChallengeSphereException {
        try {
            byte[] key = Base64.getDecoder().decode(tokenEncryptionKeyBase64.trim());
            if (key.length != 32) {
                throw new IllegalArgumentException("Expected 32-byte key");
            }
            return new SecretKeySpec(key, "AES");
        } catch (Exception error) {
            throw appleConfigurationError();
        }
    }

    private void requireEnabledConfiguration() throws ChallengeSphereException {
        if (!enabled) {
            throw new ChallengeSphereException(
                    "Sign in with Apple is currently disabled",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    logger
            );
        }
        if (allowedClientIds().isEmpty()
                || !StringUtils.hasText(teamId)
                || !StringUtils.hasText(keyId)
                || !StringUtils.hasText(privateKeyBase64)
                || !StringUtils.hasText(tokenEncryptionKeyBase64)) {
            throw appleConfigurationError();
        }
    }

    private List<String> allowedClientIds() {
        if (!StringUtils.hasText(configuredClientIds)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : configuredClientIds.split(",")) {
            String clientId = value.trim();
            if (!clientId.isEmpty()) {
                result.add(clientId);
            }
        }
        return List.copyOf(result);
    }

    private String primaryClientId() throws ChallengeSphereException {
        List<String> clientIds = allowedClientIds();
        if (clientIds.isEmpty()) {
            throw appleConfigurationError();
        }
        return clientIds.get(0);
    }

    private String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String sha256Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private ChallengeSphereException invalidIdentityToken(String reason) {
        logger.warn("Rejected Apple identity token due to invalid {}", reason);
        return new ChallengeSphereException(
                "Invalid Apple identity token",
                HttpStatus.UNAUTHORIZED,
                logger
        );
    }

    private ChallengeSphereException appleUnavailable() {
        return new ChallengeSphereException(
                "Apple authorization is temporarily unavailable",
                HttpStatus.BAD_GATEWAY,
                logger
        );
    }

    private ChallengeSphereException appleConfigurationError() {
        return new ChallengeSphereException(
                "Sign in with Apple is not configured on this server",
                HttpStatus.SERVICE_UNAVAILABLE,
                logger
        );
    }

    public record VerifiedAppleAccount(
            String subject,
            String email,
            String encryptedRefreshToken
    ) {}

    public record AppleAccountEvent(
            String jti,
            String type,
            String subject,
            Long eventTime
    ) {}
}
