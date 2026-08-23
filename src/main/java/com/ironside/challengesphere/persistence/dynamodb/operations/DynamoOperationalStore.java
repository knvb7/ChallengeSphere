package com.ironside.challengesphere.persistence.dynamodb.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoAccessPatterns;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbItem;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbTableGateway;
import com.ironside.challengesphere.persistence.dynamodb.foundation.MainTableGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable, scan-free operational state for webhook idempotency and scheduled
 * work. Due work is materialized across eight primary-key shards and claimed
 * with conditional leases before any external side effect is attempted.
 */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class DynamoOperationalStore {

    public static final int DUE_SHARDS = 8;
    private static final int MAX_QUERY_LIMIT = 100;
    private static final Duration APPLE_EVENT_LEASE = Duration.ofMinutes(5);
    private static final Duration REVOCATION_LEASE = Duration.ofMinutes(10);
    private static final String META = "META";
    private static final String LEASE_SK = "LEASE";
    private static final String UNIQUE_SK = "UNIQUE";
    private static final String DUE_PREFIX = "DUE#";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_RETRY = "RETRY";
    private static final String STATUS_PENDING = "PENDING";

    private final DynamoDomainStore domainStore;
    private final MainTableGateway gateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DynamoOperationalStore(
            DynamoDomainStore domainStore,
            MainTableGateway gateway,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.domainStore = Objects.requireNonNull(domainStore, "domainStore");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Claims a verified Apple event once; completed JTIs remain durable indefinitely. */
    public Optional<AppleAccountEventClaim> claimAppleAccountEvent(
            String jti,
            String eventType,
            String subject,
            Long eventTime
    ) {
        requireText(jti, "jti");
        requireText(eventType, "eventType");
        requireText(subject, "subject");
        DynamoDbKey key = appleEventKey(jti);

        for (int attempt = 0; attempt < 3; attempt++) {
            Instant now = clock.instant();
            Optional<Stored<AppleAccountEventDocument>> stored = get(
                    key, AppleAccountEventDocument.class
            );
            if (stored.isPresent()) {
                AppleAccountEventDocument current = stored.get().value();
                if (STATUS_COMPLETED.equals(current.status())) {
                    return Optional.empty();
                }
                if (STATUS_PROCESSING.equals(current.status())
                        && current.leaseExpiresAt() != null
                        && current.leaseExpiresAt().isAfter(now)) {
                    return Optional.empty();
                }
            }

            String token = domainStore.nextId();
            Instant receivedAt = stored.map(value -> value.value().receivedAt()).orElse(now);
            AppleAccountEventDocument claimed = new AppleAccountEventDocument(
                    jti,
                    eventType,
                    subject,
                    eventTime,
                    STATUS_PROCESSING,
                    token,
                    now.plus(APPLE_EVENT_LEASE),
                    receivedAt,
                    null,
                    null
            );
            long expectedVersion = stored.map(value -> version(value.item())).orElse(0L);
            if (putVersioned(key, "APPLE_ACCOUNT_EVENT", claimed, stored, expectedVersion)) {
                return Optional.of(new AppleAccountEventClaim(jti, token));
            }
        }
        return Optional.empty();
    }

    public boolean completeAppleAccountEvent(AppleAccountEventClaim claim) {
        return finishAppleAccountEvent(claim, STATUS_COMPLETED, null);
    }

    public boolean retryAppleAccountEvent(AppleAccountEventClaim claim, Throwable failure) {
        return finishAppleAccountEvent(
                claim,
                STATUS_RETRY,
                failure == null ? "UnknownFailure" : failure.getClass().getSimpleName()
        );
    }

    /**
     * Idempotently imports a historical PostgreSQL replay barrier as terminal.
     *
     * <p>The legacy row was committed in the same transaction as its account
     * event side effects, so it must never become claimable work after cutover.
     * A repeated import converges on the same completed item. A SHA-256 key
     * collision or conflicting source metadata fails closed.</p>
     */
    public void importCompletedAppleAccountEvent(
            String jti,
            String eventType,
            String subject,
            Long eventTime,
            Instant receivedAt
    ) {
        requireText(jti, "jti");
        requireText(eventType, "eventType");
        requireText(subject, "subject");
        Objects.requireNonNull(receivedAt, "receivedAt");
        DynamoDbKey key = appleEventKey(jti);

        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<Stored<AppleAccountEventDocument>> stored = get(
                    key, AppleAccountEventDocument.class
            );
            if (stored.isPresent()) {
                AppleAccountEventDocument current = stored.get().value();
                requireMatchingAppleEvent(current, jti, eventType, subject, eventTime);
                if (isTerminalAppleEvent(current)) {
                    return;
                }
            }

            AppleAccountEventDocument completed = new AppleAccountEventDocument(
                    jti,
                    eventType,
                    subject,
                    eventTime,
                    STATUS_COMPLETED,
                    null,
                    null,
                    receivedAt,
                    receivedAt,
                    null
            );
            long expectedVersion = stored.map(value -> version(value.item())).orElse(0L);
            if (putVersioned(
                    key,
                    "APPLE_ACCOUNT_EVENT",
                    completed,
                    stored,
                    expectedVersion
            )) {
                return;
            }
        }
        throw new IllegalStateException(
                "Unable to import completed Apple account event after concurrent updates"
        );
    }

    /** Strongly reads the exact JTI and confirms it cannot be claimed again. */
    public boolean isAppleAccountEventTerminal(String jti) {
        requireText(jti, "jti");
        return get(appleEventKey(jti), AppleAccountEventDocument.class)
                .map(Stored::value)
                .filter(document -> jti.equals(document.jti()))
                .filter(DynamoOperationalStore::isTerminalAppleEvent)
                .isPresent();
    }

    /**
     * Enqueues a durable Apple refresh-token revocation. The encrypted token is
     * stored only on its canonical item; due projections contain the work ID.
     */
    public String enqueueAppleTokenRevocation(String encryptedToken) {
        requireText(encryptedToken, "encryptedToken");
        DynamoDbKey uniqueKey = revocationTokenUniqueKey(encryptedToken);
        Optional<Stored<DueReference>> existing = get(uniqueKey, DueReference.class);
        if (existing.isPresent()) {
            return existing.get().value().id();
        }

        String id = domainStore.nextId();
        Instant now = clock.instant();
        AppleTokenRevocationDocument document = new AppleTokenRevocationDocument(
                id,
                encryptedToken,
                STATUS_PENDING,
                0,
                now,
                null,
                now,
                null,
                null
        );
        DynamoDbKey canonicalKey = revocationKey(id);
        DynamoDbKey dueKey = revocationDueKey(id, now);
        List<TransactWriteItem> actions = List.of(
                gateway.transactionPut(
                        item(canonicalKey, "APPLE_TOKEN_REVOCATION", document, 1L, now, now),
                        absent()
                ),
                gateway.transactionPut(
                        item(dueKey, "OPERATION_DUE#APPLE_TOKEN_REVOCATION",
                                new DueReference(id, now), 1L, now, now),
                        DynamoDbTableGateway.WriteCondition.none()
                ),
                gateway.transactionPut(
                        item(uniqueKey, "UNIQUE#APPLE_TOKEN_REVOCATION",
                                new DueReference(id, now), 1L, now, now),
                        absent()
                )
        );
        try {
            gateway.transactWrite(actions, domainStore.nextId());
            return id;
        } catch (ConditionalCheckFailedException | TransactionCanceledException duplicate) {
            return get(uniqueKey, DueReference.class)
                    .map(value -> value.value().id())
                    .orElseThrow(() -> new DataIntegrityViolationException(
                            "Unable to enqueue Apple token revocation", duplicate
                    ));
        }
    }

    /** Claims up to {@code maximumItems} globally due revocations in due-time order. */
    public List<ClaimedAppleTokenRevocation> claimDueAppleTokenRevocations(int maximumItems) {
        int limit = Math.max(0, Math.min(maximumItems, MAX_QUERY_LIMIT));
        if (limit == 0) {
            return List.of();
        }
        Instant now = clock.instant();
        List<DueReference> due = queryDue("APPLE_TOKEN_REVOCATION", now, limit);
        List<ClaimedAppleTokenRevocation> claimed = new ArrayList<>(limit);
        for (DueReference reference : due) {
            if (claimed.size() >= limit) {
                break;
            }
            claimAppleTokenRevocation(reference.id(), now).ifPresent(claimed::add);
        }
        return List.copyOf(claimed);
    }

    public boolean completeAppleTokenRevocation(ClaimedAppleTokenRevocation claim) {
        Objects.requireNonNull(claim, "claim");
        return finishAppleTokenRevocation(claim, null, null);
    }

    public boolean retryAppleTokenRevocation(
            ClaimedAppleTokenRevocation claim,
            Instant nextAttemptAt,
            String failure
    ) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        return finishAppleTokenRevocation(
                claim,
                nextAttemptAt,
                truncate(failure == null ? "UnknownFailure" : failure, 512)
        );
    }

    /**
     * Enqueues or resets durable account-media cleanup work. The stable work
     * ID is deduplicated by user and object key; the object key itself appears
     * only in the canonical work item, never in a key or due projection.
     */
    public String enqueueAccountMediaCleanup(
            String deletionRequestId,
            String requestedByUserId,
            String sourceType,
            String sourceId,
            String objectKey
    ) {
        requireText(deletionRequestId, "deletionRequestId");
        requireText(requestedByUserId, "requestedByUserId");
        requireText(sourceType, "sourceType");
        requireText(sourceId, "sourceId");
        requireText(objectKey, "objectKey");

        DynamoDbKey uniqueKey = accountMediaCleanupUniqueKey(requestedByUserId, objectKey);
        for (int attempt = 0; attempt < 4; attempt++) {
            Optional<Stored<DueReference>> unique = get(uniqueKey, DueReference.class);
            String id = unique.map(value -> value.value().id()).orElseGet(domainStore::nextId);
            DynamoDbKey canonicalKey = accountMediaCleanupKey(id);
            Optional<Stored<AccountMediaCleanupDocument>> stored = get(
                    canonicalKey,
                    AccountMediaCleanupDocument.class
            );
            Instant now = clock.instant();
            AccountMediaCleanupDocument next = new AccountMediaCleanupDocument(
                    id,
                    deletionRequestId,
                    requestedByUserId,
                    sourceType,
                    sourceId,
                    objectKey,
                    STATUS_PENDING,
                    now,
                    null,
                    null
            );
            if (writeAccountMediaCleanup(uniqueKey, unique, stored, next)) {
                return id;
            }
        }
        throw new DataIntegrityViolationException(
                "Concurrent account-media cleanup update for user " + requestedByUserId
        );
    }

    /**
     * Producer API for AccountDeletionService. One durable request is retained
     * per user while the due projection contains only the user ID.
     */
    public String upsertAccountDeletionRetention(
            String userId,
            String emailIdentityHmac,
            String usernameIdentityHmac,
            Instant identityHashExpiresAt
    ) {
        requireText(userId, "userId");
        Objects.requireNonNull(identityHashExpiresAt, "identityHashExpiresAt");
        DynamoDbKey key = accountDeletionKey(userId);
        for (int attempt = 0; attempt < 3; attempt++) {
            Instant now = clock.instant();
            Optional<Stored<AccountDeletionRetentionDocument>> stored = get(
                    key, AccountDeletionRetentionDocument.class
            );
            String requestId = stored.map(value -> value.value().requestId())
                    .orElseGet(domainStore::nextId);
            AccountDeletionRetentionDocument next = new AccountDeletionRetentionDocument(
                    requestId,
                    userId,
                    emailIdentityHmac,
                    usernameIdentityHmac,
                    identityHashExpiresAt,
                    null,
                    STATUS_PROCESSING,
                    now,
                    null,
                    0
            );
            if (writeAccountDeletionRetention(stored, next)) {
                return requestId;
            }
        }
        throw new DataIntegrityViolationException(
                "Concurrent account-deletion retention update for user " + userId
        );
    }

    /** Producer API for marking the durable deletion audit complete. */
    public boolean completeAccountDeletionRetention(String userId, int mediaCleanupCount) {
        requireText(userId, "userId");
        DynamoDbKey key = accountDeletionKey(userId);
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<Stored<AccountDeletionRetentionDocument>> stored = get(
                    key, AccountDeletionRetentionDocument.class
            );
            if (stored.isEmpty()) {
                return false;
            }
            AccountDeletionRetentionDocument current = stored.get().value();
            AccountDeletionRetentionDocument next = new AccountDeletionRetentionDocument(
                    current.requestId(),
                    current.userId(),
                    current.emailIdentityHmac(),
                    current.usernameIdentityHmac(),
                    current.identityHashExpiresAt(),
                    current.identityHashPurgedAt(),
                    STATUS_COMPLETED,
                    current.requestedAt(),
                    clock.instant(),
                    Math.max(0, mediaCleanupCount)
            );
            if (writeAccountDeletionRetention(stored, next)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strongly reads the durable account-deletion checkpoint so an interrupted
     * workflow can finish without replacing the retained identity HMACs.
     */
    public Optional<AccountDeletionCheckpoint> findAccountDeletionCheckpoint(String userId) {
        requireText(userId, "userId");
        return get(accountDeletionKey(userId), AccountDeletionRetentionDocument.class)
                .map(Stored::value)
                .map(document -> new AccountDeletionCheckpoint(
                        document.requestId(),
                        STATUS_COMPLETED.equals(document.status()),
                        document.mediaCleanupCount()
                ));
    }

    /** Records counter-repair evidence before a destructive account-deletion write. */
    public void recordAccountDeletionImpact(String userId, String kind, String targetId) {
        requireText(userId, "userId");
        String normalizedKind = requireText(kind, "kind").toUpperCase(Locale.ROOT);
        requireText(targetId, "targetId");
        if (!"SUBMISSION".equals(normalizedKind)
                && !"USER".equals(normalizedKind)
                && !"MEDIA".equals(normalizedKind)
                && !"OWNED_SUBMISSION".equals(normalizedKind)) {
            throw new IllegalArgumentException("Unsupported account-deletion impact kind");
        }
        DynamoDbKey key = accountDeletionImpactKey(userId, normalizedKind, targetId);
        Instant now = clock.instant();
        try {
            gateway.put(
                    item(
                            key,
                            "ACCOUNT_DELETION_IMPACT",
                            new AccountDeletionImpact(normalizedKind, targetId),
                            1L,
                            now,
                            now
                    ),
                    absent()
            );
        } catch (ConditionalCheckFailedException alreadyRecorded) {
            // Evidence is a set keyed by kind/target and is intentionally idempotent.
        }
    }

    /** Strong, bounded page of durable counter-repair evidence. */
    public AccountDeletionImpactPage findAccountDeletionImpactPage(
            String userId,
            int requestedLimit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        requireText(userId, "userId");
        int limit = Math.max(1, Math.min(requestedLimit, MAX_QUERY_LIMIT));
        Map<String, AttributeValue> startKey = exclusiveStartKey == null
                ? Map.of()
                : Map.copyOf(exclusiveStartKey);
        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(gateway.tableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", DynamoDbKeys.PARTITION_KEY))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(accountDeletionImpactPk(userId))
                ))
                .scanIndexForward(true)
                .limit(limit);
        if (!startKey.isEmpty()) {
            request.exclusiveStartKey(startKey);
        }
        DynamoDbTableGateway.QueryPage page = gateway.query(request.build(), true);
        return new AccountDeletionImpactPage(
                page.items().stream()
                        .map(item -> decode(item, AccountDeletionImpact.class))
                        .toList(),
                page.lastEvaluatedKey()
        );
    }

    public void deleteAccountDeletionImpact(
            String userId,
            AccountDeletionImpact impact
    ) {
        Objects.requireNonNull(impact, "impact");
        gateway.delete(accountDeletionImpactKey(userId, impact.kind(), impact.targetId()));
    }

    /**
     * Idempotently imports a completed PostgreSQL account-deletion audit record.
     *
     * <p>The migration runner supplies deterministically remapped IDs. Conflicting
     * target state fails closed, while an exact replay rematerializes the normal
     * purge-due projection and converges on the historical payload.</p>
     */
    public void importCompletedAccountDeletionRetention(
            String requestId,
            String userId,
            String emailIdentityHmac,
            String usernameIdentityHmac,
            Instant identityHashExpiresAt,
            Instant identityHashPurgedAt,
            Instant requestedAt,
            Instant processedAt,
            int mediaCleanupCount
    ) {
        AccountDeletionRetentionDocument imported = historicalAccountDeletionRetention(
                requestId,
                userId,
                emailIdentityHmac,
                usernameIdentityHmac,
                identityHashExpiresAt,
                identityHashPurgedAt,
                requestedAt,
                processedAt,
                mediaCleanupCount
        );
        DynamoDbKey key = accountDeletionKey(userId);
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<Stored<AccountDeletionRetentionDocument>> stored = get(
                    key, AccountDeletionRetentionDocument.class
            );
            if (stored.isPresent()
                    && (!"ACCOUNT_DELETION_RETENTION".equals(
                            stored.get().item().getEntityType()
                    ) || !imported.equals(stored.get().value()))) {
                throw new IllegalStateException(
                        "Conflicting account-deletion retention state for historical user"
                );
            }
            if (stored.isPresent() && accountDeletionRetentionDueMatches(imported)) {
                return;
            }
            if (writeAccountDeletionRetention(stored, imported)) {
                return;
            }
        }
        throw new IllegalStateException(
                "Unable to import completed account-deletion retention after concurrent updates"
        );
    }

    /** Strongly verifies the historical canonical record and exact purge-due row. */
    public boolean isAccountDeletionRetentionImported(
            String requestId,
            String userId,
            String emailIdentityHmac,
            String usernameIdentityHmac,
            Instant identityHashExpiresAt,
            Instant identityHashPurgedAt,
            Instant requestedAt,
            Instant processedAt,
            int mediaCleanupCount
    ) {
        AccountDeletionRetentionDocument expected = historicalAccountDeletionRetention(
                requestId,
                userId,
                emailIdentityHmac,
                usernameIdentityHmac,
                identityHashExpiresAt,
                identityHashPurgedAt,
                requestedAt,
                processedAt,
                mediaCleanupCount
        );
        Optional<Stored<AccountDeletionRetentionDocument>> stored = get(
                accountDeletionKey(userId), AccountDeletionRetentionDocument.class
        );
        if (stored.isEmpty()
                || !"ACCOUNT_DELETION_RETENTION".equals(stored.get().item().getEntityType())
                || !expected.equals(stored.get().value())) {
            return false;
        }
        return accountDeletionRetentionDueMatches(expected);
    }

    private boolean accountDeletionRetentionDueMatches(
            AccountDeletionRetentionDocument expected
    ) {
        Instant identityHashExpiresAt = expected.identityHashExpiresAt();
        if (identityHashExpiresAt == null) {
            return true;
        }

        Optional<Stored<DueReference>> due = get(
                retentionDueKey(expected.userId(), identityHashExpiresAt), DueReference.class
        );
        if (!hasRetainedHashes(expected)) {
            return due.isEmpty();
        }
        return due.filter(value -> "OPERATION_DUE#ACCOUNT_DELETION_RETENTION".equals(
                        value.item().getEntityType()
                ))
                .map(Stored::value)
                .filter(reference -> expected.userId().equals(reference.id()))
                .filter(reference -> identityHashExpiresAt.equals(reference.dueAt()))
                .isPresent();
    }

    /** Conditionally clears due HMAC values while retaining the deletion audit record. */
    public int purgeExpiredAccountDeletionIdentityHashes(int maximumItems) {
        return purgeExpiredAccountDeletionIdentityHashesBatch(maximumItems).purgedItems();
    }

    /** Returns examined work separately so a bounded scheduler can drain full batches safely. */
    public RetentionPurgeBatch purgeExpiredAccountDeletionIdentityHashesBatch(int maximumItems) {
        int limit = Math.max(0, Math.min(maximumItems, MAX_QUERY_LIMIT));
        if (limit == 0) {
            return new RetentionPurgeBatch(0, 0);
        }
        Instant now = clock.instant();
        List<DueReference> due = queryDue("ACCOUNT_DELETION_RETENTION", now, limit);
        int purged = 0;
        for (DueReference reference : due) {
            DynamoDbKey key = accountDeletionKey(reference.id());
            Optional<Stored<AccountDeletionRetentionDocument>> stored = get(
                    key, AccountDeletionRetentionDocument.class
            );
            if (stored.isEmpty()) {
                deleteStaleDue(retentionDueKey(reference.id(), reference.dueAt()), Long.MAX_VALUE);
                continue;
            }
            AccountDeletionRetentionDocument current = stored.get().value();
            boolean hasHashes = hasText(current.emailIdentityHmac())
                    || hasText(current.usernameIdentityHmac());
            if (!hasHashes || current.identityHashExpiresAt() == null) {
                deleteStaleDue(
                        retentionDueKey(reference.id(), reference.dueAt()),
                        version(stored.get().item())
                );
                continue;
            }
            if (current.identityHashExpiresAt().isAfter(now)) {
                if (!current.identityHashExpiresAt().equals(reference.dueAt())) {
                    deleteStaleDue(
                            retentionDueKey(reference.id(), reference.dueAt()),
                            version(stored.get().item())
                    );
                }
                continue;
            }
            AccountDeletionRetentionDocument next = new AccountDeletionRetentionDocument(
                    current.requestId(),
                    current.userId(),
                    null,
                    null,
                    current.identityHashExpiresAt(),
                    now,
                    current.status(),
                    current.requestedAt(),
                    current.processedAt(),
                    current.mediaCleanupCount()
            );
            if (writeAccountDeletionRetention(stored, next)) {
                purged++;
            }
        }
        return new RetentionPurgeBatch(due.size(), purged);
    }

    /** Logical lease; expiresAt is evaluated conditionally and is not a TTL deletion contract. */
    public Optional<Lease> tryAcquireLease(
            String scope,
            String businessKey,
            Duration duration
    ) {
        requireText(scope, "scope");
        requireText(businessKey, "businessKey");
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(duration);
        String token = domainStore.nextId();
        DynamoDbKey key = leaseKey(scope, businessKey);
        DynamoDbItem item = DynamoDbItem.create(key, "OPERATION_LEASE", token, now);
        item.setExpiresAt(expiresAt.getEpochSecond());
        DynamoDbTableGateway.WriteCondition condition = DynamoDbTableGateway.WriteCondition.of(
                "attribute_not_exists(#pk) OR #expires <= :now",
                Map.of(
                        "#pk", DynamoDbKeys.PARTITION_KEY,
                        "#expires", DynamoDbKeys.EXPIRES_AT
                ),
                Map.of(":now", AttributeValue.fromN(Long.toString(now.getEpochSecond())))
        );
        try {
            gateway.put(item, condition);
            return Optional.of(new Lease(scope, businessKey, token, expiresAt));
        } catch (ConditionalCheckFailedException alreadyHeld) {
            return Optional.empty();
        }
    }

    public void releaseLease(Lease lease) {
        if (lease == null) {
            return;
        }
        try {
            gateway.delete(
                    leaseKey(lease.scope(), lease.businessKey()),
                    tokenCondition(lease.token())
            );
        } catch (ConditionalCheckFailedException replacedOrExpired) {
            // Never remove a successor's lease.
        }
    }

    private boolean finishAppleAccountEvent(
            AppleAccountEventClaim claim,
            String status,
            String failure
    ) {
        Objects.requireNonNull(claim, "claim");
        DynamoDbKey key = appleEventKey(claim.jti());
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<Stored<AppleAccountEventDocument>> stored = get(
                    key, AppleAccountEventDocument.class
            );
            if (stored.isEmpty()
                    || !Objects.equals(claim.claimToken(), stored.get().value().leaseToken())) {
                return false;
            }
            AppleAccountEventDocument current = stored.get().value();
            AppleAccountEventDocument next = new AppleAccountEventDocument(
                    current.jti(),
                    current.eventType(),
                    current.subject(),
                    current.eventTime(),
                    status,
                    null,
                    null,
                    current.receivedAt(),
                    STATUS_COMPLETED.equals(status) ? clock.instant() : null,
                    failure
            );
            if (putVersioned(
                    key,
                    "APPLE_ACCOUNT_EVENT",
                    next,
                    stored,
                    version(stored.get().item())
            )) {
                return true;
            }
        }
        return false;
    }

    private static void requireMatchingAppleEvent(
            AppleAccountEventDocument current,
            String jti,
            String eventType,
            String subject,
            Long eventTime
    ) {
        if (!Objects.equals(current.jti(), jti)
                || !Objects.equals(current.eventType(), eventType)
                || !Objects.equals(current.subject(), subject)
                || !Objects.equals(current.eventTime(), eventTime)) {
            throw new IllegalStateException(
                    "Conflicting Apple account event metadata for historical JTI"
            );
        }
    }

    private static boolean isTerminalAppleEvent(AppleAccountEventDocument document) {
        return STATUS_COMPLETED.equals(document.status())
                && document.leaseToken() == null
                && document.leaseExpiresAt() == null
                && document.completedAt() != null;
    }

    private Optional<ClaimedAppleTokenRevocation> claimAppleTokenRevocation(
            String id,
            Instant now
    ) {
        Optional<Lease> lease = tryAcquireLease(
                "APPLE_TOKEN_REVOCATION", id, REVOCATION_LEASE
        );
        if (lease.isEmpty()) {
            return Optional.empty();
        }
        DynamoDbKey key = revocationKey(id);
        try {
            Optional<Stored<AppleTokenRevocationDocument>> stored = get(
                    key, AppleTokenRevocationDocument.class
            );
            if (stored.isEmpty()) {
                releaseLease(lease.get());
                return Optional.empty();
            }
            AppleTokenRevocationDocument current = stored.get().value();
            boolean eligibleStatus = STATUS_PENDING.equals(current.status())
                    || STATUS_RETRY.equals(current.status())
                    || STATUS_PROCESSING.equals(current.status());
            if (!eligibleStatus
                    || !hasText(current.encryptedToken())
                    || current.nextAttemptAt() == null
                    || current.nextAttemptAt().isAfter(now)) {
                releaseLease(lease.get());
                return Optional.empty();
            }
            Instant recoveryAt = lease.get().expiresAt();
            AppleTokenRevocationDocument next = new AppleTokenRevocationDocument(
                    current.id(),
                    current.encryptedToken(),
                    STATUS_PROCESSING,
                    current.attempts(),
                    recoveryAt,
                    current.lastError(),
                    current.createdAt(),
                    current.completedAt(),
                    lease.get().token()
            );
            if (!writeRevocation(stored, next, null)) {
                releaseLease(lease.get());
                return Optional.empty();
            }
            return Optional.of(new ClaimedAppleTokenRevocation(
                    id,
                    current.encryptedToken(),
                    current.attempts(),
                    lease.get().token()
            ));
        } catch (RuntimeException failure) {
            releaseLease(lease.get());
            throw failure;
        }
    }

    private boolean finishAppleTokenRevocation(
            ClaimedAppleTokenRevocation claim,
            Instant retryAt,
            String failure
    ) {
        DynamoDbKey key = revocationKey(claim.id());
        Optional<Stored<AppleTokenRevocationDocument>> stored = get(
                key, AppleTokenRevocationDocument.class
        );
        if (stored.isEmpty()) {
            return false;
        }
        AppleTokenRevocationDocument current = stored.get().value();
        if (!STATUS_PROCESSING.equals(current.status())
                || !Objects.equals(claim.claimToken(), current.leaseToken())) {
            return false;
        }
        AppleTokenRevocationDocument next = new AppleTokenRevocationDocument(
                current.id(),
                retryAt == null ? null : current.encryptedToken(),
                retryAt == null ? STATUS_COMPLETED : STATUS_RETRY,
                current.attempts() + 1,
                retryAt,
                retryAt == null ? null : failure,
                current.createdAt(),
                retryAt == null ? clock.instant() : null,
                null
        );
        return writeRevocation(
                stored,
                next,
                new Lease(
                        "APPLE_TOKEN_REVOCATION",
                        claim.id(),
                        claim.claimToken(),
                        current.nextAttemptAt()
                )
        );
    }

    private boolean writeRevocation(
            Optional<Stored<AppleTokenRevocationDocument>> stored,
            AppleTokenRevocationDocument next,
            Lease leaseToRelease
    ) {
        DynamoDbKey canonicalKey = revocationKey(next.id());
        long expectedVersion = stored.map(value -> version(value.item())).orElse(0L);
        long nextVersion = expectedVersion + 1L;
        Instant now = clock.instant();
        Instant createdAt = stored.map(value -> value.value().createdAt()).orElse(next.createdAt());
        DynamoDbItem canonical = item(
                canonicalKey,
                "APPLE_TOKEN_REVOCATION",
                next,
                nextVersion,
                createdAt,
                now
        );
        List<TransactWriteItem> actions = new ArrayList<>();
        actions.add(gateway.transactionPut(
                canonical,
                expectedVersion == 0L ? absent() : versionCondition(expectedVersion)
        ));

        DynamoDbKey oldDue = stored
                .map(Stored::value)
                .map(AppleTokenRevocationDocument::nextAttemptAt)
                .map(value -> revocationDueKey(next.id(), value))
                .orElse(null);
        DynamoDbKey newDue = next.nextAttemptAt() == null
                ? null
                : revocationDueKey(next.id(), next.nextAttemptAt());
        if (newDue != null) {
            actions.add(gateway.transactionPut(
                    item(
                            newDue,
                            "OPERATION_DUE#APPLE_TOKEN_REVOCATION",
                            new DueReference(next.id(), next.nextAttemptAt()),
                            nextVersion,
                            createdAt,
                            now
                    ),
                    DynamoDbTableGateway.WriteCondition.none()
            ));
        }
        if (oldDue != null && !oldDue.equals(newDue)) {
            actions.add(gateway.transactionDelete(
                    oldDue, DynamoDbTableGateway.WriteCondition.none()
            ));
        }
        if (leaseToRelease != null) {
            actions.add(gateway.transactionDelete(
                    leaseKey(leaseToRelease.scope(), leaseToRelease.businessKey()),
                    tokenCondition(leaseToRelease.token())
            ));
        }
        try {
            gateway.transactWrite(actions, domainStore.nextId());
            return true;
        } catch (ConditionalCheckFailedException | TransactionCanceledException conflict) {
            return false;
        }
    }

    private boolean writeAccountDeletionRetention(
            Optional<Stored<AccountDeletionRetentionDocument>> stored,
            AccountDeletionRetentionDocument next
    ) {
        DynamoDbKey canonicalKey = accountDeletionKey(next.userId());
        long expectedVersion = stored.map(value -> version(value.item())).orElse(0L);
        long nextVersion = expectedVersion + 1L;
        Instant now = clock.instant();
        Instant createdAt = stored.map(value -> value.value().requestedAt())
                .orElse(next.requestedAt());
        List<TransactWriteItem> actions = new ArrayList<>();
        actions.add(gateway.transactionPut(
                item(
                        canonicalKey,
                        "ACCOUNT_DELETION_RETENTION",
                        next,
                        nextVersion,
                        createdAt,
                        now
                ),
                expectedVersion == 0L ? absent() : versionCondition(expectedVersion)
        ));

        DynamoDbKey oldDue = stored
                .map(Stored::value)
                .filter(DynamoOperationalStore::hasRetainedHashes)
                .map(AccountDeletionRetentionDocument::identityHashExpiresAt)
                .map(value -> retentionDueKey(next.userId(), value))
                .orElse(null);
        DynamoDbKey newDue = hasRetainedHashes(next) && next.identityHashExpiresAt() != null
                ? retentionDueKey(next.userId(), next.identityHashExpiresAt())
                : null;
        if (newDue != null) {
            actions.add(gateway.transactionPut(
                    item(
                            newDue,
                            "OPERATION_DUE#ACCOUNT_DELETION_RETENTION",
                            new DueReference(next.userId(), next.identityHashExpiresAt()),
                            nextVersion,
                            createdAt,
                            now
                    ),
                    DynamoDbTableGateway.WriteCondition.none()
            ));
        }
        if (oldDue != null && !oldDue.equals(newDue)) {
            actions.add(gateway.transactionDelete(
                    oldDue, DynamoDbTableGateway.WriteCondition.none()
            ));
        }
        try {
            gateway.transactWrite(actions, domainStore.nextId());
            return true;
        } catch (ConditionalCheckFailedException | TransactionCanceledException conflict) {
            return false;
        }
    }

    private boolean writeAccountMediaCleanup(
            DynamoDbKey uniqueKey,
            Optional<Stored<DueReference>> unique,
            Optional<Stored<AccountMediaCleanupDocument>> stored,
            AccountMediaCleanupDocument next
    ) {
        DynamoDbKey canonicalKey = accountMediaCleanupKey(next.id());
        long expectedVersion = stored.map(value -> version(value.item())).orElse(0L);
        long nextVersion = expectedVersion + 1L;
        Instant now = clock.instant();
        Instant createdAt = stored.map(value -> value.value().requestedAt())
                .orElse(next.requestedAt());
        List<TransactWriteItem> actions = new ArrayList<>();
        actions.add(gateway.transactionPut(
                item(
                        canonicalKey,
                        "ACCOUNT_MEDIA_CLEANUP",
                        next,
                        nextVersion,
                        createdAt,
                        now
                ),
                expectedVersion == 0L ? absent() : versionCondition(expectedVersion)
        ));

        DynamoDbKey newDue = accountMediaCleanupDueKey(next.id(), next.requestedAt());
        actions.add(gateway.transactionPut(
                item(
                        newDue,
                        "OPERATION_DUE#ACCOUNT_MEDIA_CLEANUP",
                        new DueReference(next.id(), next.requestedAt()),
                        nextVersion,
                        createdAt,
                        now
                ),
                DynamoDbTableGateway.WriteCondition.none()
        ));
        stored.map(Stored::value)
                .map(AccountMediaCleanupDocument::requestedAt)
                .map(value -> accountMediaCleanupDueKey(next.id(), value))
                .filter(oldDue -> !oldDue.equals(newDue))
                .ifPresent(oldDue -> actions.add(gateway.transactionDelete(
                        oldDue,
                        DynamoDbTableGateway.WriteCondition.none()
                )));

        if (unique.isEmpty()) {
            actions.add(gateway.transactionPut(
                    item(
                            uniqueKey,
                            "UNIQUE#ACCOUNT_MEDIA_CLEANUP",
                            new DueReference(next.id(), next.requestedAt()),
                            1L,
                            createdAt,
                            now
                    ),
                    absent()
            ));
        } else {
            actions.add(gateway.transactionConditionCheck(
                    uniqueKey,
                    versionCondition(version(unique.get().item()))
            ));
        }

        try {
            gateway.transactWrite(actions, domainStore.nextId());
            return true;
        } catch (ConditionalCheckFailedException | TransactionCanceledException conflict) {
            return false;
        }
    }

    private List<DueReference> queryDue(String workType, Instant now, int maximumItems) {
        List<DueReference> result = new ArrayList<>();
        int perShard = Math.max(1, Math.min(MAX_QUERY_LIMIT, maximumItems));
        for (int shard = 0; shard < DUE_SHARDS; shard++) {
            Map<String, String> names = Map.of(
                    "#pk", DynamoDbKeys.PARTITION_KEY,
                    "#sk", DynamoDbKeys.SORT_KEY
            );
            Map<String, AttributeValue> values = Map.of(
                    ":pk", AttributeValue.fromS(duePartition(workType, shard)),
                    ":from", AttributeValue.fromS(DUE_PREFIX + "0000000000000000000#"),
                    ":through", AttributeValue.fromS(dueSk(now, "\uffff"))
            );
            QueryRequest request = QueryRequest.builder()
                    .tableName(gateway.tableName())
                    .keyConditionExpression("#pk = :pk AND #sk BETWEEN :from AND :through")
                    .expressionAttributeNames(names)
                    .expressionAttributeValues(values)
                    .scanIndexForward(true)
                    .limit(perShard)
                    .build();
            gateway.query(request, true).items().stream()
                    .map(item -> decode(item, DueReference.class))
                    .forEach(result::add);
        }
        return result.stream()
                .sorted(Comparator.comparing(DueReference::dueAt).thenComparing(DueReference::id))
                .limit(maximumItems)
                .toList();
    }

    private void deleteStaleDue(DynamoDbKey key, long maximumVersion) {
        try {
            gateway.delete(key, DynamoDbTableGateway.WriteCondition.of(
                    "#version <= :maximum",
                    Map.of("#version", DynamoDbKeys.VERSION),
                    Map.of(":maximum", AttributeValue.fromN(Long.toString(maximumVersion)))
            ));
        } catch (ConditionalCheckFailedException changed) {
            // A newer producer write replaced the stale due row.
        }
    }

    private <T> Optional<Stored<T>> get(DynamoDbKey key, Class<T> type) {
        return gateway.get(key, true).map(item -> new Stored<>(item, decode(item, type)));
    }

    private <T> boolean putVersioned(
            DynamoDbKey key,
            String entityType,
            T value,
            Optional<? extends Stored<?>> stored,
            long expectedVersion
    ) {
        Instant now = clock.instant();
        long nextVersion = expectedVersion + 1L;
        Instant createdAt = stored
                .map(Stored::item)
                .map(DynamoDbItem::getCreatedAt)
                .map(Instant::parse)
                .orElse(now);
        DynamoDbItem item = item(key, entityType, value, nextVersion, createdAt, now);
        try {
            gateway.put(
                    item,
                    expectedVersion == 0L ? absent() : versionCondition(expectedVersion)
            );
            return true;
        } catch (ConditionalCheckFailedException conflict) {
            return false;
        }
    }

    private DynamoDbItem item(
            DynamoDbKey key,
            String entityType,
            Object value,
            long version,
            Instant createdAt,
            Instant now
    ) {
        DynamoDbItem item = DynamoDbItem.create(key, entityType, encode(value), now);
        item.setVersion(version);
        item.setCreatedAt(createdAt.toString());
        return item;
    }

    private <T> T decode(DynamoDbItem item, Class<T> type) {
        try {
            return objectMapper.readValue(item.getPayloadJson(), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to decode operational item " + item.getPk() + '/' + item.getSk(),
                    exception
            );
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode operational item", exception);
        }
    }

    private static DynamoDbTableGateway.WriteCondition absent() {
        return DynamoDbTableGateway.WriteCondition.of(
                "attribute_not_exists(#pk)",
                Map.of("#pk", DynamoDbKeys.PARTITION_KEY),
                Map.of()
        );
    }

    private static DynamoDbTableGateway.WriteCondition versionCondition(long expectedVersion) {
        return DynamoDbTableGateway.WriteCondition.of(
                "#version = :expected",
                Map.of("#version", DynamoDbKeys.VERSION),
                Map.of(":expected", AttributeValue.fromN(Long.toString(expectedVersion)))
        );
    }

    private static DynamoDbTableGateway.WriteCondition tokenCondition(String token) {
        return DynamoDbTableGateway.WriteCondition.of(
                "#payload = :token",
                Map.of("#payload", DynamoDbKeys.PAYLOAD_JSON),
                Map.of(":token", AttributeValue.fromS(token))
        );
    }

    private static DynamoDbKey appleEventKey(String jti) {
        return DynamoDbKeys.key("OPERATION#APPLE_ACCOUNT_EVENT#" + sha256(jti), META);
    }

    private static DynamoDbKey revocationKey(String id) {
        return DynamoDbKeys.key("OPERATION#APPLE_TOKEN_REVOCATION#" + id, META);
    }

    private static DynamoDbKey revocationTokenUniqueKey(String encryptedToken) {
        return DynamoDbKeys.key(
                "OPERATION#UNIQUE#APPLE_TOKEN_REVOCATION#" + sha256(encryptedToken),
                UNIQUE_SK
        );
    }

    private static DynamoDbKey revocationDueKey(String id, Instant dueAt) {
        return DynamoDbKeys.key(
                duePartition(
                        "APPLE_TOKEN_REVOCATION",
                        DynamoAccessPatterns.stableShard(id, DUE_SHARDS)
                ),
                dueSk(dueAt, id)
        );
    }

    private static DynamoDbKey accountDeletionKey(String userId) {
        return DynamoDbKeys.key("OPERATION#ACCOUNT_DELETION#" + userId, META);
    }

    private static String accountDeletionImpactPk(String userId) {
        return "OPERATION#ACCOUNT_DELETION#" + userId + "#IMPACT";
    }

    private static DynamoDbKey accountDeletionImpactKey(
            String userId,
            String kind,
            String targetId
    ) {
        return DynamoDbKeys.key(
                accountDeletionImpactPk(userId),
                requireText(kind, "kind").toUpperCase(Locale.ROOT)
                        + '#' + requireText(targetId, "targetId")
        );
    }

    private static DynamoDbKey accountMediaCleanupKey(String id) {
        return DynamoDbKeys.key("OPERATION#ACCOUNT_MEDIA_CLEANUP#" + id, META);
    }

    private static DynamoDbKey accountMediaCleanupUniqueKey(String userId, String objectKey) {
        return DynamoDbKeys.key(
                "OPERATION#UNIQUE#ACCOUNT_MEDIA_CLEANUP#"
                        + sha256(userId + '\0' + objectKey),
                UNIQUE_SK
        );
    }

    private static DynamoDbKey accountMediaCleanupDueKey(String id, Instant dueAt) {
        return DynamoDbKeys.key(
                duePartition(
                        "ACCOUNT_MEDIA_CLEANUP",
                        DynamoAccessPatterns.stableShard(id, DUE_SHARDS)
                ),
                dueSk(dueAt, id)
        );
    }

    private static DynamoDbKey retentionDueKey(String userId, Instant dueAt) {
        return DynamoDbKeys.key(
                duePartition(
                        "ACCOUNT_DELETION_RETENTION",
                        DynamoAccessPatterns.stableShard(userId, DUE_SHARDS)
                ),
                dueSk(dueAt, userId)
        );
    }

    private static DynamoDbKey leaseKey(String scope, String businessKey) {
        return DynamoDbKeys.key(
                "OPERATION#LEASE#" + scope.toUpperCase(Locale.ROOT) + '#' + sha256(businessKey),
                LEASE_SK
        );
    }

    private static String duePartition(String workType, int shard) {
        return String.format(
                Locale.ROOT,
                "OPERATION#DUE#%s#SHARD#%02d",
                workType.toUpperCase(Locale.ROOT),
                shard
        );
    }

    private static String dueSk(Instant dueAt, String id) {
        long millis = Math.max(0L, Objects.requireNonNull(dueAt, "dueAt").toEpochMilli());
        return String.format(Locale.ROOT, "DUE#%019d#%s", millis, id);
    }

    private static long version(DynamoDbItem item) {
        return item.getVersion() == null ? 0L : item.getVersion();
    }

    private static boolean hasRetainedHashes(AccountDeletionRetentionDocument document) {
        return hasText(document.emailIdentityHmac()) || hasText(document.usernameIdentityHmac());
    }

    private static AccountDeletionRetentionDocument historicalAccountDeletionRetention(
            String requestId,
            String userId,
            String emailIdentityHmac,
            String usernameIdentityHmac,
            Instant identityHashExpiresAt,
            Instant identityHashPurgedAt,
            Instant requestedAt,
            Instant processedAt,
            int mediaCleanupCount
    ) {
        requireText(requestId, "requestId");
        requireText(userId, "userId");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (mediaCleanupCount < 0) {
            throw new IllegalArgumentException("mediaCleanupCount must not be negative");
        }
        boolean hasHashes = hasText(emailIdentityHmac) || hasText(usernameIdentityHmac);
        if (hasHashes && identityHashExpiresAt == null) {
            throw new IllegalArgumentException(
                    "identityHashExpiresAt is required while retained identity hashes exist"
            );
        }
        if (hasHashes && identityHashPurgedAt != null) {
            throw new IllegalArgumentException(
                    "Purged account-deletion identity hashes must not remain populated"
            );
        }
        return new AccountDeletionRetentionDocument(
                requestId,
                userId,
                emailIdentityHmac,
                usernameIdentityHmac,
                identityHashExpiresAt,
                identityHashPurgedAt,
                STATUS_COMPLETED,
                requestedAt,
                processedAt,
                mediaCleanupCount
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireText(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String truncate(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record AppleAccountEventClaim(String jti, String claimToken) {
    }

    public record ClaimedAppleTokenRevocation(
            String id,
            String encryptedToken,
            int attempts,
            String claimToken
    ) {
    }

    public record Lease(
            String scope,
            String businessKey,
            String token,
            Instant expiresAt
    ) {
    }

    public record RetentionPurgeBatch(int examinedItems, int purgedItems) {
    }

    public record AccountDeletionCheckpoint(
            String requestId,
            boolean completed,
            int mediaCleanupCount
    ) {
    }

    public record AccountDeletionImpact(String kind, String targetId) {
    }

    public record AccountDeletionImpactPage(
            List<AccountDeletionImpact> items,
            Map<String, AttributeValue> lastEvaluatedKey
    ) {
        public AccountDeletionImpactPage {
            items = items == null ? List.of() : List.copyOf(items);
            lastEvaluatedKey = lastEvaluatedKey == null
                    ? Map.of()
                    : Map.copyOf(lastEvaluatedKey);
        }

        public boolean hasMore() {
            return !lastEvaluatedKey.isEmpty();
        }
    }

    private record Stored<T>(DynamoDbItem item, T value) {
    }

    private record DueReference(String id, Instant dueAt) {
    }

    private record AppleAccountEventDocument(
            String jti,
            String eventType,
            String subject,
            Long eventTime,
            String status,
            String leaseToken,
            Instant leaseExpiresAt,
            Instant receivedAt,
            Instant completedAt,
            String lastError
    ) {
    }

    private record AppleTokenRevocationDocument(
            String id,
            String encryptedToken,
            String status,
            int attempts,
            Instant nextAttemptAt,
            String lastError,
            Instant createdAt,
            Instant completedAt,
            String leaseToken
    ) {
    }

    private record AccountDeletionRetentionDocument(
            String requestId,
            String userId,
            String emailIdentityHmac,
            String usernameIdentityHmac,
            Instant identityHashExpiresAt,
            Instant identityHashPurgedAt,
            String status,
            Instant requestedAt,
            Instant processedAt,
            int mediaCleanupCount
    ) {
    }

    private record AccountMediaCleanupDocument(
            String id,
            String deletionRequestId,
            String requestedByUserId,
            String sourceType,
            String sourceId,
            String objectKey,
            String status,
            Instant requestedAt,
            Instant processedAt,
            String failureReason
    ) {
    }
}
