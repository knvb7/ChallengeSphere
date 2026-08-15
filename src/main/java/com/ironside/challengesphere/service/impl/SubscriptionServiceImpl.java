package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.response.PaymentHistoryItemResponse;
import com.ironside.challengesphere.api.response.PaymentHistoryResponse;
import com.ironside.challengesphere.api.response.SubscriptionStatusResponse;
import com.ironside.challengesphere.config.ChallengeParticipationProperties;
import com.ironside.challengesphere.config.RevenueCatProperties;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.PaymentHistory;
import com.ironside.challengesphere.model.PaymentTransaction;
import com.ironside.challengesphere.model.SubscriptionWebhookEvent;
import com.ironside.challengesphere.model.SubscriptionAuditLog;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserSubscription;
import com.ironside.challengesphere.repository.PaymentHistoryRepository;
import com.ironside.challengesphere.repository.PaymentTransactionRepository;
import com.ironside.challengesphere.repository.SubscriptionWebhookEventRepository;
import com.ironside.challengesphere.repository.SubscriptionAuditLogRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.repository.UserSubscriptionRepository;
import com.ironside.challengesphere.service.SubscriptionService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

@Service
public class SubscriptionServiceImpl implements SubscriptionService {
    private static final Logger logger = LogManager.getLogger(SubscriptionServiceImpl.class);
    private static final String PAYMENT_PROVIDER = "REVENUECAT";
    private static final Set<String> PAYMENT_EVENT_TYPES = Set.of(
            "INITIAL_PURCHASE",
            "RENEWAL",
            "CANCELLATION",
            "UNCANCELLATION",
            "NON_RENEWING_PURCHASE",
            "SUBSCRIPTION_PAUSED",
            "EXPIRATION",
            "BILLING_ISSUE",
            "PRODUCT_CHANGE",
            "SUBSCRIPTION_EXTENDED",
            "REFUND_REVERSED",
            "INVOICE_ISSUANCE"
    );

    private final RevenueCatProperties properties;
    private final ChallengeParticipationProperties participationProperties;
    private final RevenueCatClient revenueCatClient;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final UserSubscriptionRepository subscriptionRepository;
    private final SubscriptionWebhookEventRepository eventRepository;
    private final UserChallengeRepository userChallengeRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final SubscriptionAuditLogRepository subscriptionAuditLogRepository;

