package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.response.SubscriptionStatusResponse;
import com.ironside.challengesphere.config.ChallengeParticipationProperties;
import com.ironside.challengesphere.config.RevenueCatProperties;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.SubscriptionWebhookEvent;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserSubscription;
import com.ironside.challengesphere.repository.SubscriptionWebhookEventRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.repository.UserSubscriptionRepository;
import com.ironside.challengesphere.service.SubscriptionService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class SubscriptionServiceImpl implements SubscriptionService {
    private static final Logger logger = LogManager.getLogger(SubscriptionServiceImpl.class);

    private final RevenueCatProperties properties;
    private final ChallengeParticipationProperties participationProperties;
    private final RevenueCatClient revenueCatClient;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final UserSubscriptionRepository subscriptionRepository;
    private final SubscriptionWebhookEventRepository eventRepository;
    private final UserChallengeRepository userChallengeRepository;

    public SubscriptionServiceImpl(
            RevenueCatProperties properties,
            ChallengeParticipationProperties participationProperties,
            RevenueCatClient revenueCatClient,
            ObjectMapper objectMapper,
            UserRepository userRepository,
            UserSubscriptionRepository subscriptionRepository,
            SubscriptionWebhookEventRepository eventRepository,
            UserChallengeRepository userChallengeRepository
    ) {
        this.properties = properties;
        this.participationProperties = participationProperties;
        this.revenueCatClient = revenueCatClient;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.eventRepository = eventRepository;
        this.userChallengeRepository = userChallengeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionStatusResponse getStatus(Long userId) throws ChallengeSphereException {
        User user = requireUser(userId, false);
        return toResponse(user, subscriptionRepository.findById(userId).orElse(null));
    }

    @Override
    @Transactional
    public SubscriptionStatusResponse sync(Long userId) throws ChallengeSphereException {
        User user = requireUser(userId, true);
        UserSubscription subscription = reconcile(user, null);
        return toResponse(user, subscription);
    }

    @Override
    @Transactional
    public void processRevenueCatWebhook(byte[] rawBody, String authorization, String signature)
            throws ChallengeSphereException {
        verifyWebhook(rawBody, authorization, signature);
        try {
            JsonNode event = objectMapper.readTree(rawBody).path("event");
            String eventId = requiredText(event, "id");
            if (eventRepository.existsById(eventId)) return;

            String appUserId = textOrNull(event, "app_user_id");
            Long userId = parseUserId(appUserId);
            if (userId == null && event.path("aliases").isArray()) {
                for (JsonNode alias : event.path("aliases")) {
                    userId = parseUserId(alias.asText(null));
                    if (userId != null) break;
                }
            }
            if (userId != null) {
                User user = userRepository.findByIdForUpdate(userId).orElse(null);
                if (user != null) {
                    reconcile(user, textOrNull(event, "environment"));
                } else {
                    logger.warn("RevenueCat webhook references unknown user | eventId={}", eventId);
                }
            }

            SubscriptionWebhookEvent storedEvent = new SubscriptionWebhookEvent();
            storedEvent.setEventId(eventId);
            storedEvent.setEventType(requiredText(event, "type"));
            storedEvent.setAppUserId(appUserId);
            storedEvent.setEnvironment(textOrNull(event, "environment"));
            eventRepository.save(storedEvent);
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("RevenueCat webhook processing failed | exception={}",
                    e.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Could not process subscription notification",
                    HttpStatus.BAD_REQUEST,
                    logger,
                    e
            );
        }
    }

    private UserSubscription reconcile(User user, String environment)
            throws ChallengeSphereException {
        String appUserId = appUserIdFor(user.getId());
        RevenueCatClient.EntitlementSnapshot snapshot =
                revenueCatClient.getPremiumEntitlement(appUserId);

        UserSubscription subscription = subscriptionRepository.findById(user.getId())
                .orElseGet(UserSubscription::new);
        subscription.setUserId(user.getId());
        subscription.setProvider("REVENUECAT");
        subscription.setAppUserId(appUserId);
        subscription.setEntitlementId(properties.getEntitlementId());
        subscription.setProductId(snapshot.productId());
        subscription.setStatus(snapshot.status());
        subscription.setStore(snapshot.store());
        if (environment != null) subscription.setEnvironment(environment);
        subscription.setExpiresAt(snapshot.expiresAt());
        subscription.setWillRenew(snapshot.willRenew());
        subscription.setLastSyncedAt(new Timestamp(System.currentTimeMillis()));
        subscriptionRepository.save(subscription);

        int premiumValue = snapshot.active() ? 1 : 0;
        if (!Integer.valueOf(premiumValue).equals(user.getPremiumUser())) {
            user.setPremiumUser(premiumValue);
            userRepository.save(user);
        }
        return subscription;
    }

    private SubscriptionStatusResponse toResponse(User user, UserSubscription subscription) {
        boolean premium = user.getPremiumUser() != null && user.getPremiumUser() > 0;
        int limit = participationProperties.limitFor(premium);
        return SubscriptionStatusResponse.builder()
                .tier(premium ? "PREMIUM" : "FREE")
                .premium(premium)
                .purchasesEnabled(properties.isPurchasesReady())
                .appUserId(appUserIdFor(user.getId()))
                .productId(subscription == null ? null : subscription.getProductId())
                .status(subscription == null ? (premium ? "ACTIVE" : "INACTIVE") : subscription.getStatus())
                .expiresAt(subscription == null ? null : subscription.getExpiresAt())
                .willRenew(subscription != null && Boolean.TRUE.equals(subscription.getWillRenew()))
                .activePublicChallenges(userChallengeRepository
                        .countSlotConsumingParticipationsByVisibility(user.getId(), false))
                .publicChallengeLimit(limit)
                .activePrivateChallenges(userChallengeRepository
                        .countSlotConsumingParticipationsByVisibility(user.getId(), true))
                .privateChallengeLimit(limit)
                .build();
    }

    private User requireUser(Long userId, boolean lock) throws ChallengeSphereException {
        return (lock ? userRepository.findByIdForUpdate(userId) : userRepository.findById(userId))
                .orElseThrow(() -> new ChallengeSphereException(
                        "User not found",
                        HttpStatus.NOT_FOUND,
                        logger
                ));
    }

    private void verifyWebhook(byte[] body, String authorization, String signature)
            throws ChallengeSphereException {
        if (properties.getWebhookAuthorization().isBlank()
                && properties.getWebhookSigningSecret().isBlank()) {
            throw webhookError("Subscription webhook is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!properties.getWebhookAuthorization().isBlank()
                && !secureEquals(properties.getWebhookAuthorization(), authorization)) {
            throw webhookError("Invalid subscription webhook authorization", HttpStatus.UNAUTHORIZED);
        }
        if (!properties.getWebhookSigningSecret().isBlank()) {
            verifyHmac(body, signature);
        }
    }

    private void verifyHmac(byte[] body, String signature) throws ChallengeSphereException {
        try {
            if (signature == null || signature.isBlank()) {
                throw webhookError("Missing subscription webhook signature", HttpStatus.UNAUTHORIZED);
            }
            String timestampValue = null;
            String signatureValue = null;
            for (String part : signature.split(",")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length != 2) continue;
                if ("t".equals(pair[0])) timestampValue = pair[1];
                if ("v1".equals(pair[0])) signatureValue = pair[1];
            }
            if (timestampValue == null || signatureValue == null) {
                throw webhookError("Invalid subscription webhook signature", HttpStatus.UNAUTHORIZED);
            }
            long timestamp = Long.parseLong(timestampValue);
            if (Math.abs(Instant.now().getEpochSecond() - timestamp)
                    > properties.getWebhookTimestampToleranceSeconds()) {
                throw webhookError("Expired subscription webhook signature", HttpStatus.UNAUTHORIZED);
            }

            byte[] prefix = (timestampValue + ".").getBytes(StandardCharsets.UTF_8);
            byte[] signedPayload = new byte[prefix.length + body.length];
            System.arraycopy(prefix, 0, signedPayload, 0, prefix.length);
            System.arraycopy(body, 0, signedPayload, prefix.length, body.length);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getWebhookSigningSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            byte[] expected = mac.doFinal(signedPayload);
            byte[] received = HexFormat.of().parseHex(signatureValue);
            if (!MessageDigest.isEqual(expected, received)) {
                throw webhookError("Invalid subscription webhook signature", HttpStatus.UNAUTHORIZED);
            }
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (Exception e) {
            throw webhookError("Invalid subscription webhook signature", HttpStatus.UNAUTHORIZED);
        }
    }

    private Long parseUserId(String appUserId) {
        if (appUserId == null || !appUserId.startsWith(properties.getAppUserIdPrefix())) return null;
        try {
            return Long.parseLong(appUserId.substring(properties.getAppUserIdPrefix().length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String appUserIdFor(Long userId) {
        return properties.getAppUserIdPrefix() + userId;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value == null) throw new IllegalArgumentException("Missing webhook field: " + field);
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText();
    }

    private static boolean secureEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private ChallengeSphereException webhookError(String message, HttpStatus status) {
        return new ChallengeSphereException(message, status, logger);
    }
}
