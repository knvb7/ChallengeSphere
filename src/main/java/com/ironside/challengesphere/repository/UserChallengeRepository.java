package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKey;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.AbstractChallengeDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeParticipantCountStore;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeRepositoryKeys;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeTransactionalWriter;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ConditionalKeyLease;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ParticipationQuotaStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class UserChallengeRepository extends AbstractChallengeDynamoRepository<UserChallenge> {

    private static final int MAX_MEMBERSHIP_WRITE_ATTEMPTS = 101;

    private static final Comparator<UserChallenge> JOINED_FIRST = Comparator
            .comparing(UserChallenge::getJoinedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(UserChallenge::getId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed();
    private static final Comparator<UserChallenge> FAVORITED_FIRST = Comparator
            .comparing(UserChallenge::getFavoritedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(UserChallenge::getJoinedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(UserChallenge::getId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed();

    private final Clock clock;
    private final ChallengeTransactionalWriter writer;
    private final ParticipationQuotaStore participationQuotaStore;
    private final ChallengeParticipantCountStore participantCountStore;
    private final UserRepository userRepository;
    private final ChallengeRepository challengeRepository;

    public UserChallengeRepository(
            DynamoDomainStore store,
            ChallengeTransactionalWriter writer,
            ConditionalKeyLease leases,
            ParticipationQuotaStore participationQuotaStore,
            ChallengeParticipantCountStore participantCountStore,
            UserRepository userRepository,
            ChallengeRepository challengeRepository,
            Clock clock
    ) {
        super(store, writer, leases, "USER_CHALLENGE", UserChallenge.class);
        this.writer = writer;
        this.participationQuotaStore = participationQuotaStore;
        this.participantCountStore = participantCountStore;
        this.userRepository = userRepository;
        this.challengeRepository = challengeRepository;
        this.clock = clock;
    }

    /**
     * Persists membership state and its participation-quota slot in one
     * transaction. Concurrent joins intentionally contend on the lowest free
     * fixed slot; a loser re-reads the partition and tries the next slot.
     */
    @Override
    public UserChallenge save(UserChallenge membership) {
        java.util.Objects.requireNonNull(membership, "membership");
        String currentId = membership.getId();
        boolean creating = blank(currentId);
        if (creating) {
            currentId = store.nextId();
            membership.setId(currentId);
        }
        prepareForSave(membership, creating);
        String lockKey = saveLockKey(membership);
        if (!blank(lockKey)) {
            leases.acquire(entityType(), lockKey);
        }

        try {
            Optional<DynamoDomainStore.Versioned<UserChallenge>> previous =
                    store.loadVersioned(entityType(), currentId, UserChallenge.class);
            User user = userRepository.findById(membership.getUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Cannot persist challenge membership for missing user "
                                    + membership.getUserId()
                    ));
            Challenge challenge = challengeRepository.findById(membership.getChallengeId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Cannot persist membership for missing challenge "
                                    + membership.getChallengeId()
                    ));
            return saveWithResolvedState(membership, challenge, user, previous, creating);
        } finally {
            if (!blank(lockKey)) {
                leases.release(entityType(), lockKey);
            }
        }
    }

    /**
     * Re-evaluates every membership against a target challenge classification.
     * Used when visibility, active state, mode, or duration changes.
     */
    public void reconcileParticipationQuotaForChallenge(Challenge targetChallenge) {
        if (targetChallenge == null || blank(targetChallenge.getId())) {
            return;
        }
        List<UserChallenge> memberships = findByChallengeId(targetChallenge.getId()).stream()
                .sorted(Comparator.comparing(this::saveLockKey))
                .toList();
        List<String> heldLockKeys = new ArrayList<>(memberships.size());
        try {
            for (UserChallenge membership : memberships) {
                String lockKey = saveLockKey(membership);
                if (!blank(lockKey)) {
                    leases.acquire(entityType(), lockKey);
                    heldLockKeys.add(lockKey);
                }
            }

            Challenge persistedChallenge = challengeRepository.findById(targetChallenge.getId())
                    .orElse(targetChallenge);
            List<ReconciledMembership> reconciled = new ArrayList<>(memberships.size());
            try {
                for (UserChallenge membership : memberships) {
                    Optional<DynamoDomainStore.Versioned<UserChallenge>> previous =
                            store.loadVersioned(
                                    entityType(), membership.getId(), UserChallenge.class
                            );
                    User user = userRepository.findById(membership.getUserId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Cannot reconcile challenge membership for missing user "
                                            + membership.getUserId()
                            ));
                    saveWithResolvedState(
                            membership,
                            targetChallenge,
                            user,
                            previous,
                            false
                    );
                    reconciled.add(new ReconciledMembership(membership, user));
                }
            } catch (RuntimeException failure) {
                if (!sameQuotaClassification(persistedChallenge, targetChallenge)) {
                    for (int index = reconciled.size() - 1; index >= 0; index--) {
                        ReconciledMembership completed = reconciled.get(index);
                        try {
                            Optional<DynamoDomainStore.Versioned<UserChallenge>> rollbackPrevious =
                                    store.loadVersioned(
                                    entityType(),
                                    completed.membership().getId(),
                                    UserChallenge.class
                            );
                            saveWithResolvedState(
                                    completed.membership(),
                                    persistedChallenge,
                                    completed.user(),
                                    rollbackPrevious,
                                    false
                            );
                        } catch (RuntimeException rollbackFailure) {
                            failure.addSuppressed(rollbackFailure);
                        }
                    }
                }
                throw failure;
            }
        } finally {
            for (int index = heldLockKeys.size() - 1; index >= 0; index--) {
                leases.release(entityType(), heldLockKeys.get(index));
            }
        }
    }

    @Override
    public void delete(UserChallenge membership) {
        if (membership == null || blank(membership.getId())) {
            return;
        }
        String lockKey = saveLockKey(membership);
        if (!blank(lockKey)) {
            leases.acquire(entityType(), lockKey);
        }
        try {
            store.loadVersioned(entityType(), membership.getId(), UserChallenge.class)
                    .ifPresent(current -> {
                        ParticipationQuotaStore.Mutation mutation =
                                participationQuotaStore.planDelete(current.value());
                        writer.delete(
                                entityType(),
                                membership.getId(),
                                allProjections(current.value()),
                                uniqueProjections(current.value()),
                                current.version(),
                                combineActions(
                                        mutation.actions(),
                                        participantCountStore.planDelete(current.value())
                                )
                        );
                    });
        } finally {
            if (!blank(lockKey)) {
                leases.release(entityType(), lockKey);
            }
        }
    }

    private UserChallenge saveWithResolvedState(
            UserChallenge membership,
            Challenge challenge,
            User user,
            Optional<DynamoDomainStore.Versioned<UserChallenge>> previous,
            boolean creating
    ) {
        Long expectedVersion = membership.getDynamoVersion() != null
                ? membership.getDynamoVersion()
                : previous.map(DynamoDomainStore.Versioned::version).orElse(null);
        for (int attempt = 1; attempt <= MAX_MEMBERSHIP_WRITE_ATTEMPTS; attempt++) {
            ParticipationQuotaStore.Mutation mutation = participationQuotaStore.planSave(
                    membership, user, challenge
            );
            List<TransactWriteItem> participantCountActions = participantCountStore.planSave(
                    previous.map(DynamoDomainStore.Versioned::value),
                    membership
            );
            try {
                writer.save(
                        entityType(),
                        membership.getId(),
                        membership,
                        allProjections(membership),
                        previous.map(DynamoDomainStore.Versioned::value)
                                .map(this::allProjections).orElseGet(List::of),
                        uniqueProjections(membership),
                        previous.map(DynamoDomainStore.Versioned::value)
                                .map(this::uniqueProjections).orElseGet(List::of),
                        creating,
                        expectedVersion,
                        combineActions(mutation.actions(), participantCountActions)
                );
                membership.setDynamoVersion(previous
                        .map(DynamoDomainStore.Versioned::version)
                        .orElse(0L) + 1L);
                return membership;
            } catch (ChallengeTransactionalWriter.ChallengeWriteConflictException conflict) {
                if (attempt == MAX_MEMBERSHIP_WRITE_ATTEMPTS) {
                    throw conflict;
                }
                // Another membership may have won either the fixed quota slot
                // or the challenge's fixed participant-counter item. The
                // failed transaction wrote nothing, so rebuilding both plans
                // is safe.
            }
        }
        throw new IllegalStateException("Participation quota retry loop exhausted");
    }

    private static boolean sameQuotaClassification(Challenge left, Challenge right) {
        return java.util.Objects.equals(left.getIsPrivate(), right.getIsPrivate())
                && java.util.Objects.equals(left.getActive(), right.getActive())
                && java.util.Objects.equals(left.getChallengeMode(), right.getChallengeMode())
                && java.util.Objects.equals(left.getStartDate(), right.getStartDate())
                && java.util.Objects.equals(left.getNumberOfDays(), right.getNumberOfDays());
    }

    private record ReconciledMembership(UserChallenge membership, User user) {
    }

    public Optional<UserChallenge> findByUserIdAndChallengeId(String userId, String challengeId) {
        if (blank(userId) || blank(challengeId)) {
            return Optional.empty();
        }
        List<UserChallenge> matches = store.queryAll(
                ChallengeRepositoryKeys.membershipUniquePk(userId, challengeId),
                ChallengeRepositoryKeys.ITEM_PREFIX,
                1,
                true,
                UserChallenge.class,
                true
        );
        return matches.stream().findFirst();
    }

    public ConditionalKeyLease.Lease acquireMembershipLease(
            String userId,
            String challengeId
    ) {
        if (blank(userId) || blank(challengeId)) {
            throw new IllegalArgumentException("userId and challengeId must not be blank");
        }
        return leases.acquireLease(entityType(), compositeLock(userId, challengeId));
    }

    public List<UserChallenge> findByChallengeId(String challengeId) {
        return joined(queryProjectionPartition(
                ChallengeRepositoryKeys.challengePk(challengeId),
                ChallengeRepositoryKeys.USER_CHALLENGE_PREFIX + "ALL#",
                false
        ));
    }

    public List<UserChallenge> findByUserId(String userId) {
        return joined(queryProjectionPartition(
                ChallengeRepositoryKeys.userPk(userId),
                ChallengeRepositoryKeys.USER_CHALLENGE_PREFIX + "ALL#",
                false
        ));
    }

    /** Newest-join-first membership history page with a real DynamoDB continuation key. */
    public DynamoPage<UserChallenge> findByUserIdPage(
            String userId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        if (blank(userId)) {
            return new DynamoPage<>(List.of(), Map.of());
        }
        return queryProjectionPage(
                ChallengeRepositoryKeys.userPk(userId),
                ChallengeRepositoryKeys.USER_CHALLENGE_PREFIX + "ALL#",
                limit,
                exclusiveStartKey,
                false
        );
    }

    public List<UserChallenge> findByUserIdAndChallengeIds(String userId, List<String> challengeIds) {
        if (blank(userId) || challengeIds == null || challengeIds.isEmpty()) {
            return List.of();
        }
        return joined(new LinkedHashSet<>(challengeIds).stream()
                .filter(challengeId -> !blank(challengeId))
                .map(challengeId -> findByUserIdAndChallengeId(userId, challengeId))
                .flatMap(Optional::stream)
                .toList());
    }

    /** Batch-resolves private-content membership for one bounded feed candidate page. */
    public Set<String> findActiveMembershipChallengeIds(
            String userId,
            Collection<String> candidateChallengeIds
    ) {
        if (blank(userId) || candidateChallengeIds == null || candidateChallengeIds.isEmpty()) {
            return Set.of();
        }
        Set<String> candidates = candidateChallengeIds.stream()
                .filter(challengeId -> !blank(challengeId))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<DynamoDbKey> keys = candidates.stream()
                .map(challengeId -> DynamoProjection.of(
                        ChallengeRepositoryKeys.membershipUniquePk(userId, challengeId),
                        ChallengeRepositoryKeys.uniqueSk()
                ).key())
                .toList();
        return store.batchGetProjections(keys, UserChallenge.class, true).stream()
                .filter(membership -> userId.equals(membership.getUserId()))
                .filter(membership -> Boolean.TRUE.equals(membership.getActive()))
                .filter(membership -> !Boolean.TRUE.equals(membership.getBanned()))
                .map(UserChallenge::getChallengeId)
                .filter(candidates::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public List<UserChallenge> findActiveByUserId(String userId) {
        return joined(queryProjectionPartition(
                ChallengeRepositoryKeys.userPk(userId),
                ChallengeRepositoryKeys.membershipStatusPrefix("ACTIVE") + '#',
                false
        ));
    }

    /** Newest-join-first active membership page with keyset pagination. */
    public DynamoPage<UserChallenge> findActiveByUserIdPage(
            String userId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        if (blank(userId)) {
            return new DynamoPage<>(List.of(), Map.of());
        }
        return queryProjectionPage(
                ChallengeRepositoryKeys.userPk(userId),
                ChallengeRepositoryKeys.membershipStatusPrefix("ACTIVE") + '#',
                limit,
                exclusiveStartKey,
                false
        );
    }

    public List<UserChallenge> findFavoritesByUserId(String userId) {
        return queryProjectionPartition(
                ChallengeRepositoryKeys.userPk(userId),
                ChallengeRepositoryKeys.USER_CHALLENGE_PREFIX + "FAVORITE#",
                false
        ).stream().sorted(FAVORITED_FIRST).toList();
    }

    /** Most-recently-favorited-first page with keyset pagination. */
    public DynamoPage<UserChallenge> findFavoritesByUserIdPage(
            String userId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        if (blank(userId)) {
            return new DynamoPage<>(List.of(), Map.of());
        }
        return queryProjectionPage(
                ChallengeRepositoryKeys.userPk(userId),
                ChallengeRepositoryKeys.USER_CHALLENGE_PREFIX + "FAVORITE#",
                limit,
                exclusiveStartKey,
                false
        );
    }

    public List<UserChallenge> findActiveByChallengeId(String challengeId) {
        return joined(queryProjectionPartition(
                ChallengeRepositoryKeys.challengePk(challengeId),
                ChallengeRepositoryKeys.membershipStatusPrefix("ACTIVE") + '#',
                false
        ));
    }

    /** Active members ordered by user ID with a real DynamoDB continuation key. */
    public DynamoPage<UserChallenge> findActiveByChallengeIdOrderedByUserPage(
            String challengeId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        if (blank(challengeId)) {
            return new DynamoPage<>(List.of(), Map.of());
        }
        return queryProjectionPage(
                ChallengeRepositoryKeys.challengePk(challengeId),
                ChallengeRepositoryKeys.activeMembershipByUserPrefix(),
                limit,
                exclusiveStartKey,
                true
        );
    }

    public List<UserChallenge> findActiveByChallengeIds(List<String> challengeIds) {
        if (challengeIds == null || challengeIds.isEmpty()) {
            return List.of();
        }
        Map<String, UserChallenge> distinct = new LinkedHashMap<>();
        new LinkedHashSet<>(challengeIds).stream()
                .filter(challengeId -> !blank(challengeId))
                .flatMap(challengeId -> findActiveByChallengeId(challengeId).stream())
                .forEach(membership -> distinct.putIfAbsent(membership.getId(), membership));
        return joined(distinct.values());
    }

    public List<UserChallenge> findByChallengeIds(List<String> challengeIds) {
        if (challengeIds == null || challengeIds.isEmpty()) {
            return List.of();
        }
        Map<String, UserChallenge> distinct = new LinkedHashMap<>();
        new LinkedHashSet<>(challengeIds).stream()
                .filter(challengeId -> !blank(challengeId))
                .flatMap(challengeId -> findByChallengeId(challengeId).stream())
                .forEach(membership -> distinct.putIfAbsent(membership.getId(), membership));
        return joined(distinct.values());
    }

    public List<UserChallenge> findByChallengeIdIn(List<String> challengeIds) {
        return findByChallengeIds(challengeIds);
    }

    public int countActiveByChallengeId(String challengeId) {
        return Math.toIntExact(participantCountStore.getActiveCount(challengeId));
    }

    /** Strongly consistent batch read of the fixed active-participant counters. */
    public Map<String, Integer> countActiveByChallengeIds(Collection<String> challengeIds) {
        Map<String, Long> counts = participantCountStore.getActiveCounts(challengeIds);
        Map<String, Integer> result = new LinkedHashMap<>();
        counts.forEach((challengeId, count) -> result.put(
                challengeId,
                Math.toIntExact(count)
        ));
        return Map.copyOf(result);
    }

    public long countSlotConsumingParticipations(String userId) {
        return countSlotConsumingParticipations(userId, null);
    }

    public long countSlotConsumingParticipationsByVisibility(
            String userId,
            boolean privateChallenge
    ) {
        return countSlotConsumingParticipations(userId, privateChallenge);
    }

    @Override
    protected void prepareForSave(UserChallenge membership, boolean creating) {
        if (membership.getJoinedAt() == null) {
            membership.setJoinedAt(Timestamp.from(clock.instant()));
        }
        if (membership.getActive() == null) {
            membership.setActive(true);
        }
        if (membership.getBanned() == null) {
            membership.setBanned(false);
        }
        if (membership.getCompleted() == null) {
            membership.setCompleted(false);
        }
        if (membership.getFavorite() == null) {
            membership.setFavorite(false);
        }
    }

    @Override
    protected String id(UserChallenge membership) {
        return membership.getId();
    }

    @Override
    protected void assignId(UserChallenge membership, String id) {
        membership.setId(id);
    }

    @Override
    protected String saveLockKey(UserChallenge membership) {
        if (blank(membership.getUserId()) || blank(membership.getChallengeId())) {
            return membership.getId();
        }
        return compositeLock(membership.getUserId(), membership.getChallengeId());
    }

    @Override
    protected Collection<DynamoProjection> uniqueProjections(UserChallenge membership) {
        if (blank(membership.getUserId()) || blank(membership.getChallengeId())) {
            return List.of();
        }
        return List.of(DynamoProjection.of(
                ChallengeRepositoryKeys.membershipUniquePk(
                        membership.getUserId(), membership.getChallengeId()
                ),
                ChallengeRepositoryKeys.uniqueSk()
        ));
    }

    @Override
    protected Collection<DynamoProjection> projections(UserChallenge membership) {
        String id = ChallengeRepositoryKeys.require(membership.getId(), "membership.id");
        String userId = ChallengeRepositoryKeys.require(membership.getUserId(), "membership.userId");
        String challengeId = ChallengeRepositoryKeys.require(
                membership.getChallengeId(), "membership.challengeId"
        );
        String allSk = ChallengeRepositoryKeys.membershipAllSk(membership.getJoinedAt(), id);
        List<DynamoProjection> projections = new ArrayList<>();
        projections.add(DynamoProjection.of(ChallengeRepositoryKeys.userPk(userId), allSk));
        projections.add(DynamoProjection.of(ChallengeRepositoryKeys.challengePk(challengeId), allSk));
        projections.add(DynamoProjection.of(
                ChallengeRepositoryKeys.membershipUniquePk(userId, challengeId),
                ChallengeRepositoryKeys.itemSk(id)
        ));

        if (Boolean.TRUE.equals(membership.getActive()) && !Boolean.TRUE.equals(membership.getBanned())) {
            addStatus(projections, membership, "ACTIVE");
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.challengePk(challengeId),
                    ChallengeRepositoryKeys.activeMembershipByUserSk(userId, id)
            ));
        } else {
            addStatus(projections, membership, "INACTIVE");
        }
        if (Boolean.TRUE.equals(membership.getCompleted())) {
            addStatus(projections, membership, "COMPLETED");
        }
        if (Boolean.TRUE.equals(membership.getBanned())) {
            addStatus(projections, membership, "BANNED");
        }
        if (Boolean.TRUE.equals(membership.getFavorite()) && !Boolean.TRUE.equals(membership.getBanned())) {
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.userPk(userId),
                    ChallengeRepositoryKeys.membershipFavoriteSk(
                            membership.getFavoritedAt(), membership.getJoinedAt(), id
                    )
            ));
        }
        if (Boolean.TRUE.equals(membership.getActive())
                && !Boolean.TRUE.equals(membership.getBanned())
                && !Boolean.TRUE.equals(membership.getCompleted())) {
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.reminderShardPk(userId),
                    ChallengeRepositoryKeys.reminderCandidateSk(userId, challengeId, id)
            ));
        }
        return projections;
    }

    private void addStatus(
            Collection<DynamoProjection> projections,
            UserChallenge membership,
            String status
    ) {
        String sk = ChallengeRepositoryKeys.membershipStatusSk(
                status, membership.getJoinedAt(), membership.getId()
        );
        projections.add(DynamoProjection.of(
                ChallengeRepositoryKeys.userPk(membership.getUserId()), sk
        ));
        projections.add(DynamoProjection.of(
                ChallengeRepositoryKeys.challengePk(membership.getChallengeId()), sk
        ));
    }

    private long countSlotConsumingParticipations(String userId, Boolean privateVisibility) {
        List<UserChallenge> memberships = findActiveByUserId(userId).stream()
                .filter(membership -> !Boolean.TRUE.equals(membership.getCompleted()))
                .toList();
        if (memberships.isEmpty()) {
            return 0L;
        }
        List<String> challengeIds = memberships.stream()
                .map(UserChallenge::getChallengeId)
                .distinct()
                .toList();
        Map<String, Challenge> challenges = new LinkedHashMap<>();
        store.batchGet("CHALLENGE", challengeIds, Challenge.class, true)
                .forEach(challenge -> challenges.put(challenge.getId(), challenge));
        LocalDate today = LocalDate.now(clock);
        return memberships.stream()
                .map(UserChallenge::getChallengeId)
                .map(challenges::get)
                .filter(java.util.Objects::nonNull)
                .filter(challenge -> Boolean.TRUE.equals(challenge.getActive()))
                .filter(challenge -> privateVisibility == null
                        || Boolean.TRUE.equals(challenge.getIsPrivate()) == privateVisibility)
                .filter(challenge -> consumesSlot(challenge, today))
                .count();
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

    private static List<UserChallenge> joined(Collection<UserChallenge> memberships) {
        return memberships.stream().sorted(JOINED_FIRST).toList();
    }

    private static String compositeLock(String userId, String challengeId) {
        return ChallengeRepositoryKeys.join("USER", userId, "CHALLENGE", challengeId);
    }

    private static List<TransactWriteItem> combineActions(
            Collection<TransactWriteItem> first,
            Collection<TransactWriteItem> second
    ) {
        List<TransactWriteItem> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
