package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.config.RevenueCatProperties;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

@Component
public class RevenueCatClient {
    private static final Logger logger = LogManager.getLogger(RevenueCatClient.class);

    private final RevenueCatProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public RevenueCatClient(RevenueCatProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public EntitlementSnapshot getPremiumEntitlement(String appUserId)
            throws ChallengeSphereException {
        if (!properties.isEnabled() || properties.getSecretApiKey().isBlank()) {
            throw new ChallengeSphereException(
                    "Purchases are not configured",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    logger
            );
        }

        try {
            String encodedId = URLEncoder.encode(appUserId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getApiBaseUrl() + "/v1/subscribers/" + encodedId))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + properties.getSecretApiKey())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn("RevenueCat subscriber lookup failed | status={}", response.statusCode());
                throw new ChallengeSphereException(
                        "Could not verify subscription status",
                        HttpStatus.BAD_GATEWAY,
                        logger
                );
            }

            JsonNode subscriber = objectMapper.readTree(response.body()).path("subscriber");
            JsonNode entitlement = subscriber.path("entitlements")
                    .path(properties.getEntitlementId());
            if (entitlement.isMissingNode() || entitlement.isNull()) {
                return EntitlementSnapshot.inactive();
            }

            String productId = textOrNull(entitlement, "product_identifier");
            Timestamp expiresAt = timestampOrNull(entitlement, "expires_date");
            boolean active = expiresAt == null || expiresAt.toInstant().isAfter(Instant.now());
            JsonNode subscription = productId == null
                    ? null
                    : subscriber.path("subscriptions").path(productId);
            boolean willRenew = active
                    && subscription != null
                    && !subscription.isMissingNode()
                    && (subscription.path("unsubscribe_detected_at").isMissingNode()
                    || subscription.path("unsubscribe_detected_at").isNull());
            String store = subscription == null ? null : textOrNull(subscription, "store");

            return new EntitlementSnapshot(
                    active,
                    productId,
                    active ? "ACTIVE" : "EXPIRED",
                    store,
                    expiresAt,
                    willRenew
            );
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(e);
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    private ChallengeSphereException unavailable(Exception cause) {
        logger.warn("RevenueCat subscriber lookup unavailable | exception={}",
                cause.getClass().getSimpleName());
        return new ChallengeSphereException(
                "Could not verify subscription status",
                HttpStatus.BAD_GATEWAY,
                logger,
                cause
        );
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText();
    }

    private static Timestamp timestampOrNull(JsonNode node, String field) {
        String value = textOrNull(node, field);
        return value == null ? null : Timestamp.from(Instant.parse(value));
    }

    public record EntitlementSnapshot(
            boolean active,
            String productId,
            String status,
            String store,
            Timestamp expiresAt,
            boolean willRenew
    ) {
        static EntitlementSnapshot inactive() {
            return new EntitlementSnapshot(false, null, "INACTIVE", null, null, false);
        }
    }
}
