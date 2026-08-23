package com.ironside.challengesphere.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.model.ChallengeInvite;
import com.ironside.challengesphere.model.InviteUsage;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbItem;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbTableGateway;
import com.ironside.challengesphere.persistence.dynamodb.foundation.MainTableGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Atomically reserves one invite use before the membership write begins.
 *
 * <p>The usage aggregate and unique marker are committed in the same DynamoDB
 * transaction as the invite count/deactivation update. A retry therefore sees
 * either no reservation or a complete, durable reservation; it can never
 * increment the invite twice for the same user.</p>
 */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class InviteRedemptionRepository {

    private static final String INVITE_ENTITY_TYPE = "CHALLENGE_INVITE";
    private static final String USAGE_ENTITY_TYPE = "INVITE_USAGE";
    private static final String PROJECTION_PREFIX = "PROJECTION#";
    private static final String UNIQUE_PREFIX = "UNIQUE#";
    private static final int MAX_ITEM_BYTES = 350 * 1024;

    private final DynamoDomainStore store;
    private final MainTableGateway gateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ChallengeInviteRepository inviteRepository;
    private final InviteUsageRepository usageRepository;

    public InviteRedemptionRepository(
            DynamoDomainStore store,
            MainTableGateway gateway,
            ObjectMapper objectMapper,
            Clock clock,
            ChallengeInviteRepository inviteRepository,
            InviteUsageRepository usageRepository
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.inviteRepository = Objects.requireNonNull(inviteRepository, "inviteRepository");
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository");
    }

    public ReservationResult reserve(ChallengeInvite observedInvite, String userId) {
        String inviteId = requireText(
                Objects.requireNonNull(observedInvite, "observedInvite").getId(),
                "invite.id"
        );
        String inviteCode = requireText(observedInvite.getInviteCode(), "invite.inviteCode");
        String normalizedUserId = requireText(userId, "userId");

        if (!inviteRepository.isInviteCodeLeaseHeld(inviteCode)) {
            throw new IllegalStateException("Invite redemption requires the invite-code lease");
        }

        Optional<InviteUsage> existing = usageRepository.findByInviteIdAndUserId(
                inviteId,
                normalizedUserId
        );
        if (existing.isPresent()) {
            return new ReservationResult(false, observedInvite);
        }

        DynamoDbKey inviteCanonicalKey = store.canonicalKey(INVITE_ENTITY_TYPE, inviteId);
        DynamoDbItem storedInvite = gateway.get(inviteCanonicalKey, true)
                .orElseThrow(() -> new ReservationConflictException("Invite no longer exists"));
        ChallengeInvite currentInvite = decode(storedInvite, ChallengeInvite.class);
        if (!inviteId.equals(currentInvite.getId())
                || !inviteCode.equals(currentInvite.getInviteCode())) {
            throw new ReservationConflictException("Invite changed during redemption");
        }

        Instant now = clock.instant();
        int currentUseCount = currentInvite.getUseCount() == null ? 0 : currentInvite.getUseCount();
        if (!Boolean.TRUE.equals(currentInvite.getActive())
                || (currentInvite.getExpiresAt() != null
                && !currentInvite.getExpiresAt().after(Timestamp.from(now)))
                || currentUseCount < 0
                || (currentInvite.getMaxUses() != null
                && currentUseCount >= currentInvite.getMaxUses())) {
            throw new ReservationUnavailableException();
        }

        List<DynamoProjection> previousInviteProjections =
                inviteRepository.projectionsForAtomicWrite(currentInvite);
        List<DynamoProjection> previousInviteUniqueProjections =
                inviteRepository.uniqueProjectionsForAtomicWrite(currentInvite);

        ChallengeInvite updatedInvite = copy(currentInvite);
        int nextUseCount = Math.addExact(currentUseCount, 1);
        updatedInvite.setUseCount(nextUseCount);
        if (updatedInvite.getMaxUses() != null && nextUseCount >= updatedInvite.getMaxUses()) {
            updatedInvite.setActive(false);
        }

        InviteUsage usage = new InviteUsage();
        usage.setId(store.nextId());
        usage.setInviteId(inviteId);
        usage.setUserId(normalizedUserId);
        usage.setJoinedAt(Timestamp.from(now));

        List<TransactWriteItem> actions = new ArrayList<>();
        appendExistingAggregateWrite(
                actions,
                INVITE_ENTITY_TYPE,
                inviteId,
                updatedInvite,
                inviteRepository.projectionsForAtomicWrite(updatedInvite),
                previousInviteProjections,
                inviteRepository.uniqueProjectionsForAtomicWrite(updatedInvite),
                previousInviteUniqueProjections,
                storedInvite,
                now
        );
        appendCreateAggregateWrite(
                actions,
                USAGE_ENTITY_TYPE,
                usage.getId(),
                usage,
                usageRepository.projectionsForAtomicWrite(usage),
                usageRepository.uniqueProjectionsForAtomicWrite(usage),
                now
        );

        try {
            gateway.transactWrite(actions, store.nextId());
            return new ReservationResult(true, updatedInvite);
        } catch (ConditionalCheckFailedException | TransactionCanceledException conflict) {
            if (usageRepository.findByInviteIdAndUserId(inviteId, normalizedUserId).isPresent()) {
                return new ReservationResult(false, observedInvite);
            }
            throw new ReservationConflictException("Invite reservation conflicted", conflict);
        }
    }

    private void appendExistingAggregateWrite(
            List<TransactWriteItem> actions,
            String entityType,
            String id,
            Object payload,
            Collection<DynamoProjection> projections,
            Collection<DynamoProjection> previousProjections,
            Collection<DynamoProjection> uniqueProjections,
            Collection<DynamoProjection> previousUniqueProjections,
            DynamoDbItem previousCanonical,
            Instant now
    ) {
        long previousVersion = previousCanonical.getVersion() == null
                ? 0L
                : previousCanonical.getVersion();
        long nextVersion = previousVersion + 1L;
        String payloadJson = encode(payload);
        DynamoDbItem canonical = aggregateItem(
                store.canonicalKey(entityType, id),
                entityType,
                payloadJson,
                nextVersion,
                previousCanonical.getCreatedAt(),
                now
        );
        actions.add(gateway.transactionPut(
                canonical,
                condition(
                        "#version = :expected",
                        Map.of("#version", DynamoDbKeys.VERSION),
                        Map.of(":expected", AttributeValue.fromN(Long.toString(previousVersion)))
                )
        ));

        List<DynamoProjection> current = distinct(projections);
        List<DynamoProjection> stale = stale(previousProjections, current);
        current.forEach(projection -> actions.add(gateway.transactionPut(
                projectionItem(
                        projection,
                        PROJECTION_PREFIX + entityType,
                        payloadJson,
                        nextVersion,
                        canonical,
                        now
                ),
                DynamoDbTableGateway.WriteCondition.none()
        )));
        stale.forEach(projection -> actions.add(gateway.transactionDelete(
                projection.key(),
                DynamoDbTableGateway.WriteCondition.none()
        )));

        String uniqueOwner = UNIQUE_PREFIX + entityType + '#' + id;
        DynamoDbTableGateway.WriteCondition uniqueCondition = condition(
                "attribute_not_exists(#pk) OR #owner = :owner",
                Map.of("#pk", DynamoDbKeys.PARTITION_KEY, "#owner", DynamoDbKeys.ENTITY_TYPE),
                Map.of(":owner", AttributeValue.fromS(uniqueOwner))
        );
        List<DynamoProjection> currentUnique = distinct(uniqueProjections);
        currentUnique.forEach(projection -> actions.add(gateway.transactionPut(
                projectionItem(projection, uniqueOwner, payloadJson, nextVersion, canonical, now),
                uniqueCondition
        )));

        DynamoDbTableGateway.WriteCondition ownedUnique = condition(
                "#owner = :owner",
                Map.of("#owner", DynamoDbKeys.ENTITY_TYPE),
                Map.of(":owner", AttributeValue.fromS(uniqueOwner))
        );
        stale(previousUniqueProjections, currentUnique).forEach(projection ->
                actions.add(gateway.transactionDelete(projection.key(), ownedUnique))
        );
    }

    private void appendCreateAggregateWrite(
            List<TransactWriteItem> actions,
            String entityType,
            String id,
            Object payload,
            Collection<DynamoProjection> projections,
            Collection<DynamoProjection> uniqueProjections,
            Instant now
    ) {
        String payloadJson = encode(payload);
        DynamoDbItem canonical = aggregateItem(
                store.canonicalKey(entityType, id),
                entityType,
                payloadJson,
                1L,
                null,
                now
        );
        DynamoDbTableGateway.WriteCondition createOnly = condition(
                "attribute_not_exists(#pk)",
                Map.of("#pk", DynamoDbKeys.PARTITION_KEY),
                Map.of()
        );
        actions.add(gateway.transactionPut(canonical, createOnly));

        distinct(projections).forEach(projection -> actions.add(gateway.transactionPut(
                projectionItem(
                        projection,
                        PROJECTION_PREFIX + entityType,
                        payloadJson,
                        1L,
                        canonical,
                        now
                ),
                DynamoDbTableGateway.WriteCondition.none()
        )));

        String uniqueOwner = UNIQUE_PREFIX + entityType + '#' + id;
        distinct(uniqueProjections).forEach(projection -> actions.add(gateway.transactionPut(
                projectionItem(projection, uniqueOwner, payloadJson, 1L, canonical, now),
                createOnly
        )));
    }

    private DynamoDbItem projectionItem(
            DynamoProjection projection,
            String entityType,
            String payloadJson,
            long version,
            DynamoDbItem canonical,
            Instant now
    ) {
        return aggregateItem(
                projection.key(),
                entityType,
                payloadJson,
                version,
                canonical.getCreatedAt(),
                now
        );
    }

    private DynamoDbItem aggregateItem(
            DynamoDbKey key,
            String entityType,
            String payloadJson,
            long version,
            String createdAt,
            Instant now
    ) {
        DynamoDbItem item = DynamoDbItem.create(key, entityType, payloadJson, now);
        item.setVersion(version);
        if (createdAt != null) {
            item.setCreatedAt(createdAt);
        }
        return item;
    }

    private String encode(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_ITEM_BYTES) {
                throw new IllegalArgumentException("Invite redemption aggregate is too large");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode invite redemption aggregate", exception);
        }
    }

    private <T> T decode(DynamoDbItem item, Class<T> payloadType) {
        try {
            return objectMapper.readValue(item.getPayloadJson(), payloadType);
        } catch (JsonProcessingException exception) {
            throw new ReservationConflictException("Unable to decode invite", exception);
        }
    }

    private static ChallengeInvite copy(ChallengeInvite source) {
        ChallengeInvite copy = new ChallengeInvite();
        copy.setId(source.getId());
        copy.setChallengeId(source.getChallengeId());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setRecipientUserId(source.getRecipientUserId());
        copy.setInviteCode(source.getInviteCode());
        copy.setExpiresAt(source.getExpiresAt());
        copy.setMaxUses(source.getMaxUses());
        copy.setUseCount(source.getUseCount());
        copy.setActive(source.getActive());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private static List<DynamoProjection> stale(
            Collection<DynamoProjection> previous,
            Collection<DynamoProjection> current
    ) {
        Set<DynamoDbKey> retained = distinct(current).stream()
                .map(DynamoProjection::key)
                .collect(java.util.stream.Collectors.toSet());
        return distinct(previous).stream()
                .filter(projection -> !retained.contains(projection.key()))
                .toList();
    }

    private static List<DynamoProjection> distinct(Collection<DynamoProjection> projections) {
        return projections == null || projections.isEmpty()
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(projections));
    }

    private static DynamoDbTableGateway.WriteCondition condition(
            String expression,
            Map<String, String> names,
            Map<String, AttributeValue> values
    ) {
        return DynamoDbTableGateway.WriteCondition.of(expression, names, values);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record ReservationResult(boolean newlyReserved, ChallengeInvite invite) {
    }

    public static final class ReservationUnavailableException extends RuntimeException {
        public ReservationUnavailableException() {
            super("Invite is unavailable for reservation");
        }
    }

    public static final class ReservationConflictException extends RuntimeException {
        public ReservationConflictException(String message) {
            super(message);
        }

        public ReservationConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