    public SubscriptionServiceImpl(
            RevenueCatProperties properties,
            ChallengeParticipationProperties participationProperties,
            RevenueCatClient revenueCatClient,
            ObjectMapper objectMapper,
            UserRepository userRepository,
            UserSubscriptionRepository subscriptionRepository,
            SubscriptionWebhookEventRepository eventRepository,
            UserChallengeRepository userChallengeRepository,
            PaymentHistoryRepository paymentHistoryRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            SubscriptionAuditLogRepository subscriptionAuditLogRepository
    ) {
        this.properties = properties;
        this.participationProperties = participationProperties;
        this.revenueCatClient = revenueCatClient;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.eventRepository = eventRepository;
        this.userChallengeRepository = userChallengeRepository;
        this.paymentHistoryRepository = paymentHistoryRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.subscriptionAuditLogRepository = subscriptionAuditLogRepository;
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
        UserSubscription subscription = reconcile(
                user,
                null,
                "USER_SYNC",
                null,
                "SUBSCRIBER_REFRESH",
                new Timestamp(System.currentTimeMillis())
        );
        return toResponse(user, subscription);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentHistoryResponse getPaymentHistory(Long userId, int page, int pageSize)
            throws ChallengeSphereException {
        requireUser(userId, false);
        if (page < 0 || pageSize < 1 || pageSize > 100) {
            throw new ChallengeSphereException(
                    "Payment history pagination is invalid",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        Page<PaymentHistory> payments = paymentHistoryRepository
                .findByUserIdOrderByEventAtDescIdDesc(
                        userId,
                        PageRequest.of(page, pageSize)
                );
        return PaymentHistoryResponse.builder()
                .payments(payments.getContent().stream()
                        .map(PaymentHistoryItemResponse::from)
                        .toList())
                .page(page)
                .pageSize(pageSize)
                .totalCount(payments.getTotalElements())
                .totalPages(payments.getTotalPages())
                .hasMore(payments.hasNext())
                .build();
    }

    @Override
    @Transactional
    public void processRevenueCatWebhook(byte[] rawBody, String authorization, String signature)
            throws ChallengeSphereException {
        verifyWebhook(rawBody, authorization, signature);
        try {
            JsonNode event = objectMapper.readTree(rawBody).path("event");
            String eventId = requiredText(event, "id");
            String eventType = requiredText(event, "type").toUpperCase(Locale.ROOT);
            String payloadHash = sha256(rawBody);
            SubscriptionWebhookEvent existingEvent = eventRepository.findById(eventId).orElse(null);
            if (existingEvent != null) {
                if (existingEvent.getPayloadSha256() != null
                        && !secureEquals(existingEvent.getPayloadSha256(), payloadHash)) {
                    logger.warn("RevenueCat reused an event ID with a different payload | eventId={}", eventId);
                }
                return;
            }

            String appUserId = textOrNull(event, "app_user_id");
            Long userId = parseUserId(appUserId);
            if (userId == null && event.path("aliases").isArray()) {
                for (JsonNode alias : event.path("aliases")) {
                    userId = parseUserId(alias.asText(null));
                    if (userId != null) break;
                }
            }
            User user = userId == null
                    ? null
                    : userRepository.findByIdForUpdate(userId).orElse(null);
            Timestamp eventAt = timestampFromMillis(event, "event_timestamp_ms");
            if (eventAt == null) eventAt = new Timestamp(System.currentTimeMillis());

            SubscriptionWebhookEvent storedEvent = new SubscriptionWebhookEvent();
            storedEvent.setEventId(eventId);
            storedEvent.setEventType(eventType);
            storedEvent.setAppUserId(appUserId);
            storedEvent.setEnvironment(textOrNull(event, "environment"));
            storedEvent.setUserId(user == null ? null : user.getId());
            storedEvent.setTransactionId(textOrNull(event, "transaction_id"));
            storedEvent.setPayloadSha256(payloadHash);
            eventRepository.saveAndFlush(storedEvent);

            if (user != null && PAYMENT_EVENT_TYPES.contains(eventType)) {
                recordPaymentEvent(user, event, storedEvent, eventAt);
                if (user.getDeletedAt() == null) {
                    reconcile(
                            user,
                            textOrNull(event, "environment"),
                            "WEBHOOK",
                            eventId,
                            eventType,
                            eventAt
                    );
                } else {
                    logger.info("Skipping entitlement changes for deleted user | eventId={}", eventId);
                }
            } else if (userId != null && user == null) {
                logger.warn("RevenueCat webhook references unknown user | eventId={}", eventId);
            }

            storedEvent.setProcessedAt(new Timestamp(System.currentTimeMillis()));
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

    private UserSubscription reconcile(
            User user,
            String environment,
            String source,
            String sourceReference,
            String reason,
            Timestamp eventAt
    ) throws ChallengeSphereException {
        String appUserId = appUserIdFor(user.getId());
        RevenueCatClient.EntitlementSnapshot snapshot =
                revenueCatClient.getPremiumEntitlement(appUserId);

        UserSubscription subscription = subscriptionRepository.findById(user.getId()).orElse(null);
        boolean newSubscription = subscription == null;
        if (subscription == null) subscription = new UserSubscription();
        boolean previousPremium = user.getPremiumUser() != null && user.getPremiumUser() > 0;
        String previousStatus = subscription.getStatus();
        String previousProductId = subscription.getProductId();
        Timestamp previousExpiresAt = subscription.getExpiresAt();

        subscription.setUserId(user.getId());
        subscription.setProvider(PAYMENT_PROVIDER);
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

        boolean subscriptionChanged = newSubscription
                || previousPremium != snapshot.active()
                || !Objects.equals(previousStatus, snapshot.status())
                || !Objects.equals(previousProductId, snapshot.productId())
                || !Objects.equals(previousExpiresAt, snapshot.expiresAt());
        if (subscriptionChanged) {
            recordSubscriptionAudit(
                    user.getId(),
                    source,
                    sourceReference,
                    previousPremium,
                    snapshot.active(),
                    previousStatus,
                    snapshot.status(),
                    previousProductId,
                    snapshot.productId(),
                    reason,
                    eventAt
            );
        }
        return subscription;
    }

    private void recordPaymentEvent(
            User user,
            JsonNode event,
            SubscriptionWebhookEvent webhookEvent,
            Timestamp eventAt
    ) {
        String eventType = webhookEvent.getEventType();
        String status = paymentStatus(eventType, textOrNull(event, "cancel_reason"));
        String entitlementId = entitlementId(event);
        String store = normalizedValue(event, "store", 32);
        String environment = normalizedValue(event, "environment", 32);
        String currency = normalizedCode(event, "currency", 3);
        String countryCode = normalizedCode(event, "country_code", 2);
        BigDecimal amount = decimalOrNull(event, "price_in_purchased_currency");
        BigDecimal amountUsd = decimalOrNull(event, "price");
        if (amount == null && "USD".equals(currency)) amount = amountUsd;

        PaymentHistory history = new PaymentHistory();
        history.setUserId(user.getId());
        history.setWebhookEventId(webhookEvent.getEventId());
        history.setProvider(PAYMENT_PROVIDER);
        history.setEventType(eventType);
        history.setStatus(status);
        history.setTransactionId(textOrNull(event, "transaction_id"));
        history.setOriginalTransactionId(textOrNull(event, "original_transaction_id"));
        history.setProductId(textOrNull(event, "product_id"));
        history.setNewProductId(textOrNull(event, "new_product_id"));
        history.setEntitlementId(entitlementId);
        history.setStore(store);
        history.setEnvironment(environment);
        history.setAmount(amount);
        history.setAmountUsd(amountUsd);
        history.setCurrency(currency);
        history.setTaxPercentage(decimalOrNull(event, "tax_percentage"));
        history.setCommissionPercentage(decimalOrNull(event, "commission_percentage"));
        history.setTakehomePercentage(decimalOrNull(event, "takehome_percentage"));
        history.setPeriodType(normalizedValue(event, "period_type", 32));
        history.setPresentedOfferingId(textOrNull(event, "presented_offering_id"));
        history.setCountryCode(countryCode);
        history.setCancellationReason(normalizedValue(event, "cancel_reason", 64));
        history.setExpirationReason(normalizedValue(event, "expiration_reason", 64));
        history.setPurchasedAt(timestampFromMillis(event, "purchased_at_ms"));
        history.setExpiresAt(timestampFromMillis(event, "expiration_at_ms"));
        history.setEventAt(eventAt);
        paymentHistoryRepository.save(history);

        String transactionId = history.getTransactionId();
        if (transactionId == null) return;
        String transactionStore = store == null ? "UNKNOWN" : store;
        PaymentTransaction transaction = paymentTransactionRepository
                .findByProviderAndStoreAndProviderTransactionId(
                        PAYMENT_PROVIDER,
                        transactionStore,
                        transactionId
                )
                .orElseGet(PaymentTransaction::new);

        if (transaction.getId() != null
                && transaction.getLastEventAt() != null
                && transaction.getLastEventAt().after(eventAt)) {
            logger.info(
                    "Ignoring out-of-order transaction state update | eventId={} transactionId={}",
                    webhookEvent.getEventId(),
                    transactionId
            );
            return;
        }

        transaction.setUserId(user.getId());
        transaction.setProvider(PAYMENT_PROVIDER);
        transaction.setProviderTransactionId(transactionId);
        transaction.setStore(transactionStore);
        transaction.setStatus(status);
        transaction.setLastEventType(eventType);
        transaction.setLastEventId(webhookEvent.getEventId());
        transaction.setLastEventAt(eventAt);
        setIfPresent(history.getOriginalTransactionId(), transaction::setOriginalTransactionId);
        setIfPresent(history.getProductId(), transaction::setProductId);
        setIfPresent(entitlementId, transaction::setEntitlementId);
        setIfPresent(environment, transaction::setEnvironment);
        setIfPresent(amount, transaction::setAmount);
        setIfPresent(amountUsd, transaction::setAmountUsd);
        setIfPresent(currency, transaction::setCurrency);
        setIfPresent(history.getPeriodType(), transaction::setPeriodType);
        setIfPresent(history.getPurchasedAt(), transaction::setPurchasedAt);
        setIfPresent(history.getExpiresAt(), transaction::setExpiresAt);
        setIfPresent(history.getCancellationReason(), transaction::setCancellationReason);
        setIfPresent(history.getExpirationReason(), transaction::setExpirationReason);
        paymentTransactionRepository.save(transaction);
    }

    private void recordSubscriptionAudit(
            Long userId,
            String source,
            String sourceReference,
            boolean previousPremium,
            boolean newPremium,
            String previousStatus,
            String newStatus,
            String previousProductId,
            String newProductId,
            String reason,
            Timestamp eventAt
    ) {
        SubscriptionAuditLog audit = new SubscriptionAuditLog();
        audit.setUserId(userId);
        audit.setSource(source);
        audit.setSourceReference(sourceReference);
        audit.setAction(auditAction(
                previousPremium,
                newPremium,
                previousProductId,
                newProductId
        ));
        audit.setPreviousStatus(previousStatus);
        audit.setNewStatus(newStatus);
        audit.setPreviousPremium(previousPremium);
        audit.setNewPremium(newPremium);
        audit.setProductId(newProductId == null ? previousProductId : newProductId);
        audit.setReason(reason);
        audit.setEventAt(eventAt == null
                ? new Timestamp(System.currentTimeMillis())
                : eventAt);
        subscriptionAuditLogRepository.save(audit);
    }

    private static String auditAction(
            boolean previousPremium,
            boolean newPremium,
            String previousProductId,
            String newProductId
    ) {
        if (!previousPremium && newPremium) return "ENTITLEMENT_GRANTED";
        if (previousPremium && !newPremium) return "ENTITLEMENT_REVOKED";
        if (!Objects.equals(previousProductId, newProductId)) return "PRODUCT_CHANGED";
        return "SUBSCRIPTION_UPDATED";
    }

    private static String paymentStatus(String eventType, String cancellationReason) {
        return switch (eventType) {
            case "INITIAL_PURCHASE", "RENEWAL", "NON_RENEWING_PURCHASE" -> "PAID";
            case "CANCELLATION" -> "CUSTOMER_SUPPORT".equalsIgnoreCase(cancellationReason)
                    ? "REFUNDED"
                    : "CANCELLED";
            case "UNCANCELLATION" -> "RENEWAL_RESUMED";
            case "SUBSCRIPTION_PAUSED" -> "PAUSE_SCHEDULED";
            case "EXPIRATION" -> "EXPIRED";
            case "BILLING_ISSUE" -> "PAYMENT_FAILED";
            case "PRODUCT_CHANGE" -> "PRODUCT_CHANGE_SCHEDULED";
            case "SUBSCRIPTION_EXTENDED" -> "EXTENDED";
            case "REFUND_REVERSED" -> "PAID";
            case "INVOICE_ISSUANCE" -> "PENDING";
            default -> "RECORDED";
        };
    }

    private SubscriptionStatusResponse toResponse(User user, UserSubscription subscription) {
        boolean premium = user.getPremiumUser() != null && user.getPremiumUser() > 0;
        int limit = participationProperties.limitFor(premium);
        PaymentHistory latestPayment = paymentHistoryRepository
                .findFirstByUserIdOrderByEventAtDescIdDesc(user.getId())
                .orElse(null);
        return SubscriptionStatusResponse.builder()
                .tier(premium ? "PREMIUM" : "FREE")
                .premium(premium)
                .purchasesEnabled(properties.isPurchasesReady())
                .appUserId(appUserIdFor(user.getId()))
                .productId(subscription == null ? null : subscription.getProductId())
                .status(subscription == null ? (premium ? "ACTIVE" : "INACTIVE") : subscription.getStatus())
                .expiresAt(subscription == null ? null : subscription.getExpiresAt())
                .willRenew(subscription != null && Boolean.TRUE.equals(subscription.getWillRenew()))
                .latestPaymentStatus(latestPayment == null ? null : latestPayment.getStatus())
                .latestPaymentAt(latestPayment == null ? null : latestPayment.getEventAt())
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

    private String entitlementId(JsonNode event) {
        String entitlementId = textOrNull(event, "entitlement_id");
        if (entitlementId != null) return entitlementId;
        JsonNode entitlementIds = event.path("entitlement_ids");
        if (entitlementIds.isArray()) {
            for (JsonNode value : entitlementIds) {
                if (!value.asText().isBlank()) return value.asText();
            }
        }
        return properties.getEntitlementId();
    }

    private static String normalizedValue(JsonNode node, String field, int maxLength) {
        String value = textOrNull(node, field);
        if (value == null) return null;
        String normalized = value.toUpperCase(Locale.ROOT);
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }

    private static String normalizedCode(JsonNode node, String field, int expectedLength) {
        String value = normalizedValue(node, field, expectedLength);
        return value != null && value.length() == expectedLength ? value : null;
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Timestamp timestampFromMillis(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        try {
            long millis = Long.parseLong(value.asText());
            return millis > 0 ? Timestamp.from(Instant.ofEpochMilli(millis)) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(body)
        );
    }

    private static <T> void setIfPresent(T value, Consumer<T> setter) {
        if (value != null) setter.accept(value);
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
