package com.ironside.challengesphere.persistence.dynamodb.foundation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes DynamoDB pagination state as a signed, URL-safe opaque cursor.
 * The cursor is cryptographically bound to a caller-supplied stable query
 * description, so it cannot be replayed against different filters or users.
 */
public final class DynamoDbCursorCodec {

    private static final int FORMAT_VERSION = 1;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_SECRET_BYTES = 32;
    private static final Duration MAX_FUTURE_CLOCK_SKEW = Duration.ofMinutes(1);
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final Duration maxAge;
    private final Clock clock;

    public DynamoDbCursorCodec(ObjectMapper objectMapper, String configuredSecret, Duration maxAge) {
        this(objectMapper, configuredSecret, maxAge, Clock.systemUTC());
    }

    DynamoDbCursorCodec(
            ObjectMapper objectMapper,
            String configuredSecret,
            Duration maxAge,
            Clock clock
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.secret = decodeSecret(configuredSecret);
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("cursor maxAge must be positive");
        }
    }

    public String encode(String queryBinding, CursorState state) {
        requireQueryBinding(queryBinding);
        Objects.requireNonNull(state, "state");
        CursorEnvelope envelope = new CursorEnvelope(
                FORMAT_VERSION,
                queryHash(queryBinding),
                clock.instant().getEpochSecond(),
                encodeKey(state.lastEvaluatedKey()),
                encodeShardState(state.shardState())
        );
        try {
            byte[] payload = objectMapper.writeValueAsBytes(envelope);
            return URL_ENCODER.encodeToString(payload)
                    + "."
                    + URL_ENCODER.encodeToString(sign(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode DynamoDB cursor", exception);
        }
    }

    public CursorState decode(String cursor, String queryBinding) {
        requireQueryBinding(queryBinding);
        if (cursor == null || cursor.isBlank()) {
            throw new InvalidCursorException("Cursor must not be blank");
        }

        int separator = cursor.indexOf('.');
        if (separator <= 0 || separator != cursor.lastIndexOf('.') || separator == cursor.length() - 1) {
            throw new InvalidCursorException("Cursor has an invalid format");
        }

        try {
            byte[] payload = URL_DECODER.decode(cursor.substring(0, separator));
            byte[] suppliedSignature = URL_DECODER.decode(cursor.substring(separator + 1));
            if (!MessageDigest.isEqual(sign(payload), suppliedSignature)) {
                throw new InvalidCursorException("Cursor signature is invalid");
            }

            CursorEnvelope envelope = objectMapper.readValue(payload, CursorEnvelope.class);
            validateEnvelope(envelope, queryBinding);
            return new CursorState(
                    decodeKey(envelope.lastEvaluatedKey()),
                    decodeShardState(envelope.shardState())
            );
        } catch (IllegalArgumentException exception) {
            if (exception instanceof InvalidCursorException invalidCursorException) {
                throw invalidCursorException;
            }
            throw new InvalidCursorException("Cursor is not valid URL-safe Base64", exception);
        } catch (IOException exception) {
            throw new InvalidCursorException("Cursor payload is invalid", exception);
        }
    }

    private void validateEnvelope(CursorEnvelope envelope, String queryBinding) {
        if (envelope.version() != FORMAT_VERSION) {
            throw new InvalidCursorException("Cursor version is not supported");
        }
        byte[] expectedQueryHash = queryHash(queryBinding).getBytes(StandardCharsets.US_ASCII);
        byte[] actualQueryHash = Objects.requireNonNullElse(envelope.queryHash(), "")
                .getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expectedQueryHash, actualQueryHash)) {
            throw new InvalidCursorException("Cursor does not belong to this query");
        }

        Instant issuedAt = Instant.ofEpochSecond(envelope.issuedAtEpochSecond());
        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plus(MAX_FUTURE_CLOCK_SKEW))) {
            throw new InvalidCursorException("Cursor issue time is invalid");
        }
        if (issuedAt.plus(maxAge).isBefore(now)) {
            throw new InvalidCursorException("Cursor has expired");
        }
    }

    private String queryHash(String queryBinding) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return URL_ENCODER.encodeToString(digest.digest(queryBinding.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static Map<String, EncodedAttribute> encodeKey(Map<String, AttributeValue> key) {
        Map<String, EncodedAttribute> encoded = new LinkedHashMap<>();
        key.forEach((name, value) -> encoded.put(name, EncodedAttribute.from(value)));
        return Map.copyOf(encoded);
    }

    private static Map<String, Map<String, EncodedAttribute>> encodeShardState(
            Map<String, Map<String, AttributeValue>> shardState
    ) {
        Map<String, Map<String, EncodedAttribute>> encoded = new LinkedHashMap<>();
        shardState.forEach((shard, key) -> encoded.put(shard, encodeKey(key)));
        return Map.copyOf(encoded);
    }

    private static Map<String, AttributeValue> decodeKey(Map<String, EncodedAttribute> key) {
        if (key == null || key.isEmpty()) {
            return Map.of();
        }
        Map<String, AttributeValue> decoded = new LinkedHashMap<>();
        key.forEach((name, value) -> decoded.put(name, value.toAttributeValue()));
        return Map.copyOf(decoded);
    }

    private static Map<String, Map<String, AttributeValue>> decodeShardState(
            Map<String, Map<String, EncodedAttribute>> shardState
    ) {
        if (shardState == null || shardState.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, AttributeValue>> decoded = new LinkedHashMap<>();
        shardState.forEach((shard, key) -> decoded.put(shard, decodeKey(key)));
        return Map.copyOf(decoded);
    }

    private static byte[] decodeSecret(String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            throw new IllegalArgumentException("cursor secret must not be blank");
        }
        byte[] decoded;
        try {
            decoded = configuredSecret.startsWith("base64:")
                    ? Base64.getDecoder().decode(configuredSecret.substring("base64:".length()))
                    : configuredSecret.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cursor secret is not valid Base64", exception);
        }
        if (decoded.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "cursor secret must contain at least " + MINIMUM_SECRET_BYTES + " bytes"
            );
        }
        return decoded.clone();
    }

    private static void requireQueryBinding(String queryBinding) {
        if (queryBinding == null || queryBinding.isBlank()) {
            throw new IllegalArgumentException("queryBinding must not be blank");
        }
    }

    public record CursorState(
            Map<String, AttributeValue> lastEvaluatedKey,
            Map<String, Map<String, AttributeValue>> shardState
    ) {
        public CursorState {
            lastEvaluatedKey = immutableKey(lastEvaluatedKey);
            shardState = immutableShardState(shardState);
        }

        public CursorState(Map<String, AttributeValue> lastEvaluatedKey) {
            this(lastEvaluatedKey, Map.of());
        }

        private static Map<String, AttributeValue> immutableKey(Map<String, AttributeValue> key) {
            return key == null || key.isEmpty() ? Map.of() : Map.copyOf(key);
        }

        private static Map<String, Map<String, AttributeValue>> immutableShardState(
                Map<String, Map<String, AttributeValue>> state
        ) {
            if (state == null || state.isEmpty()) {
                return Map.of();
            }
            Map<String, Map<String, AttributeValue>> copy = new LinkedHashMap<>();
            state.forEach((shard, key) -> copy.put(shard, immutableKey(key)));
            return Map.copyOf(copy);
        }
    }

    private record CursorEnvelope(
            int version,
            String queryHash,
            long issuedAtEpochSecond,
            Map<String, EncodedAttribute> lastEvaluatedKey,
            Map<String, Map<String, EncodedAttribute>> shardState
    ) {
    }

    private record EncodedAttribute(String type, String value) {

        private static EncodedAttribute from(AttributeValue attributeValue) {
            Objects.requireNonNull(attributeValue, "attributeValue");
            if (attributeValue.s() != null) {
                return new EncodedAttribute("S", attributeValue.s());
            }
            if (attributeValue.n() != null) {
                return new EncodedAttribute("N", attributeValue.n());
            }
            if (attributeValue.b() != null) {
                return new EncodedAttribute(
                        "B",
                        Base64.getEncoder().encodeToString(attributeValue.b().asByteArray())
                );
            }
            throw new IllegalArgumentException("DynamoDB cursor keys may only contain S, N, or B values");
        }

        private AttributeValue toAttributeValue() {
            if (type == null || value == null) {
                throw new InvalidCursorException("Cursor contains an incomplete key attribute");
            }
            return switch (type) {
                case "S" -> AttributeValue.fromS(value);
                case "N" -> AttributeValue.fromN(value);
                case "B" -> AttributeValue.fromB(SdkBytes.fromByteArray(Base64.getDecoder().decode(value)));
                default -> throw new InvalidCursorException("Cursor contains an unsupported key type");
            };
        }
    }

    public static final class InvalidCursorException extends IllegalArgumentException {
        public InvalidCursorException(String message) {
            super(message);
        }

        public InvalidCursorException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
