package com.ironside.challengesphere.persistence.dynamodb.repository.challenge;

import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbItem;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbTableGateway;
import com.ironside.challengesphere.persistence.dynamodb.foundation.MainTableGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Maintains one fixed, transactionally updated active-member count per challenge. */
@Component
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class ChallengeParticipantCountStore {

    private static final String ENTITY_TYPE = "CHALLENGE_PARTICIPANT_COUNT";

    private final MainTableGateway gateway;
    private final Clock clock;

    public ChallengeParticipantCountStore(MainTableGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    public List<TransactWriteItem> planSave(
            Optional<UserChallenge> previous,
            UserChallenge current
    ) {
        UserChallenge old = previous.orElse(null);
        boolean wasActive = counted(old);
        boolean isActive = counted(current);
        String oldChallengeId = old == null ? null : old.getChallengeId();
        String currentChallengeId = current == null ? null : current.getChallengeId();

        if (wasActive && isActive && java.util.Objects.equals(oldChallengeId, currentChallengeId)) {
            return List.of();
        }

        List<TransactWriteItem> actions = new ArrayList<>(2);
        if (wasActive) {
            actions.add(adjust(oldChallengeId, -1));
        }
        if (isActive) {
            actions.add(adjust(currentChallengeId, 1));
        }
        return List.copyOf(actions);
    }

    public List<TransactWriteItem> planDelete(UserChallenge membership) {
        return counted(membership)
                ? List.of(adjust(membership.getChallengeId(), -1))
                : List.of();
    }

    public long getActiveCount(String challengeId) {
        if (challengeId == null || challengeId.isBlank()) {
            return 0L;
        }
        return gateway.get(key(challengeId), true)
                .map(DynamoDbItem::getVersion)
                .map(value -> Math.max(0L, value))
                .orElse(0L);
    }

    /**
     * Strongly consistent, bounded batch hydration of fixed participant-count
     * rows. Missing counters are returned as zero and unprocessed keys receive
     * the same bounded jittered retry treatment as aggregate batch reads.
     */
    public Map<String, Long> getActiveCounts(Collection<String> challengeIds) {
        if (challengeIds == null || challengeIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new LinkedHashSet<>(challengeIds).stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        Map<String, Long> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, 0L));

        for (int offset = 0; offset < ids.size(); offset += 100) {
            List<String> chunk = ids.subList(offset, Math.min(ids.size(), offset + 100));
            Map<DynamoDbKey, String> idsByKey = new LinkedHashMap<>();
            chunk.forEach(id -> idsByKey.put(key(id), id));
            List<DynamoDbKey> pending = new ArrayList<>(idsByKey.keySet());
            int attempts = 0;
            while (!pending.isEmpty()) {
                if (++attempts > 5) {
                    throw new IllegalStateException(
                            "DynamoDB participant-count batch get remained unprocessed after 5 attempts"
                    );
                }
                DynamoDbTableGateway.BatchGetResult page = gateway.batchGet(pending, true);
                page.items().forEach(item -> {
                    String id = idsByKey.get(new DynamoDbKey(item.getPk(), item.getSk()));
                    if (id != null) {
                        result.put(id, Math.max(0L, Optional.ofNullable(item.getVersion()).orElse(0L)));
                    }
                });
                pending = new ArrayList<>(page.unprocessedKeys());
                if (!pending.isEmpty()) {
                    backoff(attempts);
                }
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Replaces the derived count with the exact frozen-source value during an
     * offline migration. Runtime writers must continue to use the transactional
     * delta plan above; this operation is intentionally reserved for cutover
     * reconciliation while PostgreSQL writes are disabled.
     */
    public void reconcileActiveCount(String challengeId, long exactCount) {
        if (exactCount < 0L) {
            throw new IllegalArgumentException("exactCount must not be negative");
        }
        String now = clock.instant().toString();
        gateway.update(UpdateItemRequest.builder()
                .tableName(gateway.tableName())
                .key(key(challengeId).toAttributeMap())
                .updateExpression(
                        "SET #type = :type, #payload = :payload, #count = :count, "
                                + "#created = if_not_exists(#created, :now), #updated = :now"
                )
                .expressionAttributeNames(Map.of(
                        "#type", DynamoDbKeys.ENTITY_TYPE,
                        "#payload", DynamoDbKeys.PAYLOAD_JSON,
                        "#count", DynamoDbKeys.VERSION,
                        "#created", DynamoDbKeys.CREATED_AT,
                        "#updated", DynamoDbKeys.UPDATED_AT
                ))
                .expressionAttributeValues(Map.of(
                        ":type", AttributeValue.fromS(ENTITY_TYPE),
                        ":payload", AttributeValue.fromS("{}"),
                        ":count", AttributeValue.fromN(Long.toString(exactCount)),
                        ":now", AttributeValue.fromS(now)
                ))
                .build());
    }

    private TransactWriteItem adjust(String challengeId, int delta) {
        String now = clock.instant().toString();
        Map<String, String> names = Map.of(
                "#type", DynamoDbKeys.ENTITY_TYPE,
                "#payload", DynamoDbKeys.PAYLOAD_JSON,
                "#count", DynamoDbKeys.VERSION,
                "#created", DynamoDbKeys.CREATED_AT,
                "#updated", DynamoDbKeys.UPDATED_AT
        );
        Map<String, AttributeValue> values = Map.of(
                ":type", AttributeValue.fromS(ENTITY_TYPE),
                ":payload", AttributeValue.fromS("{}"),
                ":delta", AttributeValue.fromN(Integer.toString(delta)),
                ":now", AttributeValue.fromS(now)
        );
        DynamoDbTableGateway.WriteCondition condition = delta < 0
                ? DynamoDbTableGateway.WriteCondition.of(
                        "attribute_exists(#count) AND #count >= :one",
                        Map.of("#count", DynamoDbKeys.VERSION),
                        Map.of(":one", AttributeValue.fromN("1"))
                )
                : DynamoDbTableGateway.WriteCondition.none();
        return gateway.transactionUpdate(
                key(challengeId),
                "SET #type = if_not_exists(#type, :type), "
                        + "#payload = :payload, "
                        + "#created = if_not_exists(#created, :now), "
                        + "#updated = :now ADD #count :delta",
                condition,
                names,
                values
        );
    }

    private static boolean counted(UserChallenge membership) {
        return membership != null
                && membership.getChallengeId() != null
                && !membership.getChallengeId().isBlank()
                && Boolean.TRUE.equals(membership.getActive())
                && !Boolean.TRUE.equals(membership.getBanned());
    }

    private static void backoff(int attempt) {
        long ceilingMillis = Math.min(250L, 5L << Math.min(Math.max(0, attempt - 1), 6));
        long delayMillis = ThreadLocalRandom.current().nextLong(ceilingMillis + 1L);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while retrying participant-count batch read",
                    interrupted
            );
        }
    }

    private static DynamoDbKey key(String challengeId) {
        return new DynamoDbKey(
                ChallengeRepositoryKeys.challengePk(
                        ChallengeRepositoryKeys.require(challengeId, "challengeId")
                ),
                ChallengeRepositoryKeys.activeParticipantCountSk()
        );
    }
}
