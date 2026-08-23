package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.SubscriptionWebhookEvent;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.common.AbstractDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.UserDynamoKeys;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Repository
public class SubscriptionWebhookEventRepository
        extends AbstractDynamoRepository<SubscriptionWebhookEvent> {

    private static final String ENTITY_TYPE = "SUBSCRIPTION_WEBHOOK_EVENT";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_RETRY = "RETRY";
    private static final Duration PROCESSING_LEASE = Duration.ofMinutes(5);
    private static final int MAX_STATE_WRITE_ATTEMPTS = 3;

    private final Clock clock;

    public SubscriptionWebhookEventRepository(DynamoDomainStore store, Clock clock) {
        super(store, ENTITY_TYPE, SubscriptionWebhookEvent.class);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SubscriptionWebhookEvent save(SubscriptionWebhookEvent event) {
        if (event.getReceivedAt() == null) {
            event.setReceivedAt(Timestamp.from(clock.instant()));
        }
        // PostgreSQL rows predate the claim state machine. Every row in that
        // table represents a webhook whose side effects already completed, so
        // import it as terminal instead of making it eligible for replay.
        if (blank(event.getProcessingStatus())) {
            event.setProcessingStatus(STATUS_COMPLETED);
            event.setCompletedAt(event.getReceivedAt());
            event.setAttemptCount(0);
        }
        return super.save(event);
    }

    /**
     * Atomically claims a provider event before any reconciliation side effect.
     * Active and completed duplicate deliveries intentionally return empty.
     */
    public Optional<WebhookEventClaim> claimForProcessing(
            String eventId,
            String eventType,
            String appUserId,
            String environment
    ) {
        requireClaimText(eventId, "eventId");
        requireClaimText(eventType, "eventType");
        RuntimeException lastConflict = null;

        for (int attempt = 0; attempt < MAX_STATE_WRITE_ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            Optional<DynamoDomainStore.Versioned<SubscriptionWebhookEvent>> stored =
                    store.loadVersioned(ENTITY_TYPE, eventId, SubscriptionWebhookEvent.class);

            SubscriptionWebhookEvent event;
            List<DynamoProjection> previousProjections;
            boolean creating;
            Long expectedVersion;
            if (stored.isEmpty()) {
                event = new SubscriptionWebhookEvent();
                event.setEventId(eventId);
                event.setReceivedAt(Timestamp.from(now));
                previousProjections = List.of();
                creating = true;
                expectedVersion = null;
            } else {
                DynamoDomainStore.Versioned<SubscriptionWebhookEvent> versioned = stored.get();
                event = versioned.value();
                String status = normalizeStatus(event.getProcessingStatus());
                // A missing state is a migrated legacy row. Such rows were
                // written only after successful PostgreSQL processing.
                if (status == null || STATUS_COMPLETED.equals(status)) {
                    return Optional.empty();
                }
                if (STATUS_PROCESSING.equals(status) && leaseActive(event, now)) {
                    return Optional.empty();
                }
                if (!STATUS_RETRY.equals(status) && !STATUS_PROCESSING.equals(status)) {
                    throw new IllegalStateException(
                            "Unsupported RevenueCat webhook state " + status + " for " + eventId
                    );
                }
                previousProjections = allProjections(event);
                creating = false;
                expectedVersion = versioned.version();
            }

            String claimToken = store.nextId();
            event.setEventType(eventType);
            if (!blank(appUserId)) {
                event.setAppUserId(appUserId);
            }
            if (!blank(environment)) {
                event.setEnvironment(environment);
            }
            event.setProcessingStatus(STATUS_PROCESSING);
            event.setClaimToken(claimToken);
            event.setLeaseExpiresAt(Timestamp.from(now.plus(PROCESSING_LEASE)));
            event.setLastAttemptAt(Timestamp.from(now));
            event.setCompletedAt(null);
            event.setLastFailure(null);
            event.setAttemptCount(nextAttemptCount(event.getAttemptCount()));

            try {
                store.save(
                        ENTITY_TYPE,
                        eventId,
                        event,
                        allProjections(event),
                        previousProjections,
                        creating,
                        expectedVersion
                );
                return Optional.of(new WebhookEventClaim(eventId, claimToken));
            } catch (DynamoDomainStore.DuplicateAggregateException
                     | DynamoDomainStore.OptimisticWriteException conflict) {
                lastConflict = conflict;
            }
        }

        throw new IllegalStateException(
                "RevenueCat webhook claim remained contended for " + eventId,
                lastConflict
        );
    }

    /** Marks the claim terminal only while the caller still owns its token. */
    public boolean completeClaim(WebhookEventClaim claim, String appUserId, String environment) {
        return transitionClaim(claim, STATUS_COMPLETED, appUserId, environment, null);
    }

    /** Releases a failed owner for a provider retry without disturbing a successor claim. */
    public boolean failClaim(WebhookEventClaim claim, Throwable failure) {
        String failureType = failure == null ? "UnknownFailure" : failure.getClass().getSimpleName();
        if (failureType.length() > 255) {
            failureType = failureType.substring(0, 255);
        }
        return transitionClaim(claim, STATUS_RETRY, null, null, failureType);
    }

    private boolean transitionClaim(
            WebhookEventClaim claim,
            String targetStatus,
            String appUserId,
            String environment,
            String failureType
    ) {
        Objects.requireNonNull(claim, "claim");
        RuntimeException lastConflict = null;
        for (int attempt = 0; attempt < MAX_STATE_WRITE_ATTEMPTS; attempt++) {
            Optional<DynamoDomainStore.Versioned<SubscriptionWebhookEvent>> stored =
                    store.loadVersioned(ENTITY_TYPE, claim.eventId(), SubscriptionWebhookEvent.class);
            if (stored.isEmpty()) {
                return false;
            }
            DynamoDomainStore.Versioned<SubscriptionWebhookEvent> versioned = stored.get();
            SubscriptionWebhookEvent event = versioned.value();
            if (!STATUS_PROCESSING.equals(normalizeStatus(event.getProcessingStatus()))
                    || !Objects.equals(claim.claimToken(), event.getClaimToken())) {
                return false;
            }

            List<DynamoProjection> previousProjections = allProjections(event);
            if (!blank(appUserId)) {
                event.setAppUserId(appUserId);
            }
            if (!blank(environment)) {
                event.setEnvironment(environment);
            }
            event.setProcessingStatus(targetStatus);
            event.setClaimToken(null);
            event.setLeaseExpiresAt(null);
            event.setLastFailure(failureType);
            event.setCompletedAt(STATUS_COMPLETED.equals(targetStatus)
                    ? Timestamp.from(clock.instant())
                    : null);

            try {
                store.save(
                        ENTITY_TYPE,
                        claim.eventId(),
                        event,
                        allProjections(event),
                        previousProjections,
                        false,
                        versioned.version()
                );
                return true;
            } catch (DynamoDomainStore.OptimisticWriteException conflict) {
                lastConflict = conflict;
            }
        }
        throw new IllegalStateException(
                "RevenueCat webhook transition remained contended for " + claim.eventId(),
                lastConflict
        );
    }

    @Override
    protected String id(SubscriptionWebhookEvent entity) {
        return entity.getEventId();
    }

    @Override
    protected void assignId(SubscriptionWebhookEvent entity, String id) {
        entity.setEventId(id);
    }

    @Override
    protected Collection<DynamoProjection> projections(SubscriptionWebhookEvent event) {
        List<DynamoProjection> projections = new ArrayList<>(2);
        if (event.getEventType() != null && !event.getEventType().isBlank()) {
            projections.add(UserDynamoKeys.projection(
                    UserDynamoKeys.collection(ENTITY_TYPE, event.getEventType()),
                    UserDynamoKeys.chronologicalSk(
                            "EVENT",
                            event.getReceivedAt(),
                            event.getEventId()
                    )
            ));
        }
        if (event.getAppUserId() != null && !event.getAppUserId().isBlank()) {
            projections.add(UserDynamoKeys.projection(
                    UserDynamoKeys.ownerCollectionPk("SUBSCRIPTION_EVENTS", event.getAppUserId()),
                    UserDynamoKeys.chronologicalSk(
                            "EVENT",
                            event.getReceivedAt(),
                            event.getEventId()
                    )
            ));
        }
        return projections;
    }

    private static boolean leaseActive(SubscriptionWebhookEvent event, Instant now) {
        return event.getLeaseExpiresAt() != null
                && event.getLeaseExpiresAt().toInstant().isAfter(now);
    }

    private static int nextAttemptCount(Integer current) {
        if (current == null || current < 0) {
            return 1;
        }
        return current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1;
    }

    private static String normalizeStatus(String status) {
        return blank(status) ? null : status.strip().toUpperCase(Locale.ROOT);
    }

    private static String requireClaimText(String value, String name) {
        if (blank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record WebhookEventClaim(String eventId, String claimToken) {
        public WebhookEventClaim {
            requireClaimText(eventId, "eventId");
            requireClaimText(claimToken, "claimToken");
        }
    }
}
