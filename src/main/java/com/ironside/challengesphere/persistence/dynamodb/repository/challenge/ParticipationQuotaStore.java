package com.ironside.challengesphere.persistence.dynamodb.repository.challenge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.config.ChallengeParticipationProperties;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbItem;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbTableGateway;
import com.ironside.challengesphere.persistence.dynamodb.foundation.MainTableGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns fixed, conditionally claimed participation slots.
 *
 * <p>The physical key is stable across subscription changes: user plus
 * challenge visibility. The effective tier determines the permitted occupied
 * count. Keeping tier out of the physical partition means a premium-to-free
 * transition cannot make already-consumed premium slots invisible to the free
 * cap.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class ParticipationQuotaStore {

    private static final int PHYSICAL_SLOT_COUNT = 100;
    private static final String SLOT_PREFIX = "SLOT#";
    private static final String POINTER_SK = "META";
    private static final String OWNER_PREFIX = "PARTICIPATION_QUOTA_SLOT#";

    private final MainTableGateway gateway;
    private final ObjectMapper objectMapper;
    private final ChallengeParticipationProperties properties;
    private final Clock clock;

    public ParticipationQuotaStore(
            MainTableGateway gateway,
            ObjectMapper objectMapper,
            ChallengeParticipationProperties properties,
            Clock clock
    ) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public Mutation planSave(UserChallenge membership, User user, Challenge challenge) {
        Allocation current = readOwnedAllocation(membership.getId()).orElse(null);
        Desired desired = desired(membership, user, challenge);
        if (!desired.consumesSlot()) {
            return releaseMutation(membership.getId(), current);
        }

        if (current != null
                && current.userId().equals(desired.userId())
                && current.visibility().equals(desired.visibility())
                && slotStillOwned(current, membership.getId())) {
            String effectiveTier = desired.premium() ? "PREMIUM" : "FREE";
            if (java.util.Objects.equals(
                            current.expiresAtEpochSecond(), desired.expiresAtEpochSecond()
                    )
                    && current.effectiveTier().equals(effectiveTier)) {
                return Mutation.none();
            }
            return refreshMutation(
                    membership.getId(),
                    new Allocation(
                            desired.userId(),
                            desired.visibility(),
                            current.slotSortKey(),
                            effectiveTier,
                            desired.expiresAtEpochSecond()
                    )
            );
        }

        String targetPartition = quotaPartition(desired.userId(), desired.visibility());
        List<DynamoDbItem> physicalRows = readPhysicalSlots(targetPartition);
        long nowEpochSeconds = clock.instant().getEpochSecond();
        List<DynamoDbItem> occupied = physicalRows.stream()
                .filter(item -> activeAt(item, nowEpochSeconds))
                .toList();
        if (occupied.size() >= desired.limit()) {
            throw new ParticipationQuotaExceededException(
                    desired.limit(), desired.premium(), desired.visibility()
            );
        }

        Set<String> occupiedSortKeys = new HashSet<>();
        occupied.forEach(item -> occupiedSortKeys.add(item.getSk()));
        String targetSortKey = null;
        for (int slot = 0; slot < desired.limit(); slot++) {
            String candidate = slotSortKey(slot);
            if (!occupiedSortKeys.contains(candidate)) {
                targetSortKey = candidate;
                break;
            }
        }
        if (targetSortKey == null) {
            throw new IllegalStateException("No physical participation quota slot is available");
        }

        Allocation target = new Allocation(
                desired.userId(),
                desired.visibility(),
                targetSortKey,
                desired.premium() ? "PREMIUM" : "FREE",
                desired.expiresAtEpochSecond()
        );
        return claimMutation(membership, target, current, nowEpochSeconds);
    }

    public Mutation planDelete(UserChallenge membership) {
        if (membership == null || blank(membership.getId())) {
            return Mutation.none();
        }
        return releaseMutation(
                membership.getId(),
                readOwnedAllocation(membership.getId()).orElse(null)
        );
    }

    private Mutation claimMutation(
            UserChallenge membership,
            Allocation target,
            Allocation previous,
            long nowEpochSeconds
    ) {
        String owner = owner(membership.getId());
        Instant now = clock.instant();
        DynamoDbKey slotKey = slotKey(target);
        DynamoDbItem slot = DynamoDbItem.create(slotKey, owner, encode(target), now);
        slot.setExpiresAt(target.expiresAtEpochSecond());
        DynamoDbItem pointer = DynamoDbItem.create(
                pointerKey(membership.getId()), owner, encode(target), now
        );
        pointer.setExpiresAt(target.expiresAtEpochSecond());

        List<TransactWriteItem> actions = new ArrayList<>(3);
        actions.add(gateway.transactionPut(slot, DynamoDbTableGateway.WriteCondition.of(
                "attribute_not_exists(#pk) OR #owner = :owner OR #expires <= :now",
                Map.of(
                        "#pk", DynamoDbKeys.PARTITION_KEY,
                        "#owner", DynamoDbKeys.ENTITY_TYPE,
                        "#expires", DynamoDbKeys.EXPIRES_AT
                ),
                Map.of(
                        ":owner", AttributeValue.fromS(owner),
                        ":now", AttributeValue.fromN(Long.toString(nowEpochSeconds))
                )
        )));
        actions.add(gateway.transactionPut(pointer, ownedOrMissing(owner)));
        if (previous != null
                && !slotKey(previous).equals(slotKey)
                && slotStillOwned(previous, membership.getId())) {
            actions.add(gateway.transactionDelete(slotKey(previous), owned(owner)));
        }
        return new Mutation(List.copyOf(actions), true);
    }

    private Mutation refreshMutation(String membershipId, Allocation allocation) {
        String owner = owner(membershipId);
        Instant now = clock.instant();
        DynamoDbItem slot = DynamoDbItem.create(
                slotKey(allocation), owner, encode(allocation), now
        );
        slot.setExpiresAt(allocation.expiresAtEpochSecond());
        DynamoDbItem pointer = DynamoDbItem.create(
                pointerKey(membershipId), owner, encode(allocation), now
        );
        pointer.setExpiresAt(allocation.expiresAtEpochSecond());
        return new Mutation(List.of(
                gateway.transactionPut(slot, owned(owner)),
                gateway.transactionPut(pointer, owned(owner))
        ), false);
    }

    private Mutation releaseMutation(String membershipId, Allocation allocation) {
        if (allocation == null) {
            return Mutation.none();
        }
        String owner = owner(membershipId);
        List<TransactWriteItem> actions = new ArrayList<>(2);
        if (slotStillOwned(allocation, membershipId)) {
            actions.add(gateway.transactionDelete(slotKey(allocation), owned(owner)));
        }
        actions.add(gateway.transactionDelete(pointerKey(membershipId), owned(owner)));
        return new Mutation(List.copyOf(actions), false);
    }

    private Desired desired(UserChallenge membership, User user, Challenge challenge) {
        boolean premium = user.getPremiumUser() != null && user.getPremiumUser() > 0;
        String visibility = Boolean.TRUE.equals(challenge.getIsPrivate()) ? "PRIVATE" : "PUBLIC";
        boolean consumes = Boolean.TRUE.equals(membership.getActive())
                && !Boolean.TRUE.equals(membership.getBanned())
                && !Boolean.TRUE.equals(membership.getCompleted())
                && Boolean.TRUE.equals(challenge.getActive())
                && consumesSlot(challenge, LocalDate.now(clock));
        Long expiresAt = consumes ? expirationEpochSecond(challenge) : null;
        return new Desired(
                membership.getUserId(),
                visibility,
                premium,
                properties.limitFor(premium),
                consumes,
                expiresAt
        );
    }

    private Long expirationEpochSecond(Challenge challenge) {
        if (Challenge.isOneOffMode(challenge.getChallengeMode())
                || challenge.getStartDate() == null
                || challenge.getNumberOfDays() == null) {
            return null;
        }
        return challenge.getStartDate()
                .plusDays(challenge.getNumberOfDays())
                .atStartOfDay(clock.getZone())
                .toInstant()
                .getEpochSecond();
    }

    private Optional<Allocation> readOwnedAllocation(String membershipId) {
        String expectedOwner = owner(membershipId);
        return gateway.get(pointerKey(membershipId), true)
                .filter(item -> expectedOwner.equals(item.getEntityType()))
                .map(item -> decode(item.getPayloadJson()));
    }

    private boolean slotStillOwned(Allocation allocation, String membershipId) {
        return gateway.get(slotKey(allocation), true)
                .map(DynamoDbItem::getEntityType)
                .filter(owner(membershipId)::equals)
                .isPresent();
    }

    private List<DynamoDbItem> readPhysicalSlots(String partitionKey) {
        QueryRequest request = QueryRequest.builder()
                .tableName(gateway.tableName())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :slot)")
                .expressionAttributeNames(Map.of(
                        "#pk", DynamoDbKeys.PARTITION_KEY,
                        "#sk", DynamoDbKeys.SORT_KEY
                ))
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(partitionKey),
                        ":slot", AttributeValue.fromS(SLOT_PREFIX)
                ))
                .limit(PHYSICAL_SLOT_COUNT)
                .scanIndexForward(true)
                .build();
        return gateway.query(request, true).items();
    }

    private String encode(Allocation allocation) {
        try {
            return objectMapper.writeValueAsString(allocation);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Unable to encode participation quota allocation", failure);
        }
    }

    private Allocation decode(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, Allocation.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to decode participation quota allocation", failure);
        }
    }

    private static boolean consumesSlot(Challenge challenge, LocalDate today) {
        if (Challenge.isOneOffMode(challenge.getChallengeMode())) {
            return true;
        }
        if (challenge.getStartDate() == null || challenge.getNumberOfDays() == null) {
            return false;
        }
        return today.isBefore(challenge.getStartDate().plusDays(challenge.getNumberOfDays()));
    }

    private static boolean activeAt(DynamoDbItem item, long epochSecond) {
        return item.getExpiresAt() == null || item.getExpiresAt() > epochSecond;
    }

    private static DynamoDbTableGateway.WriteCondition ownedOrMissing(String owner) {
        return DynamoDbTableGateway.WriteCondition.of(
                "attribute_not_exists(#pk) OR #owner = :owner",
                Map.of("#pk", DynamoDbKeys.PARTITION_KEY, "#owner", DynamoDbKeys.ENTITY_TYPE),
                Map.of(":owner", AttributeValue.fromS(owner))
        );
    }

    private static DynamoDbTableGateway.WriteCondition owned(String owner) {
        return DynamoDbTableGateway.WriteCondition.of(
                "#owner = :owner",
                Map.of("#owner", DynamoDbKeys.ENTITY_TYPE),
                Map.of(":owner", AttributeValue.fromS(owner))
        );
    }

    private static String quotaPartition(String userId, String visibility) {
        return ChallengeRepositoryKeys.join(
                "PARTICIPATION_QUOTA", "USER", userId, "VISIBILITY", visibility
        );
    }

    private static DynamoDbKey slotKey(Allocation allocation) {
        return DynamoDbKeys.key(
                quotaPartition(allocation.userId(), allocation.visibility()),
                allocation.slotSortKey()
        );
    }

    private static DynamoDbKey pointerKey(String membershipId) {
        return DynamoDbKeys.key(
                ChallengeRepositoryKeys.join("PARTICIPATION_QUOTA", "MEMBERSHIP", membershipId),
                POINTER_SK
        );
    }

    private static String slotSortKey(int slot) {
        return SLOT_PREFIX + String.format("%03d", slot);
    }

    private static String owner(String membershipId) {
        return OWNER_PREFIX + membershipId;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Mutation(List<TransactWriteItem> actions, boolean claimAttempt) {
        public Mutation {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        static Mutation none() {
            return new Mutation(List.of(), false);
        }
    }

    private record Allocation(
            String userId,
            String visibility,
            String slotSortKey,
            String effectiveTier,
            Long expiresAtEpochSecond
    ) {
    }

    private record Desired(
            String userId,
            String visibility,
            boolean premium,
            int limit,
            boolean consumesSlot,
            Long expiresAtEpochSecond
    ) {
    }

    public static final class ParticipationQuotaExceededException extends RuntimeException {
        private final int limit;
        private final boolean premium;
        private final String visibility;

        public ParticipationQuotaExceededException(int limit, boolean premium, String visibility) {
            super(message(limit, premium, visibility));
            this.limit = limit;
            this.premium = premium;
            this.visibility = visibility;
        }

        public int getLimit() {
            return limit;
        }

        public boolean isPremium() {
            return premium;
        }

        public String getVisibility() {
            return visibility;
        }

        private static String message(int limit, boolean premium, String visibility) {
            String normalizedVisibility = visibility.toLowerCase();
            String tier = premium ? "Premium" : "Free";
            return tier + " users can be part of up to " + limit + " active "
                    + normalizedVisibility + " challenges at a time. Complete or leave an active "
                    + normalizedVisibility + " challenge before adding another.";
        }
    }
}
